package hu.rxd.auspex.mortis.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import org.eclipse.mat.query.Bytes;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IContextObject;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.IResultTree;
import org.eclipse.mat.query.ResultMetaData;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.Json;

import hu.rxd.auspex.mortis.internal.ResultJson.Limits;

/**
 * The shape of the JSON a query answers with, which is the contract every caller reads.
 */
class ResultJsonTest {

	private static final IntFunction<String> ADDRESSES = Addresses::format;

	@Test
	void anAggregateResultCarriesNoAddressColumn() {
		Map<String, Object> rendered = render(
				new Table(new Column[] { new Column("info", String.class), new Column("n", Long.class) },
						List.<Object[]>of(new Object[] { "total", Long.valueOf(17) }), new int[] { -1 }),
				new Limits(10, 0, 0, 10));

		assertEquals("table", rendered.get("kind"));
		assertEquals(List.of("info", "n"), labels(rendered));
		assertEquals(List.of(List.of("total", Long.valueOf(17))), rendered.get("rows"));
	}

	/** A size must arrive as a number, not as the "1.2 MB" a reader cannot add up. */
	@Test
	void sizesArriveUnformatted() {
		Map<String, Object> rendered = render(new Table(new Column[] { new Column("Retained Heap", Bytes.class) },
				List.<Object[]>of(new Object[] { new Bytes(2048) }), new int[] { -1 }), new Limits(10, 0, 0, 10));

		assertEquals(List.of(List.of(Long.valueOf(2048))), rendered.get("rows"));
	}

	@Test
	void theAddressColumnAppearsWhenARowHasAnObject() {
		Map<String, Object> rendered = render(new Table(new Column[] { new Column("Class Name", String.class) },
				List.<Object[]>of(new Object[] { "java.lang.String" }, new Object[] { "int[]" }), new int[] { 3, -1 }),
				new Limits(10, 0, 0, 10));

		assertEquals(List.of("@address", "Class Name"), labels(rendered));
		assertEquals(List.of(List.of("0x3", "java.lang.String"), rowWithoutAddress()), rendered.get("rows"));
	}

	@Test
	void offsetAndLimitSayWhatWasLeftOut() {
		List<Object[]> rows = new ArrayList<>();
		for (int row = 0; row < 5; row++) {
			rows.add(new Object[] { Integer.valueOf(row) });
		}
		Map<String, Object> rendered = render(
				new Table(new Column[] { new Column("n", Integer.class) }, rows, new int[] { -1, -1, -1, -1, -1 }),
				new Limits(2, 1, 0, 10));

		assertEquals(Long.valueOf(5), rendered.get("rowCount"));
		assertEquals(Long.valueOf(1), rendered.get("offset"));
		assertEquals(Long.valueOf(2), rendered.get("returned"));
		assertTrue((Boolean) rendered.get("truncated"));
		assertEquals(List.of(List.of(Long.valueOf(1)), List.of(Long.valueOf(2))), rendered.get("rows"));
	}

	@Test
	void anUnexpandedBranchSaysThatItHasMore() {
		Map<String, Object> rendered = render(tree(), new Limits(10, 0, 0, 10));

		Map<?, ?> root = (Map<?, ?>) ((List<?>) rendered.get("rows")).get(0);
		assertEquals(List.of("root"), root.get("v"));
		assertTrue((Boolean) root.get("more"));
		assertNull(root.get("children"));
	}

	@Test
	void childLimitCutsAndSaysSo() {
		Map<String, Object> rendered = render(tree(), new Limits(10, 0, 1, 1));

		Map<?, ?> root = (Map<?, ?>) ((List<?>) rendered.get("rows")).get(0);
		assertEquals(List.of(List.of("first")), root.get("children"));
		assertTrue((Boolean) root.get("more"));
	}

	@Test
	void aFullyExpandedBranchDoesNotClaimMore() {
		Map<String, Object> rendered = render(tree(), new Limits(10, 0, 1, 10));

		Map<?, ?> root = (Map<?, ?>) ((List<?>) rendered.get("rows")).get(0);
		assertEquals(List.of(List.of("first"), List.of("second")), root.get("children"));
		assertFalse(root.containsKey("more"));
	}

	private static Map<String, Object> render(IResult result, Limits limits) {
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = (Map<String, Object>) Json
				.parse(ResultJson.render(result, ADDRESSES, limits).toString());
		return parsed;
	}

	private static List<Object> labels(Map<String, Object> rendered) {
		List<Object> labels = new ArrayList<>();
		((List<?>) rendered.get("columns")).forEach(column -> labels.add(((Map<?, ?>) column).get("label")));
		return labels;
	}

	/** A row of a table with an address column whose own object is unknown. */
	private static List<Object> rowWithoutAddress() {
		List<Object> row = new ArrayList<>();
		row.add(null);
		row.add("int[]");
		return row;
	}

	private static Tree tree() {
		return new Tree(new Branch("root", List.of(new Branch("first", List.of()), new Branch("second", List.of()))));
	}

	private record Branch(String label, List<Branch> children) {
	}

	private static final class Table implements IResultTable {

		private final Column[] columns;

		private final List<Object[]> rows;

		private final int[] objectIds;

		Table(Column[] columns, List<Object[]> rows, int[] objectIds) {
			this.columns = columns;
			this.rows = rows;
			this.objectIds = objectIds;
		}

		@Override
		public ResultMetaData getResultMetaData() {
			return null;
		}

		@Override
		public Column[] getColumns() {
			return columns;
		}

		@Override
		public Object getColumnValue(Object row, int columnIndex) {
			return rows.get(((Integer) row).intValue())[columnIndex];
		}

		@Override
		public IContextObject getContext(Object row) {
			int objectId = objectIds[((Integer) row).intValue()];
			return objectId < 0 ? null : () -> objectId;
		}

		@Override
		public Object getRow(int rowId) {
			return Integer.valueOf(rowId);
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}
	}

	private static final class Tree implements IResultTree {

		private final Branch root;

		Tree(Branch root) {
			this.root = root;
		}

		@Override
		public ResultMetaData getResultMetaData() {
			return null;
		}

		@Override
		public Column[] getColumns() {
			return new Column[] { new Column("Name", String.class) };
		}

		@Override
		public Object getColumnValue(Object row, int columnIndex) {
			return ((Branch) row).label();
		}

		@Override
		public IContextObject getContext(Object row) {
			return null;
		}

		@Override
		public List<?> getElements() {
			return List.of(root);
		}

		@Override
		public boolean hasChildren(Object element) {
			return !((Branch) element).children().isEmpty();
		}

		@Override
		public List<?> getChildren(Object parent) {
			return ((Branch) parent).children();
		}
	}
}
