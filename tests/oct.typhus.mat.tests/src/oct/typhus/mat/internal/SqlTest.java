package oct.typhus.mat.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The layout of a statement handed to the Calcite pane.
 * <p>
 * Whitespace may change; nothing else may, because the statement in that pane is one a
 * person is about to edit and run again.
 */
class SqlTest {

	@Test
	void takesTheStatementOutOfACalciteCommandLine() {
		assertEquals("select 1 from \"byte[]\"", Sql.statementOf("calcite \"select 1 from \\\"byte[]\\\"\""));
	}

	@Test
	void hasNoStatementForAnyOtherQuery() {
		assertNull(Sql.statementOf("histogram"));
		assertNull(Sql.statementOf("oql \"SELECT * FROM java.lang.String\""));
		assertNull(Sql.statementOf(null));
	}

	@Test
	void breaksAtTheClauses() {
		assertEquals("""
				select a
				from "t"
				where a > 1
				order by 1 desc""", Sql.format("select a from \"t\" where a > 1 order by 1 desc"));
	}

	@Test
	void putsEachColumnOfTheOutermostListOnItsOwnLine() {
		assertEquals("""
				select a,
				       b
				from "t\"""", Sql.format("select a, b from \"t\""));
	}

	/** A comma inside a call belongs to that call, not to the column list. */
	@Test
	void leavesTheArgumentsOfACallAlone() {
		assertEquals("""
				select getByKey(m, 'k')
				from "t\"""", Sql.format("select getByKey(m, 'k') from \"t\""));
	}

	/** A class name is a quoted identifier and may contain anything at all. */
	@Test
	void neverBreaksInsideAQuotedName() {
		assertEquals("""
				select 1
				from "io.example.OrderBy From Where\"""",
				Sql.format("select 1 from \"io.example.OrderBy From Where\""));
	}

	@Test
	void neverBreaksInsideAStringLiteral() {
		assertEquals("""
				select 1
				from "t"
				where name = 'group by from where'""",
				Sql.format("select 1 from \"t\" where name = 'group by from where'"));
	}

	/** Doubled quotes are how a literal carries one, and must not end the run. */
	@Test
	void survivesAQuoteInsideALiteral() {
		assertEquals("""
				select 1
				from "t"
				where name = 'it''s from here'""", Sql.format("select 1 from \"t\" where name = 'it''s from here'"));
	}

	/** 'from' inside a word is not a clause, however the word is spelled. */
	@Test
	void doesNotBreakInsideAnIdentifier() {
		assertEquals("""
				select fromTo,
				       t.from_date
				from "t\"""", Sql.format("select fromTo, t.from_date from \"t\""));
	}

	@Test
	void leavesNothingToFormatAlone() {
		assertNull(Sql.format(null));
		assertEquals("  ", Sql.format("  "));
	}
}
