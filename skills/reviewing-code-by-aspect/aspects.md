# Aspect catalog

The menu shown at selection time. **Core** aspects are pre-selected; the rest
are opt-in. Tiers are critical (C) / major (M) / minor (m); the cross-cutting
principles in SKILL.md (blast radius, visibility, journey-not-call-site,
intentionality, language boundary) apply throughout. A tier a ladder omits
does not exist for that aspect — the floor that creates is deliberate.

Abbreviations (finding-ID prefixes) in parentheses.

## Mechanical sweeps — a deterministic recall floor

Defect classes with a searchable signature are swept by **literal
searches, not by reading**: each sweep below is a pattern the reviewer runs
verbatim over the declared scope, records in the ledger's Declared
assumptions (pattern · scope · hit count), and adjudicates hit by hit. Two
passes over the same code then see the same hit list — the one slice of
recall that does not depend on attention. A described sweep that each
reviewer approximates from memory is not a sweep; it samples the hits like
any other reading. A hit is a lead, not a finding — it still needs its
aspect's evidence requirements — and the recorded sweep is what backs any
"verified clean" claim (SKILL.md, negative claims). Patterns are sketches
to adapt per language; what must not vary is running them and recording
the hits.

- **Raw identifier splices → SEC.** Table/column/collection names built
  from values: interpolation or concatenation adjacent to
  `FROM|INTO|UPDATE|TABLE|JOIN` (splice operators such as `#$`, f-strings,
  `+`, template literals around SQL keywords). Adjudicate by source:
  config- or catalog-sourced is suppressed; anything from a request,
  message, or file is a lead.
- **Failure collapsed to a benign value → ERR.** Catch-alls returning an
  empty or absent-state default: `catchAll(_ => …none|Nil|unit)`,
  `orElseSucceed`, `except …: return ([]|None|{}|False)`,
  `catch … { return null|[] }`. Lead when the caller cannot tell "nothing
  there" from "could not tell" on a path that mutates or protects state.
- **Init-only schema delivery → CMP.** DDL mounted or invoked only by
  first-boot hooks (`docker-entrypoint-initdb.d`, `init.sql`, image-build
  steps) with no migration runner or apply step in the deploy path.
- **Ungated tests touching real systems → TSQ.** Test files importing a
  real connection or client (DB driver, HTTP client, SDK) with no guard
  (skip marker, env-var gate, container fixture) in the file or its shared
  fixtures. Enumerate every such file, never stop at the first.
- **Silently-defaulting selectors → CMP / TYP.** Optional fields that pick
  an identity, account, tenant, route, or credential — `Option = None`,
  `= default`, `getOrElse(default)`, `.get(k, default)`, `?? default` — on
  a selector every caller is expected to set. Lead when at least one caller
  omits it.
- **Secrets adjacent to logging → LGG.** Log/print calls whose arguments
  include a URL, an exception message, or a request object on a path that
  carries a credential (webhook URLs, signed requests, connection strings).
  The leak is usually the exception's own rendered message — trace what
  the logged value renders as, not only what it is named.

- **Discarded results of external mutations → ERR / LOG.** A call that
  changes state outside the process (place, cancel, transfer, publish,
  write) whose response is dropped — bound to `_`, `.unit`/`.void`,
  `await`ed unassigned, or a status checked with the body ignored. Lead
  when the response carries something the ledger or the caller needs
  (filled quantity, affected rows, a partial-success status).
- **Numeric parse collapsed to zero → ERR / LOG.** `toXOption.getOrElse(0)`,
  `Try(…).getOrElse(0)`, `parseFloat(x) || 0`, `int(x or 0)`,
  `Number(x) ?? 0` on a quantity, price, fee, or balance. Lead when the
  zero flows into a write or a guard rather than a rejection.
- **Retry budget against the caller's deadline → ERR / CON.** Every retry
  or backoff schedule and every client timeout in scope, each with its
  worst-case duration. Lead when a callee's worst case exceeds the timeout
  of a caller that mutates state, so the caller gives up on work still
  running.
- **Caller-supplied clock or identity → SEC.** Request fields that name a
  time, account, tenant, user, or role and reach persistence, pricing, or
  an authorization decision without being bound to the authenticated
  principal or the server clock. Lead per field; the reach answer decides
  the tier under the declared trust model.

Classes earned from real misses join this list with their home aspect.

## Core

### 1. Logic correctness (LOG)

Code versus its evident intent — intent taken from names, types, tests, docs;
the finding states the intent evidence it judged against. **Evidence
required: a concrete failing input → wrong outcome.** "This looks wrong"
with no input that breaks it is not a finding; logic is where hallucinated
findings concentrate, and this requirement is the filter.

- C: silently wrong results or data corruption on a reachable path.
- M: visible wrong behavior, request-scoped (off-by-one, inverted condition).
- m: provably dead branches, always-true conditions.

