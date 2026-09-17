package oct.typhus.mat.internal;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;

/**
 * Heap addresses as a person writes them and as MAT keeps them.
 */
final class Addresses {

	private Addresses() {
	}

	/** Parses {@code 0x4038601f3b8}, {@code @0x…} or a decimal address. */
	static long parse(String text) throws SnapshotException {
		String digits = text.startsWith("@") ? text.substring(1) : text;
		try {
			boolean hex = digits.startsWith("0x") || digits.startsWith("0X");
			return hex ? Long.parseUnsignedLong(digits.substring(2), 16) : Long.parseUnsignedLong(digits);
		} catch (NumberFormatException e) {
			throw new SnapshotException("'%s' is not a heap address: expected 0x… or a decimal number".formatted(text), e);
		}
	}

	/** The form every MAT pane and every one of these tools prints. */
	static String format(long address) {
		return "0x" + Long.toHexString(address);
	}

	/** The address of an object, or {@code null} when the id is not one. */
	static String of(ISnapshot snapshot, int objectId) {
		if (objectId < 0) {
			return null;
		}
		try {
			return format(snapshot.mapIdToAddress(objectId));
		} catch (SnapshotException e) {
			// a row whose context object the snapshot cannot map is still worth
			// returning, without the address it does not have
			return null;
		}
	}
}
