<img src="branding/auspex-mortis-wordmark.png" alt="Auspex Mortis" width="620">

# 🜏 Auspex Mortis — MAT tools for the Eclipse MCP server

Five MCP tools that open a Java heap dump in Memory Analyzer and answer questions about it
through MAT's API instead of its user interface. They contribute to the
`com.vogella.eclipse.mcp.core.tools` extension point of the
[Eclipse MCP server](https://github.com/vogellacompany/eclipse-mcp-server), so they appear
next to its own `eclipse_*` tools in any MCP client connected to the IDE.

They ship two ways: as a plug-in for an IDE that already has Memory Analyzer, and as a
**product** — Memory Analyzer with Calcite, the MCP server and these tools already in it,
which also runs with nobody watching and hands an agent an endpoint against a dump.

* Home: <https://github.com/kgyrtkirk/auspex-mortis>
* Update site: <https://kgyrtkirk.github.io/auspex-mortis/>

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
  Calcite, the MCP server and these tools already installed, launcher `auspex` and
  `auspex-headless.sh` beside it. 160 MB packed, `linux/gtk/x86_64` only.

`hu.rxd.auspex.mortis.product.feature` exists to carry that script as a root file and nothing
else: the plug-in's own feature stays free of it, so installing the tools into somebody's IDE
never drops a shell script into their installation.

### 🜏 Branding

[`branding/`](branding/) holds the mark as SVG; the PNGs under
[`plugins/hu.rxd.auspex.mortis/icons/`](plugins/hu.rxd.auspex.mortis/icons/) are rendered from
it and wired to the product through `windowImages` and `aboutImage` in
[plugin.xml](plugins/hu.rxd.auspex.mortis/plugin.xml), which is why the window, the task bar
and *About Auspex Mortis* carry it.

```bash
inkscape branding/auspex-mortis-small.svg -o plugins/hu.rxd.auspex.mortis/icons/auspex-32.png -w 32
inkscape branding/auspex-mortis.svg       -o plugins/hu.rxd.auspex.mortis/icons/auspex-256.png -w 256
inkscape branding/auspex-mortis-wordmark.svg -o plugins/hu.rxd.auspex.mortis/icons/auspex-about.png -w 480
```

16 and 32 come from the simplified mark, because a twelve-tooth cog turns to porridge below
48. The About banner is 480 wide, and an About image wider than 250 makes the workbench drop
the `aboutText` beside it — the banner says the name instead, which is the trade taken here.

`.github/scripts/compose-site.sh <repository> <dir> <product>` lays the published site out: a p2
composite whose children are that repository and the sites named in the target platform, so
one address installs everything. It reads those children out of the target platform rather
than repeating them, and marks the composite non-atomic, so a child that is down costs its
own content and not the whole site. The product archive is published beside it, linked from
the site's `index.html`.

### 🔢 Versions

Every build already gets its own version: Tycho appends a qualifier, which is why a jar reads
`0.5.0.202609221904`. Out of the box that qualifier is the build clock, so the same source
builds a different version every time and none of them says where it came from.

`tycho-buildtimestamp-jgit`, configured in the parent pom, makes it **the timestamp of the
commit that last touched the module** instead. It is in the build and not in CI on purpose: a
laptop and a pipeline building the same commit then produce the same version, and nothing
rots in a workflow file nobody runs locally. A dirty working tree cannot be traced to any
commit, so the build warns and falls back to the clock for that build alone:

```
[WARNING] Working tree is dirty.
[WARNING] Fallback to default timestamp provider
```

The base version — `0.5.0` — is the release, and moving it is one command that rewrites the
poms, the manifests, the feature and the product together:

```bash
mvn tycho-versions:set-version -DnewVersion=0.6.0-SNAPSHOT
```

Tag the commit that carries it, and the tag and the artefacts agree by construction.

**MAT's version must match the IDE's.** The snapshot is handed over in process; a bundle
compiled against a different MAT resolves and then fails on a class the two cannot share.

## 📥 Install

Two ways, and neither needs anything installed first.

**The product** — unpack the archive and run `./auspex`. It is Memory Analyzer, with Calcite,
the MCP server and these tools already in it, at the versions this build was tested against,
and **the endpoint is on out of the box**: nothing to enable, no preference page to visit. It
listens on `http://127.0.0.1:8642/mcp`, and the URL and bearer token are written to
`<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json` for a client to
read. Port and token are the server's own preferences, under *Preferences → General → MCP
Server*, should either need changing.

It also answers with no display at all — `./auspex-headless.sh dump.hprof.gz`, below.

**Update site: `https://kgyrtkirk.github.io/auspex-mortis/`** — published from `main` by
GitHub Actions, so it always carries the last build that passed its tests. It is a composite:
adding that one address also gives the IDE
[Memory Analyzer](https://download.eclipse.org/mat/1.17.0/update-site/) 1.17, the
[Eclipse MCP server](https://vogellacompany.github.io/eclipse-mcp-server/) and the
[Calcite plug-in](https://vlsi.github.io/mat-calcite-plugin-update-site/stable/), so a missing
prerequisite is no longer a second trip. Calcite is required, like the other two: with it,
`calcite "…"` queries answer and open in its SQL editor pane.

Installed this way the MCP server keeps **its own default, which is off**: a plug-in that
starts listening on a socket in an IDE somebody else set up is a surprise, so turn it on under
*Preferences → General → MCP Server*. Only the product, whose whole purpose is the endpoint,
defaults it on.

By hand: *Help → Install New Software… → Add…* → the URL above → **Auspex Mortis**. Restart.

From an agent, over the MCP server:

```
eclipse_add_repository  url: https://kgyrtkirk.github.io/auspex-mortis/, dryRun: false
eclipse_install         unit: hu.rxd.auspex.mortis.feature.feature.group, wait: true
eclipse_restart
```

A local build installs the same way, from
`file:/…/auspex-mortis/update-site/hu.rxd.auspex.mortis.repository/target/repository`. Commit
first: the qualifier comes from the commit, so rebuilding the same commit produces the version
p2 already has and it will decline to install it again.

**The restart is not optional.** `McpToolRegistry` reads the extension point once and caches
it for the life of the IDE — nothing calls its `reset()` — so a newly installed tool is
invisible until the IDE comes back up. `eclipse_install_bundle` is worse than useless here:
a hot-installed bundle does not survive the restart that would publish it. The restart also
closes any open heap dump, because MAT's editor input cannot be persisted: reopen it by hand.

The MCP client picks the new tools up when it reconnects. Its copy of a tool's schema can lag
behind; that is harmless, because the server validates the arguments.

## 🤖 Headless

`auspex-headless.sh` sits at the root of the installation, beside the launcher it drives:

```bash
./auspex-headless.sh dump.hprof.gz [workspace]
{"state":"listening","url":"http://127.0.0.1:8642/mcp","token":"…","workspace":"/scratch/ws"}
{"dump":"…","indexes":"reused","state":"open","objects":356711957,"classes":32149,…}
```

It drives the product it sits in, so it is told no paths. `AUSPEX_PRODUCT` points it at
another installation, which is what a copy living outside one needs.

It starts the product, waits for the endpoint, and then **opens the dump through that
endpoint** — `mat_open`, not a launch argument, because Memory Analyzer's application reads no
file from the command line. `--launcher.openFile` is the IDE's feature and does nothing here:
the product comes up listening, with no editor, D-Bus present or not. The open call returns as
soon as the dump is open, or says `parsing` after `AUSPEX_OPEN_WAIT` and leaves the parse
running for the next `mat_open` to wait on. The workbench really runs, against `Xvfb`: panes
open, `calcite` answers, and the tools behave as they do on a desktop.

Needs `Xvfb`, `curl` and `jq` on the machine; it says which one is missing rather than
failing later.

### 🧮 Heap

The heap is the caller's to choose, and it is the one setting a large dump always needs:

```bash
AUSPEX_HEAP=40g auspex-headless.sh …            # the script's knob, default 8g
./auspex -vmargs -Xmx40g                        # by hand, once
-Xmx40g                                         # in auspex.ini, for every start
```

`auspex.ini` ships with `-Xmx1024m`, which is Memory Analyzer's own default and far too small
for a real dump. Nothing here promises what a given dump needs. Two measurements of one dump —
10.2 GB chunked gzip, ~51.7 GB of heap, 356.7 million objects, on a 24-core machine:

| | wall clock | heap the JVM actually held |
|---|---|---|
| first parse, writing ~25 GB of indexes | **9 min 34 s** | 40 GB, the ceiling it was given |
| every later open, reusing them | **0.5 s** | 2.4 GB |

The parse is what the ceiling is for; reuse never re-reads the dump. A sorted `dominator_tree`
over 13.3 million roots then answers in 138 ms, and `calcite` counting 43.9 million instances
of one class in 1.8 s.

The server needs no enabling — the product ships with it on. What the script does set is what
a run must not share with the IDE its user may be sitting in: its own port, and its own bearer
token beside the workspace through `-Dcom.vogella.eclipse.mcp.tokenDirectory` rather than the
one in `~/.eclipse`. The URL and that token are written to
`<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json`.

## 🧭 Three ways to use the product

* 🖥️ **As Memory Analyzer.** `./auspex`. It is MAT, with its panes and its query browser — and
  the endpoint is listening beside them, so an agent can join the session you are looking at
  and its answers arrive as panes you can carry on from.
* 🤖 **As an endpoint against one dump.** `./auspex-headless.sh dump.hprof.gz /scratch/ws` on a
  machine with no display. Point a client at what it prints, ask, and stop it when done —
  `eclipse_exit` over the endpoint, or kill the launcher. The indexes stay beside the dump.
* 🧱 **As an indexer, with no endpoint at all.** MAT's own headless application parses and
  exits, which is what a CI step wants when the point is to compute the indexes once:

  ```bash
  ./auspex -nosplash -consoleLog -data /scratch/ws \
      -application org.eclipse.mat.api.parse dump.hprof.gz
  ```

## 🔌 Pointing a client at it

`endpoint.json` in the workspace holds both values a client needs, so nothing is copied out of
a preferences page:

```json
{"state":"listening","url":"http://127.0.0.1:8642/mcp","token":"…","workspace":"/scratch/ws"}
```

The transport is Streamable HTTP with a bearer token:

```json
{"mcpServers": {"auspex": {
  "type": "http",
  "url": "http://127.0.0.1:8642/mcp",
  "headers": {"Authorization": "Bearer <token from endpoint.json>"}
}}}
```

Loopback only and token-guarded in both modes, because **a heap dump is customer data**. A
client arriving cold should call `auspex_help` first: it answers with the dumps that are open,
what each holds, whether Calcite is installed, and the order the other tools are usually asked
in.

## 🗄️ Keeping what the parse computed

A parse of a large dump costs tens of minutes and writes its indexes beside the dump. They are
ordinary files and they travel, which is worth doing when the same dump is analysed on another
machine or again next week. What to keep, from a real 51.7 GB heap:

| file | what it is |
|---|---|
| `<name>.index` | the master index, 52 MB — the one MAT looks for to decide whether it can reuse |
| `<name>.idx.index`, `.o2c.index`, `.a2s.index`, `.o2hprof.index` | object identity, class and address maps, ~7 GB |
| `<name>.inbound.index`, `.outbound.index` | the reference graph, ~9 GB |
| `<name>.domIn.index`, `.domOut.index`, `.o2ret.index` | the dominator tree and retained sizes, ~7 GB |
| `<name>.chunkedgzip.index` | the offset map of a chunked-gz dump — without it a compressed dump is read from the start again |
| `<name>.threads`, `<name>.i2sv2.index` | thread call stacks, and the string cache MAT fills as it goes |

```bash
tar -czf a3-indexes.tar.gz --exclude='*.log' --exclude='*temp*' a3.*index a3.threads
```

Four rules decide whether the archive is worth anything on the other side:

* ⚠️ **The `.hprof` goes with them, always.** MAT reopens the dump for every field read; the
  indexes shorten the parse and never replace it.
* 🏷️ **The dump keeps its name.** The index prefix is derived from the dump's filename, so a
  renamed or copied dump is a new dump and pays the parse again — measured: copying one to
  `a3.hprof.gz` re-parsed all 9 minutes 34 of it.
* 🕰️ **Modification times survive the trip.** MAT reuses an index only if the dump is no newer
  than it, so an unpacking that stamps everything with "now" can make the dump look newer than
  its indexes and trigger a silent re-parse. `tar` preserves them; check that whatever moves
  them does too.
* 🔒 **The same MAT version reads them.** Indexes written by another version are out of
  contract — which is the argument for the product: its MAT is pinned by the build.

Skip `*.temp.*`, the numbered `*.log` chunk files and `lock.index`: they belong to a parse in
progress. `_Leak_Suspects.zip` and friends are reports, not indexes — keep them if you want
the report, not to avoid a parse.

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
  Maven cache carrying Tycho's p2 cache, surefire output kept when a run fails, the composed
  update site packaged for Pages and the product archive kept as an artifact, so its size is
  visible on every run. Pull requests run it directly.
* [`pages.yml`](.github/workflows/pages.yml) — on every push to `main`: calls `build.yml`, then
  deploys that very artifact to GitHub Pages. What is published is what was tested; a
  deployment is never cancelled half way.

**One-time setup** in the repository: *Settings → Pages → Build and deployment → Source:
GitHub Actions*. Until then the deploy job fails; the build still runs.

The site is a composite: `compositeContent.xml` at the root, this build's own repository under
`auspex/`, and the MAT, MCP server and Calcite sites as the other children. Each deploy
replaces the previous build — pinning older versions would need one child per release, which
is not worth it until somebody asks to stay on an old one.

## 🕳️ Known gaps

* **The Calcite pane is held by reflection** — see above. It degrades to the plain pane and
  says so in the log, but it is the one place a Calcite plug-in upgrade can break this.
* **Nothing verifies the panes automatically.** Whether a pane opened, and what it shows, is
  checked by a person looking at the IDE; the tests cover the rows and the contracts.
* **Nothing that needs a snapshot is tested** — `GraphExtract`'s walk and writer, and both
  object and query tools end to end. The whole chain has been exercised by hand against a
  51.7 GB dump, parse and reuse alike, but no test does it: that wants the build to make its
  own dump, which a JVM can do to itself in a second, and nobody has written it yet.
* **Tier 2 and 3 are not here**: reading and writing widget text, invoking JFace actions,
  cancelling jobs, bundle reload, opening a file outside the workspace. Those touch the UI
  and belong in a separate bundle, so that a MAT upgrade cannot break them.