### 2. Error handling (ERR)

Every plausible error path shows evidence of a decision — propagate,
translate, retry, degrade-with-signal, or deliberately ignore with a marker.
What fails review is the path nobody decided about. Includes retry and
idempotency: retry is an error-handling decision, and idempotency is what
makes it safe.

- C: silent failure with external or process residue — swallowed errors,
  catch-all returning success, fire-and-forget async, default values on
  failure with no signal, errors swallowed mid-mutation leaving partial
  state (a silent failure whose residue is one wrong response is M); unhandled errors with
  process blast radius (one bad input kills the service or wedges a
  consumer); **retry of a non-idempotent operation** (the double-charge
  shape).
- M: loud-but-wrong, request-scoped — realistic failure paths that crash the
  request uncontrolled, overly broad catches, cause/context destroyed on
  rethrow, wrong semantics at boundaries, retry without backoff or limit.
- m: inconsistent error types, double logging, context-free messages.

Suppress: try/catch-everywhere demands (propagation is handling when a
deliberate boundary handler exists); paranoia about errors the types or
contract rule out; flagging documented intentional swallows.

### 3. Security (SEC)

Trust-boundary scoped: trace where untrusted data enters and where
privileged actions happen; review what stands between. In scope: injection
(SQL/command/path/XSS/template), authorization, client-supplied authority
fields (role, price, user id), secrets committed to the repo, unsafe
deserialization of external data, sensitive exposure (internals to clients;
PII in logs — recorded in logging, cross-referenced here), crypto misuse on
sensitive data. Out of scope: infra security, DoS resilience, dependency
CVEs (tool territory; model CVE memory is stale by construction).

**Authn is model-dependent.** Detect and declare the auth model (in-code
middleware vs perimeter/sidecar — evidence: trusted forwarded-identity
headers, mesh manifests). Under a declared perimeter model, absent in-code
authn is not a finding and trusting forwarded identity is correct. **Object-
level authorization stays in scope under every model** — a sidecar cannot
know which objects a caller owns; IDOR and client-authority findings survive.

- C: exploitable path reachable from untrusted input. **Evidence required:
  the finding names untrusted source → target** ("request param `id` → SQL
  string at x.go:42"). No named path, no critical.
- M: weakened defense without a demonstrated path — internals in error
  responses, predictable low-stakes tokens, permissive CORS with
  credentials, single-layer validation where blast radius warrants depth.
- m: hygiene — commented-out secrets, verbose headers, test credentials
  outside prod paths.

Suppress: interpolation of server-controlled values near SQL; auth demands
on genuinely public endpoints; re-validation demands past a deliberate
boundary; crypto findings on non-security uses (MD5 cache keys).

### 4. Code pattern (PAT)

Conformance to the codebase's own established pattern. Detection is a
declared, falsifiable step (done by the scout): standard precedence is
repo-declared conventions (in-repo instruction files, contributing docs,
linter/formatter configs, or convention documents the repo itself
references) > dominant codebase pattern > language-community idiom.
Conventions that live only in the reviewing agent's environment (installed
skills, global instruction files) are operator configuration, not repo
evidence — they never bind by themselves; declare them in the assumptions
as an available candidate and apply them only through a user override. A
mixed codebase gets the most defensible standard selected and declared,
never a question back to the user. Per language.
Paradigm constructs (polymorphism, functors, monads) are judged against the
house paradigm.

- C: violation breaks a guarantee the pattern exists to provide — business
  logic bypassing the layer where transactions/authorization live, side
  effects smuggled into a pure effect core, direct DB access skipping
  tenancy filtering.
- M: layer or paradigm violation in a load-bearing place without a broken
  guarantee — controller calling repository directly, shared mutable state
  in an FP codebase, a god-service where the house keeps services thin.
- m: local idiom deviation — non-idiomatic construct, file in the wrong
  package, naming that fights house structure.

Suppress: paradigm policing — the standard is what this codebase dominantly
does, never textbook purity. The house hybrid is the baseline, not a finding.

### 5. Concurrency safety (CON)

Correctness under concurrency; throughput effects of blocking belong to CPU.

- C: data-corrupting race on shared state; deadlock that wedges the process.
  Signature: runs fine in tests, corrupts under load — the worst kind of
  runs-but-dangerous.
- M: race with request-scoped consequence; missing synchronization on shared
  mutable state; non-thread-safe collections shared across threads.
- m: risky-but-currently-benign patterns (unsafe lazy init that happens not
  to matter yet).

### 6. Contract & compatibility (CMP)

