package hu.rxd.auspex.mortis.internal;

import java.util.List;
import java.util.Locale;

import org.eclipse.mat.query.registry.CommandLine;

/**
 * The SQL of a {@code calcite "…"} command line, and how it is laid out for reading.
 * <p>
 * A statement written as one line is what a command line needs and the last thing a person
 * wants in an editor, so the pane gets it broken at its clauses. The rewrite only ever adds
 * whitespace, and never inside a quoted name or a string literal: the statement in the pane
 * is one somebody will edit and run again, so changing what it means would be far worse
 * than leaving it unreadable.
 */
final class Sql {

	/** Clause keywords that start a new line, longest first so that 'group by' wins over 'group'. */
	private static final List<String> CLAUSES = List.of("left outer join", "right outer join", "full outer join",
			"cross join", "inner join", "left join", "right join", "union all", "group by", "order by", "natural join",
			"intersect", "having", "select", "except", "union", "where", "limit", "offset", "from", "join", "on");

	/** Indent of a continuation line, which lines a column list up under the first column. */
	private static final String INDENT = "       ";

	private Sql() {
	}

	/** The statement of a {@code calcite "…"} command line, or {@code null} for any other query. */
	static String statementOf(String command) {
		if (command == null) {
			return null;
		}
		String[] tokens = CommandLine.tokenize(command);
		return tokens.length == 2 && "calcite".equalsIgnoreCase(tokens[0]) ? tokens[1] : null;
	}

	/**
	 * Breaks a statement at its clauses and at the commas of its outermost list.
	 * <p>
	 * Quoted regions and anything inside brackets are copied through untouched, which is
	 * what keeps a class name like {@code "…Order"} and a literal like {@code 'group by'}
	 * from being taken for keywords.
	 */
	static String format(String sql) {
		if (sql == null || sql.isBlank()) {
			return sql;
		}
		String collapsed = sql.replaceAll("\\s+", " ").trim();
		StringBuilder out = new StringBuilder(collapsed.length() + 32);
		int depth = 0;
		for (int at = 0; at < collapsed.length(); at++) {
			char current = collapsed.charAt(at);
			if (current == '\'' || current == '"') {
				at = copyQuoted(collapsed, at, out);
				continue;
			}
			if (current == '(') {
				depth++;
			} else if (current == ')') {
				depth--;
			}
			if (depth == 0 && current == ',') {
				out.append(",\n").append(INDENT);
				at = skipSpace(collapsed, at);
				continue;
			}
			String clause = depth == 0 ? clauseAt(collapsed, at) : null;
			if (clause != null) {
				if (!out.isEmpty()) {
					trimTrailingSpace(out);
					out.append('\n');
				}
				out.append(collapsed, at, at + clause.length());
				at += clause.length() - 1;
				continue;
			}
			out.append(current);
		}
		return out.toString();
	}

	/**
	 * Copies a quoted run, closing quote included, and answers the index of that quote.
	 * <p>
	 * A doubled quote is how SQL escapes one inside a literal, so it continues the run.
	 */
	private static int copyQuoted(String sql, int start, StringBuilder out) {
		char quote = sql.charAt(start);
		out.append(quote);
		for (int at = start + 1; at < sql.length(); at++) {
			char current = sql.charAt(at);
			out.append(current);
			if (current == quote) {
				if (at + 1 < sql.length() && sql.charAt(at + 1) == quote) {
					out.append(quote);
					at++;
					continue;
				}
				return at;
			}
		}
		// unterminated: the parser will say so far better than this could
		return sql.length() - 1;
	}

	/** The clause keyword starting at {@code at} when a whole word matches one, else {@code null}. */
	private static String clauseAt(String sql, int at) {
		if (at > 0 && isWordCharacter(sql.charAt(at - 1))) {
			return null;
		}
		String rest = sql.substring(at).toLowerCase(Locale.ROOT);
		for (String clause : CLAUSES) {
			int end = at + clause.length();
			if (rest.startsWith(clause) && (end == sql.length() || !isWordCharacter(sql.charAt(end)))) {
				return clause;
			}
		}
		return null;
	}

	private static boolean isWordCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_' || character == '.';
	}

	private static int skipSpace(String sql, int at) {
		int next = at;
		while (next + 1 < sql.length() && sql.charAt(next + 1) == ' ') {
			next++;
		}
		return next;
	}

	private static void trimTrailingSpace(StringBuilder out) {
		while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
			out.setLength(out.length() - 1);
		}
	}
}
