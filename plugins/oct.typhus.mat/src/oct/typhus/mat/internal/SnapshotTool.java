package oct.typhus.mat.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.registry.QueryResult;
import org.eclipse.mat.ui.QueryExecution;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import oct.typhus.mat.internal.OpenSnapshots.Dump;

/**
 * A tool that works on the snapshot of a heap dump open in Memory Analyzer.
 * <p>
 * Subclasses answer the question; this class picks the dump, times the work and turns the
 * two failures every one of them shares into a message the model can act on.
 */
abstract class SnapshotTool implements IMcpTool {

	/** The {@code dump} property, for the input schema of every subclass. */
	protected static final String DUMP_PROPERTY = """
			"dump": {"type":"string","description":"Which open heap dump to work on, as its path or any trailing part of it. Only needed when more than one is open; the error lists them."}""";

	/** How long the workbench may take to open a pane before the tool stops waiting for it. */
	private static final int UI_TIMEOUT_SECONDS = 20;

	@Override
	public final McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		long started = System.nanoTime();
		JsonObject result;
		String path;
		try {
			Dump dump = OpenSnapshots.resolve(ToolArguments.of(arguments).getString("dump"));
			path = dump.path();
			result = run(dump, arguments, monitor);
		} catch (BadRequestException e) {
			return McpToolResult.error(e.getMessage());
		} catch (SnapshotException e) {
			// MAT reports a query that does not parse, an address that is not in the dump
			// and a snapshot that cannot answer all as this one exception, and all three
			// are fixed by sending different arguments
			return McpToolResult.error(message(e));
		}
		if (monitor.isCanceled()) {
			return McpToolResult.error("The request was cancelled. The work in the IDE may still be running.");
		}
		result.put("dump", path);
		result.put("elapsedMs", Long.valueOf((System.nanoTime() - started) / 1_000_000L));
		return McpToolResult.of(result.toString());
	}

	/**
	 * Answers the question against the given dump.
	 *
	 * @param arguments as the client sent them, because {@link ToolArguments} reads the
	 *                  scalars and some tools also take lists
	 * @return the payload of the answer, to which the caller adds the dump and the duration
	 */
	protected abstract JsonObject run(Dump dump, Map<String, Object> arguments, IProgressMonitor monitor)
			throws McpToolException, SnapshotException, BadRequestException;

	/**
	 * Puts a result in front of the person at the IDE, as the pane Memory Analyzer would
	 * have opened for it, and records in {@code into} whether that worked.
	 * <p>
	 * Never throws: the rows are the answer and a pane that failed to open must not take
	 * them down with it. The pane is the same object the query already produced, so this
	 * costs a redraw, not a second execution.
	 */
	protected static void show(Dump dump, QueryResult result, JsonObject into) {
		if (result == null) {
			into.put("shownInIde", Boolean.FALSE).put("showError", "The query produced no result to show.");
			return;
		}
		try {
			UiDispatch.call(() -> {
				QueryExecution.displayResult(dump.editor(), null, null, result, true);
				return null;
			}, UI_TIMEOUT_SECONDS);
			into.put("shownInIde", Boolean.TRUE);
		} catch (Exception e) {
			into.put("shownInIde", Boolean.FALSE).put("showError", String.valueOf(e.getMessage()));
		}
	}

	/** The message of {@code e} and of its cause, because MAT often carries the detail there. */
	private static String message(SnapshotException e) {
		String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
		Throwable cause = e.getCause();
		return cause == null || cause.getMessage() == null || message.contains(cause.getMessage()) ? message
				: message + ": " + cause.getMessage();
	}
}