The wire is where passing tests still break other people's systems. The
scout declares which surfaces are *consumed* (public API, events with
external consumers — with evidence); internal-only surfaces may change
freely. **Judgment is per published version: adding versions is always
legal; only in-place mutation of a consumed contract deducts.** v2-in-name-
only (v1 removed or altered in the same change) still breaks; v2's existence
does not license editing v1; intentional sunset with deprecation evidence is
a note, not a deduction. Covers API breaking changes, schema-migration
safety including the rolling-deploy window (old code running against new
schema), and event/message schema evolution.

Answer one question explicitly every pass: **by what mechanism does a
schema or contract change reach an already-running environment?** DDL that
only a container's first-boot init executes is a migration path that does
not exist for a live database — additive, idempotent SQL that nothing
re-applies is still a C when live data depends on the change landing; the
safety of the SQL is not the safety of its delivery.

- C: change that breaks existing consumers or data on deploy — removed/
  retyped field on a consumed surface, destructive migration without a
  backfill window, event change that strands in-flight messages; no working
  mechanism to deliver schema changes to a live environment.
- M: compatible-but-hazardous — semantic change under the same name, no
  versioning strategy where one is evidently needed.
- m: contract drift — inconsistent error shapes, undocumented additions.

## Opt-in

### 7. Logging (LGG)

Diagnosability: can an operator follow a failing request through the system.
Boundary with error handling: a *missing* signal on an error path is ERR
(silent failure); a *bad* signal — wrong level, no context — is LGG.

- C: secrets, credentials, tokens, or PII written to logs (permanent once
  aggregated).
- M: errors logged without actionable context (no identifiers, no cause);
  systematically wrong levels (real errors at info, noise at error — which
  trains people to ignore error); key state transitions unlogged.
- m: message-style drift, leftover debug spam, unstructured one-offs in a
  structured codebase.

Suppress: "add more logging" everywhere-instinct — entry/exit logging on
every function is noise, and noise is a major above.

### 8. Memory (MEM)

The lifetime question: does the process degrade over time. Recursion splits
by consequence: stack overflow here, exponential time in CPU. A collection
that grows without bound over time (per-user/tenant history, event or audit
logs) is **plausibly large by default** — the burden of evidence is on
smallness, not largeness. Unpaginated reads of such collections on request
paths are major findings here whenever CPU is not selected to host them
(cross-ref CPU's missing-pagination rule); "no scale evidence" never excuses
data whose scale is time itself.

- C: unbounded accumulation reachable in production — evictionless caches
  keyed by unbounded input, listeners registered and never released,
  connections/handles leaked on error paths (exhaustion = process down);
  unbounded recursion on externally controlled depth.
- M: whole-table/whole-file loads where streaming fits and size is plausibly
  large; leaks on rare paths; unlimited recursion on trusted-but-growing
  data.
- m: retention sloppiness — references held longer than needed, oversized
  buffers.

Suppress: "leak" claims in GC languages where scope plainly ends;
structurally bounded recursion (AST of one file) needing no depth guard.

### 9. CPU (CPU)

Per-operation cost. **Evidence required — no scale, no finding: every
finding names the input, its realistic scale, and the path's heat** (request
path vs startup vs admin). "This is O(n²)" on a 10-element config list is
not a finding. Allocation churn (GC pressure in hot loops) lives here — its
consequence is throughput.

- C: untrusted input drives unbounded compute (algorithmic-DoS shape,
  catastrophic regex backtracking on user input — cross-ref SEC); blocking
  calls on an event-loop/async-runtime thread (one slow call stalls every
  request sharing the thread: process-scale blast radius).
- M: N+1 queries; missing pagination pulling unbounded sets; invariant work
  recomputed per loop iteration; sync IO in hot loops; super-linear
  algorithms over plausibly large internal data on hot paths.
- m: real-but-small costs (repeated regex compilation, string concat in
  loops) — promotable to M only with a demonstrated hot path.

Suppress: optimization demands on cold paths; rewrites trading clarity for
unmeasured gains.

### 10. State discipline (STA)

Code versus the declared deployment model (scout declares it: manifests,
replica counts, mesh config). The question: does in-process state break the
evidently intended scaling model.

- C: state that breaks intended horizontal scaling — in-memory sessions,
  local-disk persistence, anything correct only with one replica.
- M: replica-hazardous state with limited blast — in-process cache assumed
  coherent across replicas.
- m: incidental statefulness easily externalized.

### 11. Testable code (TST)

Seams exist where logic lives: business rules exercisable without standing
up the world. No critical tier (floor 4): untestability is indirect risk
only.

- M: a module's core logic is untestable — rules fused to IO, hidden inputs
  (`now()`, random, env, globals) inside decision logic, hard-wired
  collaborators, constructors that do work.
- m: local seam gaps — one hard-wired dependency, an easily wrapped static
  call.

Suppress (test-induced design damage): DI-and-interfaces demands on mains,
thin adapters, glue; an interface with one implementation created only so a
mock can exist is a smell, not a fix; the repository layer is integration-
tested by design — SQL needing a DB is its nature, not a finding.

