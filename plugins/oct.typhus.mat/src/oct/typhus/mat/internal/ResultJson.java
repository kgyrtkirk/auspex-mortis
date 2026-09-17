package oct.typhus.mat.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.eclipse.mat.query.Bytes;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IContextObject;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultPie;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.IResultTree;
import org.eclipse.mat.query.IStructuredResult;
import org.eclipse.mat.query.results.CompositeResult;
import org.eclipse.mat.query.results.TextResult;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The result of a Memory Analyzer query as JSON.
 * <p>
 * Every column comes back, with the values MAT computes rather than the strings it
 * formats, and each row carries the address of its object where it has one. That is the
 * whole point of running queries through the API: a result pane shows the first column to
 * a reader and nothing at all to a program.
 */
final class ResultJson {

	/** How much of a result to render. */
	record Limits(int limit, int offset, int depth, int childLimit) {
	}

	/** A rendered row, with its children when the result is a tree. */
	private record Node(String address, List<Object> values, List<Node> children, boolean more) {
	}

	private ResultJson() {
	}

	/**
	 * Renders a result.
	 *
	 * @param addresses turns the object id of a row into the address to print, or
	 *                  {@code null} for a row whose object the snapshot cannot map
	 */
	static JsonObject render(IResult result, IntFunction<String> addresses, Limits limits) {
		return switch (result) {
		case null -> new JsonObject().put("kind", "none").put("note",
				"The query ran and produced no result. MAT reports an empty object set this way.");
		case IResultTable table -> table(table, addresses, limits);
		case IResultTree tree -> tree(tree, addresses, limits);
		case TextResult text -> new JsonObject().put("kind", "text").put("html", Boolean.valueOf(text.isHtml()))
				.put("text", text.getText());
		case CompositeResult composite -> composite(composite, addresses, limits);
		case IResultPie pie -> pie(pie, addresses);
		default -> new JsonObject().put("kind", "unsupported").put("resultClass", result.getClass().getName())
				.put("note", "The query answered with a result type these tools cannot render as rows.");
		};
	}

	private static JsonObject table(IResultTable table, IntFunction<String> addresses, Limits limits) {
		int rowCount = table.getRowCount();
		int from = Math.min(limits.offset(), rowCount);
		int to = Math.min(from + limits.limit(), rowCount);
		List<Node> rows = new ArrayList<>(to - from);
		for (int row = from; row < to; row++) {
			rows.add(node(table, table.getRow(row), addresses, List.of(), false));
		}
		return structured(table, rows).put("kind", "table").put("rowCount", Integer.valueOf(rowCount))
				.put("offset", Integer.valueOf(from)).put("returned", Integer.valueOf(rows.size()))
				.put("truncated", Boolean.valueOf(to < rowCount));
	}

	private static JsonObject tree(IResultTree tree, IntFunction<String> addresses, Limits limits) {
		List<?> elements = tree.getElements();
		int shown = Math.min(elements.size(), limits.limit());
		List<Node> rows = new ArrayList<>(shown);
		for (int row = 0; row < shown; row++) {
			rows.add(branch(tree, elements.get(row), addresses, limits, limits.depth()));
		}
		return structured(tree, rows).put("kind", "tree").put("rowCount", Integer.valueOf(elements.size()))
				.put("returned", Integer.valueOf(rows.size()))
				.put("truncated", Boolean.valueOf(shown < elements.size()))
				.put("note",
						"A row is {v:[values], addr, children:[rows], more}. 'more' means the row has children that 'depth' or 'childLimit' cut off.");
	}

	private static Node branch(IResultTree tree, Object element, IntFunction<String> addresses, Limits limits, int depth) {
		if (depth <= 0 || !tree.hasChildren(element)) {
			return node(tree, element, addresses, List.of(), depth <= 0 && tree.hasChildren(element));
		}
		List<?> children = tree.getChildren(element);
		int shown = children == null ? 0 : Math.min(children.size(), limits.childLimit());
		List<Node> rendered = new ArrayList<>(shown);
		for (int child = 0; child < shown; child++) {
			rendered.add(branch(tree, children.get(child), addresses, limits, depth - 1));
		}
		return node(tree, element, addresses, rendered, children != null && shown < children.size());
	}

