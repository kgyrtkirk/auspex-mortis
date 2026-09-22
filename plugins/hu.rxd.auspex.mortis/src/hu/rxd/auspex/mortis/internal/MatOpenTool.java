package hu.rxd.auspex.mortis.internal;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.snapshot.SnapshotInfo;
import org.eclipse.mat.ui.MemoryAnalyserPlugin;
import org.eclipse.mat.ui.editor.PathEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import hu.rxd.auspex.mortis.internal.OpenSnapshots.Dump;

/**
 * Opens a heap dump in Memory Analyzer, so that the other tools have something to work on.
 */
public final class MatOpenTool implements IMcpTool {

	private static final int UI_TIMEOUT_SECONDS = 20;

	private static final int DEFAULT_WAIT_SECONDS = 120;

	private static final int MAX_WAIT_SECONDS = 7200;

	private static final long POLL_MILLIS = 500L;

	@Override
	public String getName() {
		return "mat_open";
	}

	@Override
	public String getDescription() {
		return "PARSES A HEAP DUMP AND WRITES INDEX FILES NEXT TO IT, then leaves it open in the IDE for the other mat_ tools. This is the one tool that starts the multi-minute, multi-gigabyte work the others refuse to start: a first parse of a large dump takes tens of minutes and its indexes take more disk than the dump itself, so the dump's own directory has to be writable and have room. A dump whose indexes are already there is reused instead, in seconds, and the answer says which of the two happened - 'indexes' reads 'reused' or 'parsed', by MAT's own rule that the index is present and no older than the dump. A dump that is already open is returned as it stands, without a second parse. The call waits up to 'waitSeconds' for the parse to finish and then returns with state 'parsing' rather than hanging: call it again with the same path to keep waiting, because the work carries on in the IDE either way. When the dump is gzipped it is also checked for the chunk size that lets MAT read it compressed - a dump made by 'jcmd GC.heap_dump -gz=N' has it, one made by plain 'gzip' does not, and the latter costs either a tenth of the uncompressed size in heap or a full decompression onto disk, which the answer warns about before the parse starts rather than after. Once the state reads 'open', ask auspex_help or go straight to mat_query.";
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "path":        {"type":"string","description":"The dump to open, as an absolute path, e.g. /data/dumps/broker.hprof.gz. MAT reads .hprof, .hprof.gz, .bin and the other formats it knows by extension."},
				    "waitSeconds": {"type":"integer","default":120,"minimum":0,"maximum":7200,"description":"How long to wait for the parse before answering 'parsing' and leaving it to run. 0 returns as soon as the dump is handed to MAT."}
				  },
				  "required": ["path"],
				  "additionalProperties": false
				}""";
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		long started = System.nanoTime();
		JsonObject result;
		try {
			result = open(ToolArguments.of(arguments), monitor);
		} catch (BadRequestException e) {
			return McpToolResult.error(e.getMessage());
		}
		result.put("elapsedMs", Long.valueOf((System.nanoTime() - started) / 1_000_000L));
		return McpToolResult.of(result.toString());
	}

	private JsonObject open(ToolArguments args, IProgressMonitor monitor) throws McpToolException, BadRequestException {
		File file = readable(args.getString("path"));
		JsonObject result = new JsonObject().put("dump", file.getAbsolutePath());
		Dump open = OpenSnapshots.at(file.getAbsolutePath());
		if (open != null) {
			return describe(open, result.put("state", "open").put("indexes", "alreadyOpen"));
		}
		DumpFile dump = new DumpFile(file);
		result.put("indexes", dump.indexesReusable() ? "reused" : "parsed");
		warnAboutCompression(dump, result);
		handToMemoryAnalyser(file);
		return waitForParse(file, result, args.getInt("waitSeconds", DEFAULT_WAIT_SECONDS, 0, MAX_WAIT_SECONDS), monitor);
	}

	private static File readable(String path) throws BadRequestException {
		if (path == null) {
			throw new BadRequestException("'path' is required: the heap dump to open, as an absolute path.");
		}
		File file = new File(path);
		if (!file.isFile()) {
			throw new BadRequestException("'%s' is not a file. Heap dumps are opened from this machine's own file system."
					.formatted(file.getAbsolutePath()));
		}
		if (!file.canRead()) {
			throw new BadRequestException("'%s' cannot be read.".formatted(file.getAbsolutePath()));
		}
		return file;
	}

	/**
	 * Says what a gzipped dump without a chunk size is about to cost.
	 * <p>
	 * A warning rather than a refusal: MAT can read such a dump, and whether the price is
	 * acceptable is the caller's to judge - but not after having paid it unknowingly.
	 */
	private static void warnAboutCompression(DumpFile dump, JsonObject result) throws BadRequestException {
		if (!dump.compressed()) {
			return;
		}
		try {
			if (dump.chunked()) {
				result.put("compression", "chunked gzip, read where it lies");
				return;
			}
		} catch (IOException e) {
			throw new BadRequestException("Could not read the gzip header of '%s': %s".formatted(dump.file(), e));
		}
		result.put("compression", "plain gzip").put("warning",
				"This dump is gzipped without the 'HPROF BLOCKSIZE=' comment that lets MAT read it compressed, so MAT will either hold a position index of roughly a tenth of the uncompressed size in heap or decompress the whole dump to disk, whichever fits. Recompressing it with 'jcmd GC.heap_dump -gz=1048576' avoids both.");
	}

	private static void handToMemoryAnalyser(File file) throws McpToolException, BadRequestException {
		if (!PlatformUI.isWorkbenchRunning()) {
			throw new BadRequestException(
					"No workbench is running, so there is nowhere to open a dump. These tools work through Memory Analyzer's editors.");
		}
		try {
			UiDispatch.call(() -> page().openEditor(new PathEditorInput(IPath.fromOSString(file.getAbsolutePath())),
					MemoryAnalyserPlugin.EDITOR_ID), UI_TIMEOUT_SECONDS);
		} catch (Exception e) {
			throw new McpToolException("Could not open '%s' in Memory Analyzer".formatted(file.getAbsolutePath()), e);
		}
	}

	/** The page the editor goes into, on the UI thread. */
	private static IWorkbenchPage page() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null) {
			IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
			if (windows.length == 0) {
				throw new IllegalStateException("the workbench has no window");
			}
			window = windows[0];
		}
		IWorkbenchPage page = window.getActivePage();
		if (page == null) {
			throw new IllegalStateException("the workbench window has no page");
		}
		return page;
	}

	/**
	 * Waits for the parse to finish, and answers either way.
	 * <p>
	 * The snapshot appears in the editor's query context when MAT's parse job is done; until
	 * then the dump is being read, and saying so beats a call that never returns.
	 */
	private JsonObject waitForParse(File file, JsonObject result, int seconds, IProgressMonitor monitor)
			throws McpToolException {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (true) {
			Dump dump = OpenSnapshots.at(file.getAbsolutePath());
			if (dump != null) {
				return describe(dump, result.put("state", "open"));
			}
			if (monitor.isCanceled() || System.currentTimeMillis() >= deadline) {
				return result.put("state", "parsing").put("note",
						"The dump is being read in the IDE and this call stopped waiting; the work carries on. Call mat_open again with the same path to keep waiting. A parse that never finishes has said why in the Error Log.");
			}
			sleep();
		}
	}

	private static void sleep() throws McpToolException {
		try {
			Thread.sleep(POLL_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the dump to be parsed", e);
		}
	}

	/** What the dump turned out to hold, so that the next question can be asked without a query. */
	static JsonObject describe(Dump dump, JsonObject result) {
		SnapshotInfo info = dump.snapshot().getSnapshotInfo();
		return result.put("objects", Integer.valueOf(info.getNumberOfObjects()))
				.put("classes", Integer.valueOf(info.getNumberOfClasses()))
				.put("usedHeapBytes", Long.valueOf(info.getUsedHeapSize())).put("jvm", info.getJvmInfo());
	}
}
