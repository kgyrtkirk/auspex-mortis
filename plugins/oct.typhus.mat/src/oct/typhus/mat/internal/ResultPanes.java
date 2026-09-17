package oct.typhus.mat.internal;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.ILog;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IStructuredResult;
import org.eclipse.mat.query.registry.CommandLine;
import org.eclipse.mat.query.registry.QueryResult;
import org.eclipse.mat.ui.MemoryAnalyserPlugin;
import org.eclipse.mat.ui.QueryExecution;
import org.eclipse.mat.ui.editor.AbstractEditorPane;
import org.eclipse.mat.ui.editor.EditorPaneRegistry;
import org.eclipse.mat.ui.util.PaneState;
import org.eclipse.mat.ui.util.PaneState.PaneType;
import org.eclipse.swt.custom.StyledText;

import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import oct.typhus.mat.internal.OpenSnapshots.Dump;

/**
 * Puts a result in front of the person at the IDE, as a pane they can carry on from.
 * <p>
 * An agent that answers only to itself leaves nobody anything to continue with, which is
 * why every reading tool shows its result by default. The pane holds the same result
 * object the rows were rendered from: one execution, two readers.
 */
final class ResultPanes {

	/** How long the workbench may take to open a pane before the tool stops waiting. */
	private static final int UI_TIMEOUT_SECONDS = 20;

	/** The pane the Calcite plug-in contributes, which is the one with the SQL editor in it. */
	private static final String CALCITE_PANE_ID = "CALCITE";

	private ResultPanes() {
	}

	/**
	 * Shows {@code result}, and records in {@code into} what happened.
	 * <p>
	 * Never throws: the rows are the answer, and a pane that would not open must not take
	 * them down with it.
	 */
	static void show(Dump dump, QueryResult result, String title, JsonObject into) {
		if (result == null) {
			into.put("shownInIde", Boolean.FALSE).put("showError", "The query produced no result to show.");
			return;
		}
		try {
			into.put("shownInIde", Boolean.TRUE).put("pane", UiDispatch.call(() -> display(dump, result, title),
					UI_TIMEOUT_SECONDS));
		} catch (Exception e) {
			into.put("shownInIde", Boolean.FALSE).put("showError", String.valueOf(e.getMessage()));
		}
	}

	/** Which pane was opened, on the UI thread. */
	private static String display(Dump dump, QueryResult result, String title) {
		String sql = calciteSql(result.getCommand());
		if (sql != null && calcitePane(dump, result, title, sql)) {
			return "calcite";
		}
		IResult subject = result.getSubject();
		if (!(subject instanceof IStructuredResult)) {
			// a composite or an HTML report needs MAT's own conversion, and that path
			// takes its title from the command line
			QueryExecution.displayResult(dump.editor(), null, null, result, true);
			return "mat";
		}
		AbstractEditorPane pane = EditorPaneRegistry.instance().createNewPane(subject, null);
		// the identifier stays the command line, which is what 'execute again' re-runs;
		// only the tab label is ours
		PaneState state = new PaneState(PaneType.QUERY, null, result.getCommand(), true);
		state.setImage(MemoryAnalyserPlugin.getDefault().getImage(result.getQuery()));
		pane.setPaneState(state);
		dump.editor().addNewPage(pane, result, title, MemoryAnalyserPlugin.getDefault().getImage(result.getQuery()));
		return "result";
	}

	/**
	 * Opens the Calcite plug-in's own pane, with the statement in its editor and the result
	 * below it, so the query can be changed and run again by hand.
	 * <p>
	 * By reflection, because that plug-in exports {@code com.github.vlsi.mat.calcite} and
	 * {@code .functions} but not the package its pane lives in, so neither a dependency nor
	 * a cast can reach the two public methods this needs. Both are looked up before
	 * anything is added to the editor: a version of the plug-in that no longer has them
	 * leaves no half-built pane behind, only a warning in the log and the plain result
	 * pane the caller would otherwise have had.
	 *
	 * @return whether the pane was opened
	 */
	private static boolean calcitePane(Dump dump, QueryResult result, String title, String sql) {
		try {
			AbstractEditorPane pane = EditorPaneRegistry.instance().createNewPane(CALCITE_PANE_ID);
			if (pane == null) {
				return false;
			}
			Method queryString = pane.getClass().getMethod("getQueryString");
			Method initQueryResult = pane.getClass().getMethod("initQueryResult", QueryResult.class, PaneState.class);
			pane.setPaneState(new PaneState(PaneType.COMPOSITE_PARENT, null, pane.getTitle(), false));
			dump.editor().addNewPage(pane, null, title, pane.getTitleImage());
			// after the page is added, because the widget does not exist before it
			((StyledText) queryString.invoke(pane)).setText(sql);
			initQueryResult.invoke(pane, result, null);
			return true;
		} catch (Exception e) {
			ILog.get().warn("Could not open the Calcite pane for this query, falling back to the plain result pane", e);
			return false;
		}
	}

	/** The statement of a {@code calcite "…"} command line, or {@code null} for anything else. */
	private static String calciteSql(String command) {
		if (command == null) {
			return null;
		}
		String[] tokens = CommandLine.tokenize(command);
		return tokens.length == 2 && "calcite".equalsIgnoreCase(tokens[0]) ? tokens[1] : null;
	}
}
