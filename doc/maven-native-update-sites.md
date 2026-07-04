# Maven-Native Update Sites: Design and Implementation Plan

| | |
|---|---|
| **Status** | DRAFT — core decisions settled (see §8), details for discussion |
| **Date** | 2026-07-04 |
| **Scope** | imagej-updater, list-of-update-sites, new publisher tooling (jgo-based), fiji/fiji release process |

## 1. Summary

Teach the ImageJ Updater to understand Maven coordinates as first-class
citizens, so that update sites compose with Maven semantics rather than
filename shadowing. The design splits Maven dependency resolution at a
principled line:

- **Model building** (POM inheritance, property interpolation, profile
  activation, dependencyManagement effects) runs **once, at publish time**,
  via [jgo](https://github.com/apposed/jgo)-based tooling, producing
  per-component *direct dependency* facts embedded in each site's index.
- **Graph mediation** (version selection over the composed installation)
  runs **on the client**, using minimum version selection (§4.1), so that
  enabling multiple update sites composes deterministically and
  order-independently.

Slogan: **ship the facts, compute the choice.**

This supersedes the [db-xml-maven](https://github.com/ctrueden/db-xml-maven)
prototype. Its `maven.py` half already graduated into jgo v2; its
generation design (one pre-resolved update site per project version) is
replaced by this document, because pre-resolved closures do not compose:
cross-site conflicts degrade to filename shadowing, and pruned subgraphs
(dependencies dropped between versions) cannot be recovered from flattened
lists.

## 2. Design principles

1. **The installation is a synthetic root project.** Every component the
   user has selected, across all enabled sites, is a direct (depth-1)
   dependency of that root. The core site's BOM plays the role of the
   root's `<dependencyManagement>`. This is the same operation jgo's
   `PythonResolver.resolve(list)` performs and the same composition
   `pombast melt` validates.
2. **Publish edges, not closures.** Each published `<release>` lists only
   that component's direct dependencies (post model-building). The client
   reconstructs the composed graph by walking edges, so mediation prunes
   subgraphs correctly (a dependency dropped in v2 is never reached when
   v2 is selected).
3. **The index is a materialized view, not a source of truth.** Every
   entry carries its G:A:V; anyone can re-derive and verify the facts from
   the POM. Facts are regenerable: a flattener bug is fixed by rerunning
   the generator, not by shipping client code. Consequently, facts must
   live only in regenerable locations (site indexes) — never in immutable
   release repositories.
4. **Coexistence, not replacement.** Filename-keyed `<plugin>` entries
   keep today's semantics untouched, in the same index. Non-Maven content
   (scripts, macros, LUTs, launchers, hand-uploaded historical JARs) and
   legacy third-party sites continue to work unchanged.
5. **jgo is the normative reference for published facts.** Each
   `<component>` block must match Maven's model-building behavior exactly,
   as jgo strives to do — not "whatever mvn does this year." Cross-site
   *mediation*, by contrast, is deliberately not Maven's (§4.1) and is
   defined normatively by this document with its own reference vectors.

## 3. Index format (draft)

New elements live alongside existing `db.xml.gz` content. Old clients
ignore unknown *elements*; new-style data must never appear as new
attributes on old elements (`XMLFileReader` would pass a null `filename`
to `FileObject.addDependency`).

```xml
<component coordinate="sc.fiji:TrackMate" offered="true" current="7.12.0">
  <release version="7.12.0" sha1="..." filesize="..." timestamp="..." min-java="8">
    <description>...</description>
    <dependency coordinate="net.imglib2:imglib2:6.2.0"/>
    <dependency coordinate="org.jogamp.gluegen:gluegen-rt:2.4.0"
                classifier="natives-linux-amd64" platform="linux64"/>
    <dependency coordinate="com.google.guava:guava:32.1.2-jre">
      <exclude>com.google.code.findbugs:jsr305</exclude>
    </dependency>
  </release>
  <release version="7.11.0" sha1="..." min-java="8">...</release>
  <!-- slim entry: recognized but not installable (POM unresolvable) -->
  <release version="2.1.0" sha1="..." slim="true"/>
</component>

<managed bom="org.scijava:pom-scijava:45.0.0">
  <version coordinate="net.imglib2:imglib2:6.2.0"/>
  <version coordinate="com.google.guava:guava:32.1.2-jre">
    <exclude>com.google.code.findbugs:jsr305</exclude>
  </version>
</managed>
```

Rules and semantics:

- **Identity** is G:A(:C:P); version is a first-class variable, not a
  filename substring. Filenames are derived (`jars/artifactId-version.jar`
  conventions preserved).
- **Offered vs referenced.** `offered="true"` marks components a user can
  enable (site roots). All other `<component>` entries are catalog facts
  needed to make the graph walkable. Presence in the index is *not* an
  installation instruction; the installed set is always the output of
  mediation.
- **Closure obligation.** A site publishes edge lists for the transitive
  closure of everything it offers, at every full-entry version it
  mentions. This makes the composed union closed: any version mediation
  can select arrived via some site's edge, and that site published the
  target's own edges.
- **Concrete versions only.** Version ranges are forbidden in published
  edges (enforced at publish time). Ranges would break the closure
  property.
- **Exclusions** are per-edge facts (post-interpolation), G:A only,
  wildcards allowed (`g:*`, `*:*`). They cannot be resolved away at
  publish time because their effect is path-dependent and depends on
  compose-time mediation. A `<managed>` entry's exclusions apply to the
  managed component's *outgoing edges wherever it appears* (Maven
  depMgmt-exclusion semantics — see the `bom-exclusion` golden vector);
  only user-level root exclusions ("never install X") are global.
  Intermediate nodes' depMgmt is already baked into their own published
  edges.
- **Platform-specific dependencies** are pre-expanded at publish time by
  resolving under each supported platform's `ProfileConstraints` and
  diffing, emitted with `platform` attributes using the Updater's short
  platform names.
- **Scopes** are pre-filtered at publish time (compile/runtime only).
- **Checksums**: SHA1 (from repo `.sha1` metadata) for both download
  verification and local-modification detection of Maven-born files. The
  legacy content-aware JAR digest remains only for filename-keyed legacy
  entries. Rationale: Maven release artifacts are immutable; "rebuilt
  locally but equivalent" should show as modified.
- **Provenance stamps**: each index records `generator` name+version and a
  format version, so buggy generator outputs are identifiable and bulk
  regeneration is auditable.

## 4. Client: composition and mediation

### 4.1 Mediation algorithm

**DECIDED (2026-07-03): Minimum Version Selection (MVS).** Per-component
facts match Maven's behavior exactly (jgo at publish time); cross-site
composition uses MVS.

Note that in this format MVS and Gradle-style highest-wins coincide:
published edges carry only concrete versions (ranges are banned by the
closure rules, §3), each treated as a minimum requirement, so
"maximum of the minima" and "highest requested" compute the same
fixpoint. The distinction would matter only if ranges or upper bounds
were ever admitted — one more reason they stay banned.

Rationale for MVS over Maven's nearest-wins:

- **Order-independent and deterministic** regardless of graph shape — no
  depth tracking, no declaration-order tie-breaks, no dependence on which
  site was enabled first. More robust for user-composed installations.
- **Simpler client code**: the mediation walk shrinks to a monotone
  fixpoint (select highest requested per G:A:C:P; traverse winners'
  edges; repeat until stable — terminates because selections only
  increase over a finite version set).
- Each component's *own* dependency facts are still exactly Maven's; only
  the cross-extension composition rule differs, and Fiji's runtime (flat
  classpath, one version per library) never matched Maven's build-time
  nearest-wins anyway.

