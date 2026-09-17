package oct.typhus.mat.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.snapshot.query.SnapshotQuery;

import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import oct.typhus.mat.internal.OpenSnapshots.Dump;
import oct.typhus.mat.internal.ResultJson.Limits;

/**
 * Runs a Memory Analyzer query against an open dump and returns its rows.
 */
public final class MatQueryTool extends SnapshotTool {

	private static final int DEFAULT_LIMIT = 100;

	private static final int DEFAULT_CHILD_LIMIT = 20;

	@Override
	public String getName() {
		return "mat_query";
	}

	@Override
	public String getDescription() {
		return "Runs a Memory Analyzer query against a heap dump that is ALREADY OPEN in the IDE and returns every column of the result as JSON rows. Takes the same command line the MAT query browser takes, so 'histogram', 'dominator_tree', 'list_objects 0x4038601f3b8', 'oql \"SELECT ...\"' and, when the Calcite plug-in is installed, 'calcite \"select ...\"' all work. Quoting is MAT's: a double quote toggles quoting and a backslash escapes the next character, so SQL with quoted identifiers goes in as calcite \"select ... from \\\"io.example.Foo\\\"\"; single quotes are not delimiters and are safe inside SQL. This runs through the API, not the user interface: no result tab is opened, no window is brought to the front, the clipboard is untouched, and the numeric columns that the widget tree cannot see come back with the rows, unformatted, so a size is a number and not '1.2 MB'. Rows carry the address of their object as a leading @address column wherever the result has one. It never opens or parses a dump: an unknown one is an error listing what is open. COST: the query runs in the IDE's own process against the snapshot it holds, and a query over millions of instances is as expensive here as it is in the UI. Bound a big class with a WHERE before an ORDER BY, and know that a Calcite query ignores cancellation and runs to completion even after this call returns.";
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
				    "childLimit": {"type":"integer","default":20,"minimum":1,"maximum":1000,"description":"Children rendered per expanded tree node."}
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
		IResult result = SnapshotQuery.parse(query, dump.snapshot()).execute(new MonitorListener(monitor));
		return ResultJson.render(result, id -> Addresses.of(dump.snapshot(), id), limits).put("query", query);
	}
}
