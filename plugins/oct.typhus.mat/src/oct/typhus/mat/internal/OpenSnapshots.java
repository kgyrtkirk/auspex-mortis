package oct.typhus.mat.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.mat.query.IQueryContext;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.ui.editor.MultiPaneEditor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.UiDispatch;

/**
 * The heap dumps a person has open in Memory Analyzer, and the live snapshot behind each.
 * <p>
 * These tools never open a dump themselves. Parsing one is a multi-minute, multi-gigabyte
 * operation that the IDE has already paid for, and a mistyped path would pay it again for
 * nothing, so an unknown dump is an error rather than an invitation to index it.
 */
final class OpenSnapshots {

	/** How long the workbench may take to answer before the tool gives up on it. */
	private static final int UI_TIMEOUT_SECONDS = 10;

	/** An open heap dump: the file the IDE knows it by, and the snapshot to query. */
	record Dump(String path, ISnapshot snapshot) {
	}

	private OpenSnapshots() {
	}

	/**
	 * Resolves the dump to work on.
	 *
	 * @param wanted an absolute path, or any trailing part of one, or {@code null} when the
	 *               caller expects a single dump to be open
	 * @throws BadRequestException when the choice is empty or ambiguous, which the caller
	 *                             fixes by opening a dump or naming one
	 */
	static Dump resolve(String wanted) throws McpToolException, BadRequestException {
		List<Dump> open = all();
		if (open.isEmpty()) {
			throw new BadRequestException(
					"No heap dump is open in the IDE. Open one in Memory Analyzer first: these tools query the snapshot the IDE already holds and never parse a dump themselves.");
		}
		if (wanted == null) {
			if (open.size() == 1) {
				return open.get(0);
			}
			throw new BadRequestException(
					"%d heap dumps are open, so 'dump' has to say which one: %s".formatted(open.size(), paths(open)));
		}
		List<Dump> matches = new ArrayList<>();
		for (Dump dump : open) {
			if (dump.path().equals(wanted) || dump.path().endsWith(wanted)) {
				matches.add(dump);
			}
		}
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.isEmpty()) {
			throw new BadRequestException("No open heap dump matches '%s'. Open dumps: %s".formatted(wanted, paths(open)));
		}
		throw new BadRequestException("'%s' matches %d open heap dumps: %s".formatted(wanted, matches.size(), paths(matches)));
	}

	/** Every dump that is open and finished parsing, in the order the editors were opened. */
	static List<Dump> all() throws McpToolException {
		try {
			return UiDispatch.call(OpenSnapshots::collect, UI_TIMEOUT_SECONDS);
		} catch (Exception e) {
			throw new McpToolException("Could not read the editors of the workbench", e);
		}
	}

	static String paths(List<Dump> dumps) {
		return dumps.stream().map(Dump::path).toList().toString();
	}

	/**
	 * Reads the snapshot out of each Memory Analyzer editor, on the UI thread.
	 * <p>
	 * Through the query context rather than the editor input, because
	 * {@code org.eclipse.mat.ui.snapshot.editor} is not an exported package while
	 * {@link MultiPaneEditor} is, and the context is also the one place that answers for
	 * an editor whose dump is still being parsed: it is set when the parse job finishes.
	 */
	private static List<Dump> collect() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return List.of();
		}
		Map<String, Dump> byPath = new LinkedHashMap<>();
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorReference reference : page.getEditorReferences()) {
					// false: an editor that was never restored holds no snapshot, and
					// restoring it here would parse a dump behind the person's back
					IEditorPart editor = reference.getEditor(false);
					if (!(editor instanceof MultiPaneEditor pane)) {
						continue;
					}
					IQueryContext context = pane.getQueryContext();
					if (context != null && context.get(ISnapshot.class, null) instanceof ISnapshot snapshot) {
						String path = snapshot.getSnapshotInfo().getPath();
						byPath.putIfAbsent(path, new Dump(path, snapshot));
					}
				}
			}
		}
		return List.copyOf(byPath.values());
	}
}
