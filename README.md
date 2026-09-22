# 🜏 Auspex Mortis — MAT tools for the Eclipse MCP server

Three MCP tools that answer questions about a heap dump **already open** in Memory Analyzer
through MAT's API instead of its user interface. They contribute to the
`com.vogella.eclipse.mcp.core.tools` extension point of the
[Eclipse MCP server](https://github.com/vogellacompany/eclipse-mcp-server), so they appear
next to its own `eclipse_*` tools in any MCP client connected to the IDE.

Home: <https://github.com/kgyrtkirk/auspex-mortis> · update site:
`https://kgyrtkirk.github.io/auspex-mortis/`

## 🎯 Why

Driving MAT through the widget layer costs a result tab per query, steals window focus, and
still cannot read the numbers: the widget tree exposes **column 0 only**, so every object
count, shallow size and retained size stays invisible. Bulk data cannot leave a dump at all —
MAT's CSV and HTML exports are JFace actions with no command behind them.

Running the same queries through MAT's API in the IDE's own process removes all of it at
once: every column comes back, unformatted, as JSON; no window is fronted; the clipboard is
untouched. The result pane still opens — on purpose, see below.

## 🔧 The tools

| tool | answers |
|---|---|
| `auspex_help` | what these tools are, which dumps are open right now, in what order to ask, and what costs an hour |
| `mat_open` | opens a dump and parses it when its indexes are not there yet, so the rest have something to work on |
| `mat_query` | any MAT command line — `histogram`, `dominator_tree`, `list_objects 0x…`, `oql "…"`, `calcite "…"` — as rows with every column, addresses included |
| `mat_object` | one object: class, sizes, GC roots, fields with resolved reference targets, outbound and inbound references, array slices |
| `mat_extract` | the object graph below one object, written to a flat binary plus a report, so experiments can continue in a plain JVM |

The three reading tools are read-only against the snapshot. `mat_extract` writes a file and
therefore **defaults to a dry run**; `mat_open` is the one that starts a parse, which costs
tens of minutes and writes indexes beside the dump, and it says which of the two — parse or
reuse — it did.

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

* **Only `mat_open` parses.** For the reading tools an unknown dump is an error that lists
  what is open: parsing is a multi-minute, multi-gigabyte operation, and paying it by accident
  for a mistyped path is the one failure that must not happen quietly. Asked for it by name,
  `mat_open` pays it deliberately and reports whether it parsed or reused the indexes, by
  MAT's own rule that the index is there and no older than the dump.
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

Produces two things, and runs the tests on the way:

* `update-site/hu.rxd.auspex.mortis.repository/target/repository` — the p2 repository.
* `product/hu.rxd.auspex.mortis.product/target/products/…tar.gz` — Memory Analyzer with
  Calcite, the MCP server and these tools already installed, launcher `auspex`. 156 MB
  packed, 176 MB unpacked, `linux/gtk/x86_64` only.

`.github/scripts/compose-site.sh <repository> <dir>` lays the published site out: a p2
composite whose children are that repository and the sites named in the target platform, so
one address installs everything. It reads those children out of the target platform rather
than repeating them, and marks the composite non-atomic, so a child that is down costs its
own content and not the whole site.

**MAT's version must match the IDE's.** The snapshot is handed over in process; a bundle
compiled against a different MAT resolves and then fails on a class the two cannot share.

## 📥 Install

Two ways, and neither needs anything installed first.

**The product** — unpack the archive and run `./auspex`. It is Memory Analyzer, with Calcite,
the MCP server and these tools already in it, at the versions this build was tested against.
It also answers headless, through MAT's own application, with no display:

```bash
./auspex -nosplash -consoleLog -data /scratch/ws -application org.eclipse.mat.api.parse dump.hprof
./auspex -data /scratch/ws --launcher.openFile dump.hprof   # the workbench, and the endpoint with it
```

**Update site: `https://kgyrtkirk.github.io/auspex-mortis/`** — published from `main` by
GitHub Actions, so it always carries the last build that passed its tests. It is a composite:
adding that one address also gives the IDE
[Memory Analyzer](https://download.eclipse.org/mat/1.17.0/update-site/) 1.17, the
[Eclipse MCP server](https://vogellacompany.github.io/eclipse-mcp-server/) and the
[Calcite plug-in](https://vlsi.github.io/mat-calcite-plugin-update-site/stable/), so a missing
prerequisite is no longer a second trip. Calcite stays optional; with it, `calcite "…"`
queries open in its SQL editor pane.

By hand: *Help → Install New Software… → Add…* → the URL above → **Auspex Mortis**. Restart.

From an agent, over the MCP server:

```
eclipse_add_repository  url: https://kgyrtkirk.github.io/auspex-mortis/, dryRun: false
eclipse_install         unit: hu.rxd.auspex.mortis.feature.feature.group, wait: true
eclipse_restart
```

A local build installs the same way, from
`file:/…/auspex-mortis/update-site/hu.rxd.auspex.mortis.repository/target/repository`. Bump the
version first: p2 treats a rebuilt `0.4.0` as the thing it already has.

**The restart is not optional.** `McpToolRegistry` reads the extension point once and caches
it for the life of the IDE — nothing calls its `reset()` — so a newly installed tool is
invisible until the IDE comes back up. `eclipse_install_bundle` is worse than useless here:
a hot-installed bundle does not survive the restart that would publish it. The restart also
closes any open heap dump, because MAT's editor input cannot be persisted: reopen it by hand.

The MCP client picks the new tools up when it reconnects. Its copy of a tool's schema can lag
behind; that is harmless, because the server validates the arguments.

## 🤖 Headless

`product/hu.rxd.auspex.mortis.product/auspex-headless.sh <product-dir> <dump> [workspace]`
starts the product with nobody watching and prints the endpoint to talk to it:

```
{"state":"listening","url":"http://127.0.0.1:8643/mcp","token":"…","workspace":"/scratch/ws"}
```

`AUSPEX_PORT`, `AUSPEX_HEAP`, `AUSPEX_DISPLAY` and `AUSPEX_WAIT` are its knobs. The workbench
really runs, against `Xvfb`: panes open, `calcite` answers, and the tools behave as they do on
a desktop.

It also does what the preferences page would otherwise be needed for. The MCP server is **off
by default** — a process that listens on a socket is opt-in — so the script enables it for one
workspace by writing `…/.settings/com.vogella.eclipse.mcp.server.prefs`, and keeps the bearer
token beside that workspace with `-Dcom.vogella.eclipse.mcp.tokenDirectory`, so a run cannot
take over the token of the IDE its user is sitting in. The URL and the token are written to
`<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json`.

**`--launcher.openFile` needs `dbus-launch`.** The launcher hands the path to the instance over
D-Bus; without it the file is dropped, the script says so, and `mat_open` is the way in.

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
implementations, the schema and naming contract every tool owes the server, and what
`mat_open` can tell about a dump before MAT touches it — where MAT will put the index, when it
counts as reusable, and whether a gzipped dump carries the `HPROF BLOCKSIZE=` comment that
lets MAT read it compressed.

`ResultJson` takes an id-to-address function rather than the snapshot for exactly this
reason: it is the only thing it needed a snapshot for.

## ⚙️ CI and publishing

GitHub Actions, two workflows:

* [`build.yml`](.github/workflows/build.yml) — the core: JDK 25, `mvn -B clean verify`, the
  Maven cache carrying Tycho's p2 cache, surefire output kept when a run fails, the update
  site packaged as an artifact. Pull requests run it directly.
* [`pages.yml`](.github/workflows/pages.yml) — on every push to `main`: calls `build.yml`, then
  deploys that very artifact to GitHub Pages. What is published is what was tested; a
  deployment is never cancelled half way.

**One-time setup** in the repository: *Settings → Pages → Build and deployment → Source:
GitHub Actions*. Until then the deploy job fails; the build still runs.

The site is a plain p2 repository at the root, not a composite, so each deploy replaces the
previous build. Pinning older versions would need a composite with one child per release —
not worth it until someone asks to stay on an old one.

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