### 12. Test quality (TSQ)

Risky paths have tests that would fail if the behavior broke. Risk-first,
never coverage-first: the reviewer identifies risky paths itself (money,
auth decisions, state transitions, parsing external input, concurrency) and
checks those, error paths included. Coverage tooling is out of scope. One
finding per untested *behavior*, not per function.

- C: tests that touch production systems or cause real side effects when
  run — the one test smell where running the suite is itself dangerous.
  Rare and narrow by design.
- M: a core risky path (state-mutating, money, auth, external-input parsing)
  untested — **evidence required: name a concrete behavior change the
  missing test would catch that passes today, and where a test command
  exists, apply that mutation — the one the finding or candidate names,
  not a substitute — in a scratch copy and run the suite; the major
  stands only on a green run**; a named-but-unrun mutation is a
  candidate, and so is one downgraded to minor without a run, because
  reading a suite predicts its verdict worse than executing it. Restore
  the copy afterward; the review never leaves a mutation in the tree.
  "Untested" with no such input is a minor; assertion-free or tautological tests over
  important logic (suites that lie); pervasive flakiness patterns (sleeps,
  real clock, order dependence, shared mutable state); suite or large parts
  disabled.
- m: happy-path bias in low-risk modules, mock-interaction change-detector
  tests, leftover skips.

Suppress: coverage worship (no findings for untested getters/glue/config);
testing-pyramid sermons — the house testing style is the standard, and a
deliberately integration-heavy repo is not a finding.

### 13. Type & null safety (TYP)

The type system as a correctness tool, judged through the language's own
idiom (Option/Either vs nullable vs zero values vs None).

- C: unguarded null deref or unsafe cast with process blast radius, or
  producing silent corruption.
- M: request-scoped unguarded derefs on nullable values; unsafe casts;
  authority-bearing data passed stringly-typed.
- m: `any`/`Object` in low-stakes code, hygiene.

### 14. Readability & maintainability (RDB)

Can the next person change this safely. No critical tier (floor 4). Absorbs
unused code — with a confidence rule: "no caller" claims only for
confidently internal code; public API, reflection, and DI-framework targets
are false-positive territory, and the finding states its confidence.

- M: god functions, extreme nesting, misleading structure or names in core
  logic.
- m: magic values, dead/unused code, needless cleverness, unclear naming.

### 15. Format consistency (FMT)

Surface and lexical only — the human-visible layer formatters don't own:
mixed naming conventions (camelCase and snake_case fighting in one module),
member-organization drift, comment-style drift. Minor tier only (band 9):
this aspect is about cleanliness, and its ceiling encodes that. Boundary
with PAT: structure there, surface here. The widespread label matters most
here — cross-module convention mixing reads as "no convention exists";
formatting is also where occurrence-slicing would explode, so the
one-issue-one-finding rule is strict. Where a formatter or linter is
enforced, FMT has nothing to find and scores 10 — the only durable path off
9; hand-enumerated drift is otherwise a lead to recommend the tool, not a
list to keep growing.

- m: any of the above, isolated or widespread.

### 16. Requirements alignment (REQ)

The code versus what was *asked* — a plan, ticket, spec, PRD, or the
request text itself — where LOG judges code against its own evident intent.
Selected automatically in quick mode when such a source exists; opt-in
otherwise, and only when a source is supplied or discoverable in the repo
(`docs/`, an issue link in the commit, a plan file). `docs/domain-context.md`
is *not* a source — it records what the system does, not what it should —
though its Invariants section supplies failing inputs to LOG. With no
source the aspect has no standard and is not selected. **Evidence required: quote the
requirement and name the code path that contradicts, omits, or exceeds it.**
"Feels incomplete" is not a finding.

- C: a stated requirement silently implemented wrong or not at all on a
  reachable path, with no marker (TODO, deferred note, release note) that
  the gap is known.
- M: a requirement met partially or with a visible deviation not called out
  anywhere; an *unrequested* behavior with user-visible or data-shape
  consequences (scope creep that changes a contract).
- m: a deviation the author documented but the source was not updated to
  match; harmless extras.

Suppress: deviations the plan itself marks optional or "if time permits";
judging the requirement's own quality (a bad requirement implemented
faithfully is a note for the user, not a finding); re-litigating a decision
the ledger's User overrides or Credited entries already settle; anything
the LOG, SEC or CMP ladders already own — REQ cross-references, never
double-files.

## Custom aspects

A user-named aspect gets, at selection time: a one-line standard (the
question it answers), a C/M/m ladder in the same consequence-not-runnability
terms (omitting tiers that don't apply, stating the floor), any evidence
requirement, and its suppression list. Record the rubric in the ledger;
future reviews reuse it unchanged unless the user edits it.