**Exclusion interplay** (ours to define, since Go's MVS has no such
concept): the fixpoint walk accumulates per-path exclusion sets exactly
as in §4.2; a version is a candidate only if requested via a surviving
(non-excluded) path. Exclusion filtering interleaves with selection in
each fixpoint iteration.

**Precedence: user pin > core BOM (`<managed>`) > MVS.** An exclusion
prunes transitive reachability but never removes an explicitly selected
root. Whenever a pin or BOM entry holds a library *below* a version some
surviving edge requests, the client shows a visible warning, not silent
success.

**SemVer misalignment warning**: when the selected version of a G:A has a
different major version than some surviving edge's requested version
(e.g. an extension requires `imglib2:5.x` but `6.2.0` is selected), the
client surfaces a compatibility warning naming the requesting
extension(s) and the versions involved. This is a heuristic (not every
project follows SemVer) but the pom-scijava ecosystem largely does; the
warning is informational and never blocks.

### 4.2 Algorithm sketch

1. Fetch enabled sites' indexes (one HTTP request per site, as today).
2. Roots = user-selected offered components (with any pins) + root
   exclusions (user "never install X" preferences).
3. Walk the union graph from the roots. Each path accumulates the union
   of exclusion sets along its edges; matching nodes are pruned on that
   path. Exclusion filtering interleaves with mediation (an excluded node
   is not a version candidate via that path).
