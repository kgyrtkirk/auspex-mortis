package hu.rxd.auspex.mortis.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.Column.SortDirection;
import org.eclipse.mat.query.IStructuredResult;
import org.eclipse.mat.query.refined.RefinedResultBuilder;
import org.eclipse.mat.query.registry.CommandLine;
import org.eclipse.mat.query.registry.QueryResult;

import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import hu.rxd.auspex.mortis.internal.OpenSnapshots.Dump;
import hu.rxd.auspex.mortis.internal.ResultJson.Limits;

/**
 * Runs a Memory Analyzer query against an open dump and returns its rows.
 */
public final class MatQueryTool extends SnapshotTool {

	private static final int DEFAULT_LIMIT = 100;

	private static final int DEFAULT_CHILD_LIMIT = 20;

	/** Roughly what fits on a tab before the rest is unreadable anyway. */
	private static final int MAX_TITLE = 60;

	@Override
	public String getName() {
		return "mat_query";
	}

	@Override
	public String getDescription() {
		return "Runs a Memory Analyzer query against a heap dump that is ALREADY OPEN in the IDE and returns every column of the result as JSON rows. Takes the same command line the MAT query browser takes, so 'histogram', 'dominator_tree', 'list_objects 0x4038601f3b8', 'oql \"SELECT ...\"' and, when the Calcite plug-in is installed, 'calcite \"select ...\"' all work. Quoting is MAT's: a double quote toggles quoting and a backslash escapes the next character, so SQL with quoted identifiers goes in as calcite \"select ... from \\\"io.example.Foo\\\"\"; single quotes are not delimiters and are safe inside SQL. This runs through the API rather than by driving the user interface: no window is brought to the front, the clipboard is untouched, and the numeric columns that the widget tree cannot see come back with the rows, unformatted, so a size is a number and not '1.2 MB'. The result IS opened as an ordinary MAT pane by default, because the person at the IDE continues from where this left off - the pane is the same result these rows came from, not a second execution, and 'show' false suppresses it for a probe whose pane would only be noise. Rows carry the address of their object as a leading @address column wherever the result has one. It never opens or parses a dump: an unknown one is an error listing what is open. 'sortBy' names a column to order by - 'Retained Heap' on a histogram, say - and the pane shows that same ordered result, so the rows read here and the table on screen are one table and not two. COST: the query runs in the IDE's own process against the snapshot it holds, and a query over millions of instances is as expensive here as it is in the UI; sorting materializes every row of it. Bound a big class with a WHERE before an ORDER BY, and know that a Calcite query ignores cancellation and runs to completion even after this call returns.";
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "query":      {"type":"string","description":"The MAT command line, e.g. histogram, list_objects 0x…, oql \\"SELECT …\\", calcite \\"select …\\"."},
				    %s,
				    "limit":      {"type":"integer","default":100,"minimum":1,"maximum":10000,"description":"Rows to return; root rows for a tree."},
				    "offset":     {"type":"integer","default":0,"minimum":0,"description":"First row to return. Tables only; a tree ignores it."},
				    "depth":      {"type":"integer","default":0,"minimum":0,"maximum":10,"description":"Levels of a tree result to expand. 0 returns the roots and says which of them have children."},
				    "childLimit": {"type":"integer","default":20,"minimum":1,"maximum":1000,"description":"Children rendered per expanded tree node."},
				    "show":       {"type":"boolean","default":true,"description":"Also open the result as an ordinary MAT pane in the heap editor, so the person at the IDE can carry on from it by hand. Pass false for a probe whose pane would only be noise."},
				    "sortBy":     {"type":"string","description":"Order by this column, named exactly as the column label reads, e.g. 'Retained Heap'. This is what makes 'histogram' answer 'the biggest classes' instead of MAT's natural order. COSTS a full materialization of the result, so bound a huge one with the query first."},
				    "desc":       {"type":"boolean","description":"Descending. Left out, MAT decides per column: numbers descend, text ascends."},
				    "title":      {"type":"string","description":"What the tab should read, e.g. 'byte[] over 50 MB'. Without it the command line is used, cut to fit, which makes a poor tab for a long SQL statement. The pane still re-runs the real command."}
				  },
				  "required": ["query"],
				  "additionalProperties": false
				}""".formatted(DUMP_PROPERTY);
	}

	@Override
	protected JsonObject run(Dump dump, Map<String, Object> arguments, IProgressMonitor monitor)
			throws SnapshotException, BadRequestException {
		ToolArguments args = ToolArguments.of(arguments);
		String query = args.getString("query");
		if (query == null) {
			throw new BadRequestException("'query' is required, for example: histogram");
		}
		Limits limits = new Limits(args.getInt("limit", DEFAULT_LIMIT, 1, 10000), args.getInt("offset", 0, 0, Integer.MAX_VALUE),
				args.getInt("depth", 0, 0, 10), args.getInt("childLimit", DEFAULT_CHILD_LIMIT, 1, 1000));
		// through the registry rather than SnapshotQuery, because the QueryResult it
		// discards is what the editor needs to show the very result these rows came from
		QueryResult executed = CommandLine.parse(dump.context(), query).execute(new MonitorListener(monitor));
		QueryResult answer = sorted(executed, dump, args);
		JsonObject rendered = ResultJson
				.render(answer == null ? null : answer.getSubject(), id -> Addresses.of(dump.snapshot(), id), limits)
				.put("query", query);
		String sql = Sql.statementOf(query);
		if (sql != null) {
			// the statement as it reads, so that what is discussed here and what stands in
			// the pane are the same thing rather than one line against nine
			rendered.put("sql", Sql.format(sql));
		}
		if (args.getBoolean("show", true)) {
			ResultPanes.show(dump, answer, paneTitle(query, args.getString("title")), rendered);
		}
		return rendered;
	}

	/**
	 * What the tab reads.
	 * <p>
	 * A whole SQL statement makes a useless tab, so a caller may name the pane after the
	 * question it answers; without one the command line is cut to something that still fits
	 * on a tab. The pane's identifier stays the command either way, so MAT can still re-run it.
	 */
	static String paneTitle(String command, String given) {
		if (given != null) {
			return given;
		}
		String line = command.replaceAll("\\s+", " ").trim();
		return line.length() <= MAX_TITLE ? line : line.substring(0, MAX_TITLE - 1) + "…";
	}

	/**
	 * The result ordered by the column the caller named, or the result as it came.
	 * <p>
	 * Through MAT's own refinement, and the refined result is what both the rows and the
	 * pane are built from: whoever is at the IDE has to be looking at the order the answer
	 * was read in, or the two of us are discussing different tables.
	 */
	private static QueryResult sorted(QueryResult executed, Dump dump, ToolArguments args) throws BadRequestException {
		String column = args.getString("sortBy");
		if (column == null) {
			return executed;
		}
		if (executed == null || !(executed.getSubject() instanceof IStructuredResult structured)) {
			throw new BadRequestException(
					"'sortBy' needs a result with columns, and this query produced none. Drop it, or ask a query that answers with a table or a tree.");
		}
		RefinedResultBuilder builder = new RefinedResultBuilder(dump.context(), structured);
		int index = builder.getColumnIndexByName(column);
		if (index < 0) {
			throw new BadRequestException("This result has no column '%s'. Its columns are %s.".formatted(column,
					builder.getColumns().stream().map(Column::getLabel).toList()));
		}
		builder.setSortOrder(index, direction(args));
		return new QueryResult(executed.getQuery(), executed.getCommand(), builder.build());
	}

	/** Null leaves the choice to MAT, which descends a numeric column and ascends the rest. */
	private static SortDirection direction(ToolArguments args) {
		if (!args.has("desc")) {
			return null;
		}
		return args.getBoolean("desc", true) ? SortDirection.DESC : SortDirection.ASC;
	}
}
