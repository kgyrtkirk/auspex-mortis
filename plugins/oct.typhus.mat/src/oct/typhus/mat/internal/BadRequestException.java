package oct.typhus.mat.internal;

/**
 * Thrown when the caller can fix the failure by sending different arguments.
 * <p>
 * Separate from {@code McpToolException}, which says the IDE could not do the work: this
 * one comes back as a tool error carrying the message, and names what to send instead.
 */
final class BadRequestException extends Exception {

	private static final long serialVersionUID = 1L;

	BadRequestException(String message) {
		super(message);
	}
}