4. Mediate versions per G:A:C:P; traverse only the winner's edge list.
5. Filter release candidates by `min-java` against the running JVM.
6. Diff the mediated selection against local state (filename+SHA1
   recognition; slim entries let ancient installs be identified rather
   than reported as unknown files).
7. Download Maven-born files directly from Maven repositories
   (scijava.public proxies Central), SHA1-verified. sites.imagej.net
   hosts only non-Maven files and indexes.

Implementation — **DECIDED (2026-07-03): Java, in imagej-updater.** The
MVS fixpoint plus Maven's `ComparableVersion` ordering is a few hundred
lines, validated by golden vectors (§7). Full model building stays out of
the client permanently. Fiji is not ready to bundle a Python runtime; if
that changes, delegating to embedded jgo can be revisited, but the Java
implementation proceeds regardless.

### 4.3 Mixed-regime collisions

A legacy filename entry (e.g. `jars/imglib2-5.0.jar` from an old-style
site) can collide with a mediated coordinate selection. Detection uses
the existing version-stripping logic (`FileObject.getFilename(true)`).
Rule: **coordinate entries win**; the file entry surfaces as
"overridden" in the UI rather than silently shadowing. This is the
riskiest area of the design and gets dedicated tests (§7).

### 4.4 Manifest and lockfile

Local state becomes two artifacts, mirroring jgo's `jgo.toml` + lockfile:

- **Installation manifest**: the roots — (site, coordinate, optional
  pin) — plus root exclusions. Small, human-readable, composable;
  sharing it reproduces *intent*.
- **Exportable lockfile**: the full mediated selection with versions and
  SHA1s, for byte-exact reconstruction of an installation (methods
  sections, containers).

### 4.5 Downgrades

A downgrade is a pinned root edge: the synthetic root depends on
`G:A:7.10.0` instead of the site's `current`. Mediation re-runs and the
subtree re-resolves consistently (pruning included). Marginal cost: a
version dropdown (populated from the back catalog, filtered by
`min-java`), pin persistence in the manifest, and the precedence warning
above. Designed in from day one (schema + manifest must support it);
shipped as a fast-follow after core install/update/compose is stable.

## 5. Publisher tooling

A jgo-based **flattener** (the surviving kernel of db-xml-maven,
heavily recast): given a seed G:A (or G:A:V), resolve the closure per
platform, emit `<component>`/`<managed>` blocks, and **merge** them into
the site index — generator owns coordinate blocks; GUI-uploaded
filename entries are round-tripped untouched (the core Fiji site will
always have both).