	private static Node node(IStructuredResult result, Object row, IntFunction<String> addresses, List<Node> children,
			boolean more) {
		Column[] columns = result.getColumns();
		List<Object> values = new ArrayList<>(columns.length);
		for (int column = 0; column < columns.length; column++) {
			values.add(value(result.getColumnValue(row, column)));
		}
		return new Node(address(result, row, addresses), values, children, more);
	}

	/**
	 * The rendered rows plus the columns that describe them.
	 * <p>
	 * The address is a column of its own, first, and only when some row actually has one:
	 * an aggregate result has no objects behind its rows, and a leading null on every one
	 * of them would say nothing.
	 */
	private static JsonObject structured(IStructuredResult result, List<Node> rows) {
		boolean addressed = rows.stream().anyMatch(ResultJson::addressed);
		JsonArray columns = new JsonArray();
		if (addressed) {
			columns.add(new JsonObject().put("label", "@address").put("type", "address"));
		}
		for (Column column : result.getColumns()) {
			columns.add(new JsonObject().put("label", column.getLabel()).put("type", typeOf(column)));
		}
		JsonArray rendered = new JsonArray();
		rows.forEach(row -> rendered.add(row(row, addressed)));
		return new JsonObject().put("columns", columns).put("rows", rendered);
	}

	private static boolean addressed(Node node) {
		return node.address() != null || node.children().stream().anyMatch(ResultJson::addressed);
	}

	private static Object row(Node node, boolean addressed) {
		JsonArray values = new JsonArray();
		if (addressed) {
			values.add(node.address());
		}
		node.values().forEach(values::add);
		if (node.children().isEmpty() && !node.more()) {
			return values;
		}
		JsonObject wrapped = new JsonObject().put("v", values);
		if (!node.children().isEmpty()) {
			JsonArray children = new JsonArray();
			node.children().forEach(child -> children.add(row(child, addressed)));
			wrapped.put("children", children);
		}
		if (node.more()) {
			wrapped.put("more", Boolean.TRUE);
		}
		return wrapped;
	}

	private static JsonObject composite(CompositeResult composite, IntFunction<String> addresses, Limits limits) {
		JsonArray parts = new JsonArray();
		for (CompositeResult.Entry entry : composite.getResultEntries()) {
			parts.add(render(entry.getResult(), addresses, limits).put("name", entry.getName()));
		}
		return new JsonObject().put("kind", "composite").put("name", composite.getName()).put("parts", parts);
	}

	private static JsonObject pie(IResultPie pie, IntFunction<String> addresses) {
		JsonArray slices = new JsonArray();
		for (IResultPie.Slice slice : pie.getSlices()) {
			slices.add(new JsonObject().put("label", slice.getLabel()).put("value", Double.valueOf(slice.getValue()))
					.put("description", slice.getDescription())
					.put("address", slice.getContext() == null ? null : addresses.apply(slice.getContext().getObjectId())));
		}
		return new JsonObject().put("kind", "pie").put("slices", slices);
	}

	private static String address(IStructuredResult result, Object row, IntFunction<String> addresses) {
		IContextObject context = result.getContext(row);
		return context == null ? null : addresses.apply(context.getObjectId());
	}

	/**
	 * The unformatted value, so that a number stays a number.
	 * <p>
	 * {@link Bytes} is unwrapped because it is MAT's way of saying "this long is a size",
	 * and its own {@code toString} would turn 1,048,576 into "1 MB".
	 */
	private static Object value(Object raw) {
		return switch (raw) {
		case null -> null;
		case Bytes bytes -> Long.valueOf(bytes.getValue());
		case Number number -> number;
		case Boolean bool -> bool;
		case String text -> text;
		default -> String.valueOf(raw);
		};
	}

	private static String typeOf(Column column) {
		Class<?> type = column.getType();
		return type == null ? "object" : type.getSimpleName();
	}
}
