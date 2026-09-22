package hu.rxd.auspex.mortis.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.query.registry.QueryRegistry;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import hu.rxd.auspex.mortis.internal.OpenSnapshots.Dump;

/**
 * Orients an agent that arrived with no idea what these tools are for.
 */
public final class AuspexHelpTool implements IMcpTool {

	/** The MAT query the Calcite plug-in contributes, present only when it is installed. */
	private static final String CALCITE_QUERY = "calcite";

	@Override
	public String getName() {
		return "auspex_help";
	}

	@Override
	public String getDescription() {
		return "Explains what the mat_ tools are, which heap dumps they can answer about right now, and in what order to ask. Read this first when connecting to an IDE you have not worked in before: it reports live state - the dumps that are open and what each holds, whether the Calcite SQL plug-in is installed, whether a workbench is there to draw panes into - and the traps that cost an hour, such as a query over millions of instances or a Calcite statement that cannot be cancelled once started. It takes no arguments, reads nothing but the state of the IDE, and returns in milliseconds. It does not say what a leak looks like or which query proves one: that is yours to decide.";
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {},
				  "additionalProperties": false
				}""";
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		List<Dump> open = OpenSnapshots.all();
		JsonObject state = new JsonObject()
				.put("what",
						"Auspex Mortis answers questions about a Java heap dump through Memory Analyzer's API, in the IDE's own process. Every column of a result comes back as JSON rows, unformatted, so a size is a number rather than '1.2 MB', and the same result is opened as a MAT pane for whoever is at the IDE.")
				.put("dumps", dumps(open)).put("tools", tools()).put("start", start(open))
				.put("calcite", calcite()).put("panes", Boolean.valueOf(PlatformUI.isWorkbenchRunning()))
				.put("traps", traps());
		return McpToolResult.of(state.toString());
	}

	private static JsonArray dumps(List<Dump> open) {
		JsonArray dumps = new JsonArray();
		for (Dump dump : open) {
			dumps.add(MatOpenTool.describe(dump, new JsonObject().put("dump", dump.path())));
		}
		return dumps;
	}

	private static JsonArray tools() {
		return new JsonArray()
				.add(tool("mat_open", "Opens a dump and parses it when its indexes are not there yet. The only tool that starts that work; the others refuse to."))
				.add(tool("mat_query",
						"Any MAT command line - histogram, dominator_tree, list_objects 0x…, oql \"…\", calcite \"…\" - as rows with every column. 'sortBy' orders them, which is what turns a histogram into 'the biggest classes'."))
				.add(tool("mat_object",
						"One object by address: class, sizes, GC roots, fields with their targets resolved, what points at it, array slices. One call per hop down a graph."))
				.add(tool("mat_extract",
						"Writes the object graph below one object to a flat binary plus a report, so experiments continue in a plain JVM. Dry run by default."));
	}

	private static JsonObject tool(String name, String use) {
		return new JsonObject().put("tool", name).put("use", use);
	}

	private static JsonArray start(List<Dump> open) {
		JsonArray steps = new JsonArray();
		if (open.isEmpty()) {
			steps.add("No dump is open. Call mat_open with the path of one; a first parse of a large dump takes tens of minutes and writes indexes beside it.");
		} else if (open.size() > 1) {
			steps.add("Several dumps are open, so every other tool needs 'dump' to say which one, as its path or any trailing part of it.");
		}
		return steps
				.add("mat_query 'histogram' with sortBy 'Retained Heap' names the classes that hold the heap.")
				.add("mat_query 'dominator_tree' with sortBy 'Retained Heap' names the objects that hold it, which is the question a histogram cannot answer.")
				.add("mat_object with an address from either walks the graph from there; addresses come back in the '@address' column.")
				.add("mat_query 'calcite \"select …\"' aggregates and groups when a query browser answer will not do.");
	}

	private static JsonObject calcite() {
		boolean installed = QueryRegistry.instance().getQuery(CALCITE_QUERY) != null;
		return new JsonObject().put("available", Boolean.valueOf(installed)).put("note", installed
				? "Pass SQL as mat_query 'calcite \"select …\"'. MAT's quoting applies: a class name with dots goes in as \\\"io.example.Foo\\\"."
				: "The Calcite plug-in is not installed in this IDE, so 'calcite' queries will fail. OQL through mat_query 'oql \"…\"' is what is left for anything set-shaped.");
	}

	private static JsonArray traps() {
		return new JsonArray()
				.add("Cost is the caller's problem. A query over millions of instances is as expensive here as in the UI, and sorting materializes every row of it: bound a big class with a WHERE first.")
				.add("A Calcite query ignores cancellation. It runs to completion even after the call returns, so an unbounded one is not merely slow but unstoppable.")
				.add("An address belongs to the dump it came from. Passing one to another dump answers about whatever happens to live at that address there.")
				.add("Only mat_open parses. The other tools refuse an unknown dump rather than pay a multi-minute parse by accident.");
	}
}