Packaged two ways from the same code:

1. **CLI + reusable GitHub Action** — any developer can generate/refresh
   their own self-hosted site.
2. **list-of-update-sites integration** — `sites.yml` gains a site-level
   `source:` key:

   ```yaml
   - name: "TrackMate"
     id: "TrackMate"
     url: "https://sites.imagej.net/TrackMate/"
     source: "sc.fiji:TrackMate"      # optionally :7.12.0 to pin
     catalog-since: "3.0.0"           # optional back-catalog floor
     maintainers: [...]
   ```

   Registering a Maven-seeded site = one PR adding `source:`. The
   workflow regenerates indexes it hosts (sites.imagej.net) on three
   triggers: sites.yml changes; a scheduled sweep comparing
   `maven-metadata.xml` `lastUpdated` against index stamps; and
   `repository_dispatch` so component release CI can request immediate
   regeneration.

**Back catalog**: full entries (edges + sha1) for every release version
of the seed and everything reachable from those versions — cheap,
because only POMs and `.sha1` files are fetched, never JARs. Generation
is incremental/append-only (facts from immutable POMs never change).
Unresolvable ancient versions degrade to slim entries via jgo lenient
mode; `catalog-since` floors genuinely worthless history. Historical
hand-uploaded binaries are imported once from existing core `db.xml.gz`
files as legacy entries.

**Validation gate**: before publishing, cross-check the flattener's
per-component direct deps against `mvn` output (pombast-style), and
verify provenance stamps.

**Uploads**: Maven-born content is published by doing a Maven release.
The GUI upload flow remains for non-Maven files.

## 6. Migration and sequencing

- **Phase 0 — de-risking spike + bootstrap safety.**
  - Spike the flattener: generate real indexes for `sc.fiji:TrackMate`
    and `sc.fiji:fiji`; eyeball size, closure correctness, platform
    expansion, back-catalog behavior on ugly old POMs.
  - Generate first golden mediation vectors from jgo.
  - Standalone updater / "update the updater" channel (long-planned):
    new client mechanics must reach existing installations through a
    bootstrap-safe path before any site publishes new-style entries.
- **Phase 1 — format freeze.** Freeze schema + manifest format (including
  pins); write the MVS reference implementation; publish the conformance
  test suite.
- **Phase 2 — client + core site.** Java mediation engine behind the new
  updater. **DECIDED (2026-07-03)**: the new core site is stood up as a
  parallel *test site* at its own URL, validated end-to-end without
  touching production, then swapped in once it slots in seamlessly.
  Its index carries three layers of content:
  1. `<component>`/`<managed>` blocks driven by `sc.fiji:fiji` — what new
     clients consume (transitional-to-permanent);
  2. legacy `<plugin>` blocks for the *current* Maven-born JARs — so
     pre-new-updater clients keep working; retired only once such
     clients are effectively extinct;
  3. legacy entries for non-Maven content (scripts, macros, LUTs,
     launchers) **and** historical version recognition (old checksums,
     hand-uploaded builds) — kept **indefinitely**, so long-unattended
     installations recognize their files as known old versions rather
     than reporting walls of "locally modified" files.
  Downloads move to Maven repos; sites.imagej.net can bridge old clients
  with `filename-timestamp` → Nexus redirects.
- **Phase 3 — ecosystem.** `source:` key + workflow triggers live in
  list-of-update-sites; reusable Action for self-hosted sites;
  third-party onboarding docs.
- **Phase 4 — reproducibility features.** Version dropdown (downgrades),
  lockfile export/import, min-java-aware upgrade guidance.

## 7. Testing strategy

