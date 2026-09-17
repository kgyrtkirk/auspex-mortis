# 🜏 Auspex Mortis — MAT tools for the Eclipse MCP server

Three MCP tools that answer questions about a heap dump **already open** in Memory Analyzer
through MAT's API instead of its user interface. They contribute to the
`com.vogella.eclipse.mcp.core.tools` extension point of the
[Eclipse MCP server](https://github.com/vogellacompany/eclipse-mcp-server), so they appear
next to its own `eclipse_*` tools in any MCP client connected to the IDE.

Standalone: nothing here depends on the repository it currently sits in.

## 🎯 Why

Driving MAT through the widget layer costs a result tab per query, steals window focus, and
still cannot read the numbers: the widget tree exposes **column 0 only**, so every object
count, shallow size and retained size stays invisible. Bulk data cannot leave a dump at all —
MAT's CSV and HTML exports are JFace actions with no command behind them.

Running the same queries through `SnapshotQuery` in the IDE's own process removes all of it
at once: every column comes back, unformatted, as JSON; no tab is opened; no window is
fronted; the clipboard is untouched.

## 🔧 The tools

| tool | answers |
|---|---|
| `mat_query` | any MAT command line — `histogram`, `dominator_tree`, `list_objects 0x…`, `oql "…"`, `calcite "…"` — as rows with every column, addresses included |
| `mat_object` | one object: class, sizes, GC roots, fields with resolved reference targets, outbound and inbound references, array slices |
| `mat_extract` | the object graph below one object, written to a flat binary plus a report, so experiments can continue in a plain JVM |

All three are read-only against the snapshot. `mat_extract` writes a file and therefore
**defaults to a dry run**.

## 🪟 Panes — the point of running in the IDE

Every reading tool opens its result as an ordinary MAT pane by default (`show`). This is
not a concession: an agent that keeps the answer to itself leaves the person at the IDE
nothing to continue from. The pane holds the **same result object** the JSON rows were
rendered from — one execution, two readers — so "the top 20 by retained heap" is literally
the table on screen, sorting included.

* `sortBy` / `desc` order through MAT's own `RefinedResultBuilder`. The ordered result is
  what both the rows and the pane are built from; direction defaults to MAT's rule per
  column, numbers descending and text ascending.
* `title` names the tab. Without it the command line is used, cut to 60 characters, which
  beats a tab labelled with a whole SQL statement. The pane's *identifier* stays the real
  command, so MAT can still re-run it.
* A `calcite "…"` query opens the **Calcite plug-in's own pane**, statement in its editor
  and result beneath, so it can be changed and re-run by hand. That plug-in exports
  `com.github.vlsi.mat.calcite` and `.functions` but not the package its pane lives in, so
  `getQueryString()` and `initQueryResult(…)` are reached **by reflection**, both resolved
  before anything is added to the editor. A version that no longer has them logs a warning
  and falls back to the plain result pane. Exporting that package upstream would remove the
  reflection entirely.

Without this, running `calcite "…"` from MAT's own query browser has the same gap: panes
are chosen by result *type*, and the SQL editor only exists in the pane its toolbar button
opens.

### 🧭 Rules they all follow

* **Never open or parse a dump.** An unknown dump is an error that lists what is open.
  Parsing is a multi-minute, multi-gigabyte operation the IDE has already paid for.
* **The dump is found through the editors**, by way of `MultiPaneEditor#getQueryContext()` —
  `org.eclipse.mat.ui.snapshot.editor` is not an exported package, the editor package is.
  With one dump open, `dump` can be left out.
* **Numbers stay numbers.** `Bytes` is unwrapped to a long rather than formatted as "1.2 MB".
* **Cost is the caller's problem.** A query over millions of instances is as expensive here
  as in the UI, and a Calcite query ignores cancellation: bound a big class with `WHERE`.

## 🏗️ Build

Tycho, pomless, with a target platform resolved from p2 — the 2026-09 platform, MAT 1.17.0
and the MCP server's own update site. Needs network on a cold cache, nothing else.

```bash
mvn clean verify
```

Produces `update-site/hu.rxd.auspex.mortis.repository/target/repository`, a p2 repository, and runs
the tests on the way.

**MAT's version must match the IDE's.** The snapshot is handed over in process; a bundle
compiled against a different MAT resolves and then fails on a class the two cannot share.

## 📥 Install

```
eclipse_add_repository  url: file:/…/auspex-mortis/update-site/hu.rxd.auspex.mortis.repository/target/repository, dryRun: false
eclipse_install         unit: hu.rxd.auspex.mortis.feature.feature.group, wait: true
eclipse_restart
```

**The restart is not optional.** `McpToolRegistry` reads the extension point once and caches
it for the life of the IDE — nothing calls its `reset()` — so a newly installed tool is
invisible until the IDE comes back up. `eclipse_install_bundle` is worse than useless here:
a hot-installed bundle does not survive the restart that would publish it.

The MCP client also has to re-list the tools afterwards, which usually means a new session.

## 📤 What `mat_extract` writes

`out` gets the binary, `out + ".json"` gets the report — the same summary the call returns:
bytes per class, what the filters excluded, what could not travel, and which limit stopped
the walk. **The report is the diagnostic; the binary is fuel.**

The binary layout is documented in the class comment of `GraphExtract`. A reader is written
per restored type, in the repository that owns those types. There is deliberately no generic
restorer: rebuilding a class reflectively whose fields drifted since the dump fails
*silently*, which is the one failure mode an extract must not have.

The walk follows field and array references only. Classes and class loaders are never
entered — every object points at its class, and from a class the walk reaches the loader,
its other classes and most of the heap.

## 🧪 Tests

`tests/hu.rxd.auspex.mortis.tests` is a **fragment** of the plug-in, so it sees the internal
classes without any of them being exported. `tycho-surefire` runs it in a real headless
Equinox — `useUIHarness=false`, no display needed — with `failIfNoTests`, because a build
that discovers nothing and reports success is the failure that matters.

What is covered is what can be covered without a parsed dump: address parsing, the JSON
contract of query results (columns, the `@address` column, `Bytes` as numbers, offset and
limit, tree expansion and its `more` flag) against hand-built `IResultTable`/`IResultTree`
implementations, and the schema and naming contract every tool owes the server.

`ResultJson` takes an id-to-address function rather than the snapshot for exactly this
reason: it is the only thing it needed a snapshot for.

CI is GitHub Actions, [`.github/workflows/build.yml`](.github/workflows/build.yml): JDK 25,
`mvn -B clean verify`, the Maven cache carrying Tycho's p2 cache, the update site as an
artifact and the surefire output kept when a run fails.

## 🕳️ Known gaps

* **The Calcite pane is held by reflection** — see above. It degrades to the plain pane and
  says so in the log, but it is the one place a Calcite plug-in upgrade can break this.
* **Nothing verifies the panes automatically.** Whether a pane opened, and what it shows, is
  checked by a person looking at the IDE; the tests cover the rows and the contracts.
* **Nothing that needs a snapshot is tested** — `GraphExtract`'s walk and writer, and both
  object and query tools end to end. That needs a small dump committed as a fixture, or a
  fake `ISnapshot`, and neither is free.
* **Tier 2 and 3 are not here**: reading and writing widget text, invoking JFace actions,
  cancelling jobs, bundle reload, opening a file outside the workspace. Those touch the UI
  and belong in a separate bundle, so that a MAT upgrade cannot break them.
