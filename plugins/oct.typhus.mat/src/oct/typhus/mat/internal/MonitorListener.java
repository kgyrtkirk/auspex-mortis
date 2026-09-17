package oct.typhus.mat.internal;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.util.VoidProgressListener;

/**
 * Carries the cancellation of an MCP request into Memory Analyzer.
 * <p>
 * Only the queries that check it stop: a Calcite query does not, and runs to completion
 * inside the IDE whatever the caller does.
 */
final class MonitorListener extends VoidProgressListener {

	private final IProgressMonitor monitor;

	MonitorListener(IProgressMonitor monitor) {
		this.monitor = monitor;
	}

	@Override
	public boolean isCanceled() {
		return super.isCanceled() || monitor.isCanceled();
	}
}