- **Golden mediation vectors**: composed inputs → expected selection,
  consumed by imagej-updater's Java tests. Since mediation is MVS (not
  Maven's nearest-wins), jgo cannot be the oracle here; the vectors come
  from a small independent MVS reference implementation (~100 lines of
  Python, living in the generator repo) plus hand-constructed cases. MVS's
  simplicity is what makes an independent reference tractable. Must cover
  the nasty interleavings: exclusion flips the selection winner; node
  excluded on one path, reachable via another; wildcard exclusion of a
  BOM-managed component; pin or BOM entry below other extensions' needs
  (warning fires); SemVer-major misalignment (warning fires); pruned-
  subgraph case (the bee/stinger example: a dependency dropped between
  versions must not survive selection of the newer version); site
  enable-order permutations (results must be identical).
- **Flattener validation**: per-component direct deps diffed against
  `mvn dependency:list` across a corpus including pathological POMs
  (interpolated G/A fields, platform profiles, BOM imports).
- **Mixed-regime tests**: legacy site + coordinate site colliding on the
  same library; overridden-file UI state.
- **End-to-end**: fresh install, 3-year-old install recognition/upgrade,
  compose two third-party sites with conflicting transitive deps,
  downgrade + lockfile round-trip.

## 8. Decision log and open questions

Settled 2026-07-03:

1. **Mediation algorithm** (§4.1): MVS — equivalent to highest-wins
   given concrete-versions-only edges. Per-component facts remain exactly
   Maven's (via jgo). SemVer-major misalignment raises a user-visible
   warning.
2. **Core-site cutover** (§6 Phase 2): parallel test site at its own URL,
   validated, then swapped in. Legacy non-Maven and historical-recognition
   entries are kept indefinitely; legacy blocks for current Maven JARs are
   kept until pre-new-updater clients are extinct.
3. **Client mediation language** (§4.2): Java, in imagej-updater. Fiji is
   not ready to bundle Python; embedded jgo may be revisited later but
   does not block.

Still open:

4. **Transport split**: single `db.xml.gz` vs lean `db.xml.gz` + companion
   `graph.xml.gz`. Default: single file; revisit only if size becomes
   real.
5. **Multiple registries** (apt-source/tap/channel analogy for
   list-of-update-sites): explicitly out of scope; nothing here
   forecloses it.
6. **SemVer warning heuristics**: exact trigger conditions (major-only?
   0.x handling?) and UI presentation — decide during Phase 2 UI work.
   Empirical input from the Phase 0 spike: mediating the real
   `sc.fiji:TrackMate:8.1.6` closure fires major-misalignment for 27 of
   221 selected components, mostly annotation/logging libraries with
   habitual major churn (jsr305, guava, slf4j, asm) alongside a few
   meaningful ones (imglib2, imagej-common). The raw heuristic is too
   noisy for a modal warning; the UI likely needs severity tiers (e.g.
   warn prominently only for offered/rooted components or direct edges
   of roots) or an ecosystem allowlist.

## 9. Work breakdown by repository

| Repo | Work |
|---|---|
| **imagej-updater** | Schema reader/writer for new elements; mediation engine + `ComparableVersion`; manifest/lockfile; SHA1 local-state recognition; Maven-repo downloader; mixed-regime collision handling; UI (overridden state, version dropdown, warnings); golden-vector test harness. |
| **new: updater-site-generator** (name TBD) | jgo-based flattener: closure walk, per-platform expansion, slim-entry fallback, merge-not-overwrite emission, provenance stamps, validation gate; CLI + reusable GitHub Action. |
| **list-of-update-sites** | `source:`/`catalog-since:` schema; regeneration workflow (push, schedule, dispatch); hosted-site publishing. |
| **fiji/fiji** | Core site BOM publication (from the POM, pombast-adjacent); release CI dispatch hook; retire `populate-app.sh` + manual upload. |
| **jgo** | Oracle for flattener validation (model-building facts); no mediation role. |
| **new: MVS reference impl** | ~100-line Python MVS + exclusions reference emitting golden mediation vectors; lives in the generator repo. |
| **ctrueden/db-xml-maven** | Retire; point README here. |
