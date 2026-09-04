---
name: documenting-domain-context
description: >-
  Use when a repository's domain facts and operating envelope need to be
  written down for agents — before a scored code review, after a review
  ran on assumptions declared from code alone, when onboarding an agent to
  a service with no PRD, or when the recorded context may be stale. Covers
  the module map, document scoping, the owner Q&A, provenance, and the
  `docs/domain-context.md` format. Keywords: domain context, business
  context, module map, operating envelope, owner interview, update.
---

# Documenting domain context

Recover the facts about a system that its code cannot state — what its
parts are for and which carry the core path, what the external parties
actually do, what the units and fees are, what scale and trust model it
runs under, which hazards the owner has ruled on — and write them to
`docs/domain-context.md`. The document is an input to other skills (the
scored review's scout step reads it first); it is not a README, not
requirements, and not a narrative.

The skill is **evidence-led and draft-first**: the agent writes the file
from the repository, every line carrying `repo:` provenance as a base
assumption, and asks the owner only what the repository cannot answer —
the module split, what each document covers, runtime numbers, which of
two contradicting sources is true. A `repo:` line stands as the
assumption every consumer starts from whether or not the owner ever
reviews it.

## When to use — and when not

- Before a `reviewing-code-by-aspect` run the user intends to act on, or
  after one whose summary said context was declared from code alone.
- An agent is about to work on a service with no PRD, spec or ADRs.
- The context file exists but the review scout found a contradiction with
  the code, or it is older than several review rounds.

Not for: what the system *should* do (that is `writing-business-requirements`
— hand off the moment the owner starts describing intended behavior);
architecture narrative; or anything the repo already documents well within
a confirmed scope (cite it instead — see Documents and their scope).

## Output: `docs/domain-context.md`

Start from `template/domain-context.md` in this skill's folder and keep its
section names and order; consumers navigate by them. Every entry allows
`n/a` or `unknown` — an honest `unknown` is a fact (the reviewer knows the
severity was calibrated against a default); a guessed value is a lie.

**Module-first.** Global calibration facts come first, then one block per
module. A reviewer of one module reads its block plus the global sections,
and a module with no block is visibly missing rather than silently absent.

**A guideline, not a second copy of the code.** Every consumer reads the
file in full and then reads the code anyway, so a line earns its place
only if a reviewer could not get it from the enclosing function alone: an
outcome an external party can return, a provider quirk, a consequence that
crosses a module boundary, a difference between what is deployed and what
is at HEAD, a number the code does not fix. One function's own branching,
parameter values, sizing formulas, error-code lists and timeouts are not
domain facts: they go stale on the next commit and the reviewer reads them
at the source. **A module block is at most about forty lines**; the write
step enforces it.

1. **System in one paragraph** — what it does, for whom, and the sentence
   that lets an agent tell a core path from a side path.
2. **Module map** — one row per module (path, one-line purpose, role:
   core path · supporting), then the core path as a sequence of modules.
   Severity and finding placement depend on this row, so it is confirmed
   before any fact is asked. A module is a **business-domain code unit**
   — something that does part of the business's work: a service, a job,
   a UI, an API. Shared libraries and schemas are the **Shared** block,
   not rows. Deployment manifests, build files, local tooling and
   research scripts are not modules; they are evidence for the global
   sections. Whether a module runs in production is not a column — it
   goes stale and local setups differ; when it matters it is a dated
   fact under that module's *Not exercised*. When the split is unclear,
   the owner decides from the candidate list.
3. **Documents and their scope** — every document the mining relied on,
   the scope the owner confirmed it covers, and how fresh it is. A fact is
   cited to a document only within that scope.
4. **Operating envelope** — replicas, tenants, throughput, dataset sizes,
   latency budget, batch windows: the numbers MEM/CPU/CON severities are
   judged against; without them every cache is a critical or none is.
   Only numbers that decide whether a behavior is a defect *at all* at
   this scale belong here. Numbers that merely size the loss — balance
   under management, revenue, contract value — are never asked: a
   money-path bug is a defect at any balance, and the balance moves with
   decisions the owner has not made yet.
5. **Trust and deployment model** — where authn/authz happens, what the
   client is trusted to decide, what secrets exist and where they live,
   what platform actually runs the code (not what the manifests support).
6. **Invariants** — statements that must never be false, each naming the
   module that enforces it. Reviewers turn these into failing inputs.
7. **Module context** — per module, in map order, under the template's
   fixed headings. *Business context* is at most three lines: the rule
   the module enforces, the record it keeps, the decision it makes — not
   a feature list. *External parties* is where most silent-failure
   criticals come from: a reviewer that does not know an accepted payment
   can still be reversed cannot see the empty cell. *Hazards* holds **only
   consequences the owner has ruled on**: `accepted by owner (date)`
   with the reason, which a review credits, or `declined by owner
   (date)`, which a review files as a finding citing the line. A hazard
   observed in code that the owner has not ruled on is not written — it
   is a finding the review will surface anyway, and a file that lists
   every swallowed exception is a pre-review, not a guideline. Such
   observations go into the review list and, if the owner ignores them,
   are dropped. A party, schema or store used by more than one module is
   described once under **Shared**.
8. **Provenance and freshness** — see below.

## Provenance on every fact

Each fact carries one of: `owner (YYYY-MM-DD)` — stated or confirmed by
the owner on that date; `repo: <file:line or doc path>` — inferred from
the repository, not yet confirmed; `probe (YYYY-MM-DD): <what was run>` —
verified against the live external system; `unknown` — asked, not known.
Owners misremember and code drifts, so a reader must be able to tell which
claims to trust outright, which to re-verify on contradiction, and which
were never known. A fact with no provenance is not written.

A `repo:` line is a **base assumption**: unconfirmed, but written down so
every consumer starts from the same one instead of re-deriving it. The
owner may confirm or correct lines at any time; nothing waits on it.

A correction applies to the clause corrected, not to the sentence or the
document around it. When the owner corrects who bears a fee,
the documented rate and basis still stand within their confirmed scope.

The freshness section records the commit the mining ran against, the date
of the last owner confirmation, and whether the owner has reviewed the
`repo:` lines at all. A consumer that finds the code contradicting a fact
reports the contradiction rather than silently trusting either side.

## Documents and their scope

Existing documentation is evidence with a path: the file cites it and
restates only the fact, never the document — converting a document into
this format creates a second copy that drifts. **A fact documented within
a confirmed scope is cited, never asked — not even as a confirm item.**

Scope is not given by position or title. A file at the repository root
can describe one module; a design document can describe a configuration
that never ran; an undated page can predate the code it seems to
describe. So before a document is cited, its **scope and freshness are
candidates**: the agent states what it believes the document covers and
what dates it (a date line, a value that agrees or disagrees with config
or manifests), and the owner confirms or corrects in a word.

Some documents are never documentation: a prior review's ledger or
assumption list, a generated design or product page, a plan. Their claims
are candidates to confirm, never sources to cite — a review that cites its
own earlier assumptions is confirming itself.

When a document and the code disagree, the code claim is the candidate and
the document is cited as the other side; the owner is asked which is true,
once. That is the only way a documented fact reaches the interview.

## Flow

1. **Locate.** If `docs/domain-context.md` exists in this skill's format,
   run in update mode (step 7). If a file exists there in some other form,
   it is *input*, never overwritten — ask once for an output path.
2. **Map the modules.** From build files, directory layout, deployment
   manifests and entry points, draft the module map and the core path,
   every cell with `repo:` provenance. It is a candidate table until the
   owner corrects it in step 5.
3. **Inventory documents.** README, `docs/`, ADRs, specs, runbooks,
   in-code comments that name a decision, test names that pin a behavior.
   Draft each one's scope-and-freshness candidate; mark prior review
   ledgers and generated pages as candidate sources.
4. **Mine facts per module.** Look where each class of fact lives (table
   below) and sort every fact into two piles. **Stated** — the code or a
   scoped document answers it — goes straight into the file as a `repo:`
   line, subject to the density rule above. **Not answerable** — a
   runtime number the manifests do not fix, a unit or fee bearer the
   code leaves ambiguous, an outcome an external party can return that
   the code does not handle, which of two contradicting sources is true,
   whether an observed hazard is accepted — becomes an ask: a claim the
   code makes plausible, phrased for a one-word answer ("the refund fee is
   borne by the merchant — confirmed?", not "how do fees work?"). Mining is not done until each of these has a line or an ask
   in every module where it applies:
   - each operation that mutates state in an external system, and each
     request *type* it can send → what an accepted response can mean
     (settles later, in part, or all-or-nothing);
   - each failure caught and dropped, defaulted, or logged without effect
     → a hazard observation, with its consequence named, for the ask
     batch or the review list;
   - each number that bounds load (replicas, pool sizes, limits,
     schedules) → an envelope line or ask;
   - each quantity with a unit, fee, or rounding → a money line or ask;
   - each constant or code path nothing reaches → *Not exercised*;
   - each module's business context, in the three-line form.
   A generic question ("anything else you've accepted?") is never sent:
   it returns "not sure" and leaves the specific fact unasked. Before
   batching, list lines and asks by module and class and check each
   class is covered wherever the code makes it plausible — a replica
   count that never became an ask leaves a `repo:` guess where an owner
   fact belonged. A module with nothing at all means the mining missed
   it.
5. **Interview — two batches.** The **first batch** is the module map,
   the document inventory, and one question: is there domain or business
   documentation outside the repository (a wiki, a PRD, a tracker) to
   read first? Tables are corrected row by row; the three count as three
   asks and nothing else goes in that batch — every later fact hangs off
   a module, and a business document the agent never saw is re-derived
   badly from code. The **second batch** holds only the not-answerable
   asks, grouped by module, each with its evidence line and the answers
   available (confirm / correct / unknown / "not sure — go with the
   code"). **A batch holds at most eight asks, counting every ask
   however it is labelled** — numbered items, notes, follow-ups all
   count. Fill it in this order: first every runtime number the
   manifests do not fix (throughput, tenants, what actually runs, how
   many instances) and every unit or fee-bearer ambiguity, because they
   are one line each and no review can recover them; then whether each
   module runs in production where the manifests suggest it does; then
   external-party outcomes the code does not handle; hazard-acceptance
   asks last, with whatever room remains. Everything that does not fit
   goes into the review list presented with the file. There is no third
   batch. Number asks so the owner answers by number. An owner who asks
   "isn't that in the code?" has caught a stated fact in the ask pile.
6. **Write.** Fill the template; provenance on every line. Then run three
   checks before presenting. *Coverage*: every mapped module has a block,
   every cited document is in section 3 with a confirmed scope, every
   `unknown` is in section 8's open-unknowns list (a later pass asks from
   that list; an unknown missing from it is never asked again).
   *Density*: count the lines of each module block; for any block over
   about forty, remove the sub-bullets that restate one function's own
   branching, parameters or formulas — keep owner, probe, party-outcome,
   deployed-versus-HEAD and ruled-hazard lines — and repeat until it
   fits. *Rulings*: no hazard line without an owner ruling. Present the
   file with a **review list**: at most eight items, one line each, that
   a reviewer's severity would swing on most — unruled hazard
   observations on the core path first, then invariants and business
   context — which the owner may answer by number or ignore. Then stop.
   Do not offer to run a review, write requirements, or fix anything; the
   document is the deliverable. When the owner answers the list, apply
   the rulings and present the file again; ignored items are dropped.
7. **Update mode.** Scope the work by the commit recorded in section 8:
   `git diff <that hash>..HEAD --stat` names the files that changed, and
   only facts whose evidence lives in those files (or in a new file) are
   re-mined; every other line is carried unchanged. Ask only about what
   is new or contradicted — a new module, a new party, a hazard whose
   code is gone. Restate each amended fact as what is true now with fresh
   provenance; never append what it used to say. The file describes the
   system, not its own edits — git keeps those.

### Where facts live, by module type

| Module type | External parties | Envelope | Trust |
|---|---|---|---|
| Backend service | HTTP/gRPC clients, SDK wrappers, queue producers, retry/timeout config, error-code enums | deployment manifests, autoscaling config, connection-pool sizes, rate-limit constants | middleware stack, gateway/sidecar manifests, auth headers trusted, secret mounts |
| Frontend / client | API clients, third-party SDKs, payment/analytics embeds | bundle/route count, largest list rendered, offline/cache policy | what the client decides vs the server (pricing, permissions, validation), token storage |
| Scheduled job / worker | every system the job writes to, checkpoint/offset store | schedule, volume per run, run duration, overlap policy | service-account scope, which environment's data it touches |
| Data pipeline | sources and sinks, schema registries, late/duplicate data handling | partition sizes, backfill windows, freshness SLA | PII columns, row-level access, who may re-run |
| Library / shared schema | the systems its callers hit through it | callers' expected scale (from docs/issues) | what it trusts callers to validate |

A repository usually mixes types; the map row decides which column
applies to which module.

## Interview rules

- **Map first, facts second.** A fact under the wrong module misleads
  the reviewer of both.
- **Ask only what the code cannot answer.** The owner's patience is the
  scarcest input; a `repo:` line costs none of it.
- **Candidate first, question second.** The owner corrects a wrong claim
  faster than they compose a right one.
- **One fact per line, one answer per fact.** Compound questions get
  half-answers that look complete.
- **Ask for consequences, not stories.** "When the chargeback lands after
  the refund was issued, what does the system do?" — the answer is a party
  fact or a hazard.
- **Redirect requirements.** The moment the owner says "it should…",
  record nothing — not as a fact, not as an exclusion, not under *Not
  exercised* "so the reviewer knows" — name the boundary in one line
  ("that is intended behavior, not current —
  `writing-business-requirements` captures it") and continue with what
  the system *does*. A file that mentions the requirement has recorded it.
- **Unknown is an answer.** Never press; never fill with a guess. But "I
  can't recall, go with the code" is not `unknown`: the line keeps its
  `repo:` or `probe:` provenance. `unknown` is reserved for a fact with
  no evidence anywhere, so the open-unknowns list holds only what a later
  pass can still ask.

## Common mistakes

- **Asking the owner to describe the domain.** Open questions produce
  prose; the reviewer needs facts with units.
- **Transcribing the code.** Freed from the owner's patience, `repo:`
  lines multiply into a prose copy of every branch; every model
  overshoots without the density check.
- **Writing hazards nobody ruled on.** Twenty unruled hazard lines are a
  review, not context.
- **Recording requirements as facts.** "Should" is a different document.
- **Facts without provenance.** A claim nobody can trace is re-litigated
  by the next reviewer.
- **Appending edit history.** The file is a snapshot, not a changelog.
- **Filling `unknown` with a plausible default.** The default belongs to
  the consumer; the file records only what someone actually knows.
