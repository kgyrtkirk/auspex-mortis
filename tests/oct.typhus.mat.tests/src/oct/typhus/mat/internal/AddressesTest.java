package oct.typhus.mat.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.mat.SnapshotException;
import org.junit.jupiter.api.Test;

class AddressesTest {

	@Test
	void parsesTheFormMatPrints() throws SnapshotException {
		assertEquals(0x4038601f3b8L, Addresses.parse("0x4038601f3b8"));
	}

	@Test
	void parsesTheFormAnInspectorRowCarries() throws SnapshotException {
		assertEquals(16L, Addresses.parse("@0x10"));
	}

	@Test
	void parsesDecimal() throws SnapshotException {
		assertEquals(4096L, Addresses.parse("4096"));
	}

	/** An address above 2^63 is a valid address and a negative long; signed parsing would reject it. */
	@Test
	void parsesAnAddressWithTheTopBitSet() throws SnapshotException {
		assertEquals(-1L, Addresses.parse("0xffffffffffffffff"));
	}

	@Test
	void refusesWhatIsNotAnAddress() {
		SnapshotException thrown = assertThrows(SnapshotException.class, () -> Addresses.parse("the big map"));
		assertEquals("'the big map' is not a heap address: expected 0x… or a decimal number", thrown.getMessage());
	}

	@Test
	void formatsAsMatDoes() {
		assertEquals("0xff", Addresses.format(255));
	}

	@Test
	void hasNoAddressForANegativeId() {
		assertNull(Addresses.of(null, -1));
	}
}
