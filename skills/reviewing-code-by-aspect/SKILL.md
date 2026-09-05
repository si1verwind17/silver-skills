---
name: reviewing-code-by-aspect
description: >-
  Use when asked to review code and produce a scored, documented result — a
  full-codebase audit, an incremental re-review since a previous review, or
  a quick tiered review of one diff or PR — rather than an ad-hoc
  fix-as-you-go pass. Covers aspect selection, severity tiers, per-aspect
  scores, the persistent review ledger, declared assumptions, requirements
  alignment, and subagent fan-out. Keywords: code review, audit, PR review,
  diff review, findings, severity, incremental review, render to HTML.
---

# Reviewing code by aspect

A bounded, measurable code review: the reviewer scores the codebase on a
selected set of aspects and writes findings into a persistent ledger. The
skill is **review-only** — it never edits code. That is what terminates the
otherwise-endless review-fix-review loop: the deliverable is the document,
and fixing is a separate task the user starts and scopes.

"Measurable" has two consequences. Scores are **derived from findings by
fixed arithmetic**, never assigned by judgment — the same open findings
always produce the same score, and "improve the score" collapses to
"resolve these named findings". And every finding carries the evidence its
class requires (`aspects.md`) — a claim without evidence is not a finding.

Files beside this one, read as needed:

- `aspects.md` — the catalog: each aspect's standard, severity ladder,
  evidence requirement, suppression list; the mechanical sweeps. **Read it
  before reviewing; the suppression lists bind as much as the ladders.**
- `orchestration.md` — subagent clusters, reviewer briefs, verification,
  the journey table, reviewer independence, the blind fresh-eyes pass.
- `ledger.md` — the ledger's sections, hygiene rules, archive, rendering
  and JSON export.
- `template/review.md` — the ledger skeleton for a new repo.

## Three modes

| Mode | Trigger | Scope | Output |
|---|---|---|---|
| **Full** | no ledger, or the user asks for a fresh audit | whole repo or named paths | ledger with scores |
| **Incremental** | a ledger exists and code changed | diff since each aspect's baseline | ledger updated, scores recomputed over all open findings |
| **Quick** | "review this diff / PR / branch", no ledger wanted | one diff | tiered findings, no scores, no ledger |

Full and incremental follow the flow below. Quick is defined at the end of
this file; it keeps the ladders, evidence bar and suppression lists, and
drops the ceremony and the arithmetic.

## Flow (full and incremental)

1. **Locate the ledger.** Default `docs/review/review.md` in the target
   repo. If it exists, its baselines, declared assumptions, user overrides
   and custom rubrics are all still in force; a full re-review starts from
   the ledger's assumptions and overrides — never re-scouts from zero — so
   two full passes calibrate identically. Apply the tidy tripwire
   (`ledger.md`) before dispatching anything.
2. **Select scope and aspects in one prompt.** Show the `aspects.md` menu as
   a numbered two-column list with core aspects pre-selected; invite
   toggles by number, "all", and custom aspects by name; ask the scope
   (whole repo default, or named paths). A custom aspect gets its ladder
   drafted on the spot in the same tier scheme. Echo the final list once
   for confirm-or-edit. **One confirmation** — a review is expensive enough
   to deserve one, and ceremony beyond it is waste.
3. **Plan scope per aspect.** Each aspect has its own baseline hash. No
   baseline → full pass for that aspect; baseline → diff since it. A run
   may mix both. A scoped run advances baselines only for what it reviewed;
   files outside a prior scope are unbaselined for that aspect; scoped
   scores are labeled with their scope wherever shown — a module's score
   is never presented as the codebase's.
4. **Choose the execution tier with the user, once.** Offer three:
   strongest-model fan-out (depth), strongest orchestrator with mid-tier
   reviewers (middle), inline single-agent (economy). Name the cost —
   fan-out multiplies tokens by the cluster count — and let the user trade
   missed-finding risk against quota; recommend depth for full reviews,
   never default to it silently. Settle the reviewer channel here too
   (where dispatch needs permission or is unavailable) and record the
   answer; re-asking each round trains the user to wave the review through.
   Independence is a separate axis: if this session authored the code,
   delegate to a clean-context reviewer regardless of diff size
   (`orchestration.md` › Reviewer independence).
5. **Scout once.** Read `docs/domain-context.md` first if it exists (written
   by `documenting-domain-context`): its owner-confirmed facts are the
   strongest evidence a declaration can cite; its `repo:` lines are
   the review's declared assumptions, restated with their line rather
   than re-derived; its module map names the core-path modules severity
   is placed against; a hazard is credited only when its line carries
   owner acceptance — a hazard the owner declined is filed as a finding
   that cites the line; and a contradiction between the file and the
   code is reported, never silently resolved either way. If it
   is absent, proceed on code evidence and add one line to the presented
   summary — never a question, never a gate: "No owner-confirmed context
   found — assumptions declared from code alone; scale, fee and trust
   severities are calibrated against defaults. `documenting-domain-context`
   (a short owner Q&A) recalibrates the next review." When the file exists,
   its invariants (§6) and every module's hazards and not-exercised lines
   (§7) become a **checklist the review answers line by line** in Declared
   assumptions — each invariant `held` / `violated at file:line` / `not
   reachable`, each hazard `credited` (owner accepted) / `filed as <ID>`
   (owner declined, or unruled and confirmed in code) / `not found`, each
   not-exercised operation named so reach answers can cite it. A listed
   line that no cluster adjudicated is a scope gap the summary names. One
   pass produces the declarations every aspect consumes:
   languages and house pattern per language, auth model, deployment model
   **with the scale the code runs at** (instances, tenants, throughput —
   what MEM/CPU/CON severities are judged against), consumed contract
   surfaces, generated/vendored exclusions, **domain facts** findings will
   rely on (who bears a fee and in what unit, whether an accepted request
   can rest unfilled, how long a token stays valid), the **mechanical
   sweeps** run with hit counts, and the operations enumerated for the
   journey table. Each declaration states the **weakest claim its evidence
   supports**: manifests prove the code *supports* a platform, not that it
   runs there — "assumed X; basis: repo ships k8s manifests (capability
   evidence only)", never a runtime asserted as fact. Never ask the user to
   fill a gap: declare the most defensible standard and proceed; overrides
   come later and persist.
6. **Review.** Fan out by cluster or run clusters inline
   (`orchestration.md`). Incremental aspects: re-check open findings whose
   files the diff touched (resolved / still open), carry untouched ones
   forward unchanged, add new findings from the diff only. A new finding
   that is the same distinct issue as an archived one **reopens that ID**
   (`reopened @hash`) — the ID names the defect, not the event of finding
   it, and a regression usually earns a TSQ cross-ref. **A fix re-review
   sweeps the whole enclosing unit** (function/class) as new code, never
   just the changed lines. **Every file in scope gets its own pass** — the
   reviewer keeps a checklist of files (diff-touched files in incremental
   mode) and confirms each was read before returning; a companion file
   (interface, config, migration, test) is not covered by reviewing its
   implementation.
7. **Verify, then merge mechanically.** Every proposed critical or major
   passes the verification gate below before it gets an ID; its tier is
   read off the severity table from the verified worksheet line. Dedup
   cross-referenced findings (one home aspect, one deduction), compute
   scores by the band table, update the ledger, present the summary. No
   model judgment in this step — that is what makes results reproducible.
8. **Stop.** Present the summary (worst aspect first) and end. No fixing,
   no second pass, no offer-loop. Fixes are a new task the user scopes
   against named finding IDs. Regenerate any HTML/JSON view that already
   exists beside the ledger — they are stale the moment the ledger changes
   (`ledger.md`). Recommend committing the ledger (a new ledger directory
   also gets `template/gitignore` as `.gitignore`, so derived views stay
   out of the repo); the skill itself never commits.

**Dirty working tree.** A scored review targets a commit — baselines are
reproducible only at a hash. If the tree is dirty, review HEAD and declare
the dirty/untracked files "uncommitted changes NOT reviewed". When the user
asks for uncommitted work, run it *provisional*: mark dirty-file findings
`provisional @uncommitted — re-verify at next commit` and advance no
baseline past HEAD. A baseline once recorded against a dirty tree makes its
dirty-listed files unbaselined — full re-check, not diff.

## Scoring

Per aspect, 0–10. **Severity sets the band; count refines within it:**

| Worst open finding | Score |
|---|---|
| none | 10 |
| minor only | 9 — flat; minors are listed and counted, never deducted |
| major | 6, −1 per additional major, floor 4 |
| critical | 3, −1 per additional critical, floor 0 |

Reading: **9–10** nothing structural (10 = nothing found, 9 = polish only)
· **4–6** real issues, schedule fixes · **≤3** do not ship this aspect.
Aspects that cap their own tier (no-critical floor 4, minor-only band 9)
encode how serious that aspect can get.

- **Minors never move the score** because their supply is unbounded — every
  codebase holds endless naming drift and magic constants, and each review
  samples a different handful. Deducting per minor would score which
  handful the reviewer picked. The aspect header still shows the count.
- **Severity measures consequence and risk, never whether the code runs.**
  Working code carries criticals; that is the finding worth surfacing. The
  tiers are critical / major / minor — never "blocker".
- **One distinct issue with one distinct fix is one finding**, with an
  occurrence list — never one finding per occurrence. A finding is
  *widespread* when it spans multiple modules or roughly a quarter of the
  files where the convention applies; a widespread major counts double
  (sets the band and deducts one more step → 5). Doubling never applies to
  criticals; on minors the label is descriptive. The finding states which
  level applied and why.
- **Scores are recomputed from all open findings, old and new** — an
  incremental review still scores the codebase, not the diff.
- **Severity is derived from three recorded facts, never argued.** Two
  reviewers who read the same code agree on what a path leaves behind far
  more often than on what to call it; the tier is therefore a lookup. Every
  proposed critical or major carries a **worksheet line** — `reach · residue
  · signal` — and the tier follows from it:
  - *Reach*: the caller and condition that get to the failing path, judged
    against the declared deployment model and the domain context's
    not-exercised list. A path no existing caller can take under that model
    (a race that needs a second caller the deployment does not have, a
    branch behind an operation the owner marked not exercised) is **latent
    → minor**, whatever its residue. Reach asks whether a caller can take
    the path, never how far the consequence grows: growth bounded only by
    an external population (users, symbols, days) is unbounded here.
  - *Residue*: what is left after the path runs — `external` (state outside
    the process wrong or missing: a store, a ledger, a venue, a queue,
    money — and data read or altered across a trust boundary, so a
    disclosure to the wrong principal is external residue), `process` (the
    service down or wedged), `request` (a wrong or failed response with
    state intact), or `none` (structure, hygiene).
  - *Signal*: `none` (the caller is told success, or nothing), `caller` (a
    typed failure the caller can act on), or `operator` (an alert, or a log
    line a documented process reads). A log line nobody is shown is `none`.

  | Residue | Signal none | Signal to caller or operator |
  |---|---|---|
  | external or process | critical | major |
  | request | major | major |
  | none | minor | minor |

  Structural aspects (PAT, RDB, FMT, TST) answer *reach* as **load-bearing**
  — on a core-path module per the module map, or a guarantee the pattern
  exists to provide — and keep their ladders: broken guarantee → critical,
  load-bearing violation → major, local deviation → minor. Where an aspect
  ladder in `aspects.md` names a tier for a shape, the ladder is read as an
  instance of this table, never as an exception to it. **Intentionality**
  (cross-cutting principles) converts a finding only when the evidence
  decides the *residue*; a comment or handler that improves the *signal*
  moves the tier one row, it does not credit the finding.
- **Verification gate — a critical or major enters the score only after an
  independent attempt to refute it.** In fan-out mode a clean-context
  verifier receives the finding, its worksheet line and the code; inline,
  the reviewer re-derives the failing input from the code rather than from
  the finding's text. The verifier returns one of three things: **refuted**
  (the code proves the failing input cannot occur), **kept**, or a
  **fact correction** — one worksheet answer with the line that contradicts
  it ("signal: operator — Discord alert at x.scala:498"). **It never assigns
  a tier**; the orchestrator recomputes the tier from the corrected line
  and marks it `(corrected: …)` — no separate corrections log.
  The mandate is asymmetric: **it removes only what the code proves
  wrong.** Doubt, distaste, low perceived value, or an inability to
  reproduce are not refutations — the finding stays. The asymmetry is
  deliberate: a wrongly kept finding costs a reader seconds; a wrongly
  dropped one vanishes and nobody learns it existed. A refutation must
  attack the finding's own failing input; a defect masked only by another
  open defect (a retry that cannot double-charge today because nothing is
  ever captured) keeps its tier and records the dependency. What fails or
  skips the gate is recorded in its aspect section as a *candidate*: no ID,
  not in the score.
- The scale stays 10. A 100-scale adds no resolution (the measurement is
  band + count) and invites chasing phantom 3-point differences.

**Summary header, in order:** the worst aspect and its score (a review is
as good as its weakest aspect), then the full per-aspect table in catalog
order (never sorted by score — fixed rows let two reviews be compared
line-for-line), then the average *only* as a trend. Never lead with the
average: fourteen 10s and one 2 average to 9.5 on a codebase that must not
ship.

## Cross-cutting principles

Stated once here; the catalog assumes them.

- **Blast radius:** process-down consequences are critical; request-scoped
  consequences are major. One rule for errors, memory, CPU, concurrency,
  and type/null alike.
- **Visibility:** a visible crash outranks a silent success. A swallowed
  error lies; an unhandled one at least reports itself.
- **The unit of review is the journey, not the call site.** Propagation is
  handling when a deliberate handler exists downstream; boundary validation
  is validation; sidecar authn is authn. Findings attach to journeys that
  end nowhere or badly — never to a function for lacking its own guard.
- **Intentionality converts findings** — but only a decision about the
  *consequence*. A comment, a typed `.ignore`, a version bump, a test that
  pins the behavior turns a would-be finding into accepted behavior. A
  comment that narrates the branch ("no fill → no ledger, no stop") decides
  nothing about what the branch leaves behind; credit only evidence that
  names the hazard being accepted.
- **Language is the consistency boundary.** Detection and standards run per
  language; cross-language differences are never findings; same-language
  mixing across modules is.
- **Tools' work stays with tools.** No formatter output re-derived by hand,
  no CVE claims from model memory, no coverage percentages. A mechanizable
  check at most earns a note that the tool is missing.
- **A negative claim carries its sweep.** "Verified clean", "the only raw
  splice", "no committed secrets" are falsifiable claims: state the sweep
  (pattern, scope, hits adjudicated) or scope the wording to what was swept.
- **Reviewer independence — the reviewer is never the author.** See
  `orchestration.md`; it binds regardless of diff size, and when
  independence is impossible the self-review is *declared*, never silent.

## Quick mode — one diff, tiered, unscored

For "review this PR / branch / diff" where the user wants findings, not a
ledger. Same ladders, evidence requirements, suppression lists, verification
gate and independence rule; no menu, no scores, no ledger.

- **Scope:** the diff the user names (`base..head`, a commit, or the working
  tree — the last is provisional by definition). Aspects: the core set plus
  REQ when a plan, ticket or requirements text is supplied or findable; the
  user may add opt-ins by name in the request.
- **Scout in miniature:** read `docs/domain-context.md` if present, then
  the touched files' surroundings enough to state, in three lines, the
  house pattern, auth model and the domain facts the findings rely on. Run
  the mechanical sweeps over the touched files. No advisory when the
  context file is absent — a PR review does not warrant an interview.
- **Review** with the file checklist (step 6) — every touched file gets its
  own pass, and the enclosing unit of every change is read, not just the
  hunk. One reviewer inline, or one clean-context reviewer when the session
  authored the diff.
- **Output** a findings list, worst tier first: `ABBR · C/M/m · title ·
  file:line · evidence · suggested direction of fix (one line, never a
  patch)`; then candidates (unverified C/M) separately; then a one-line
  verdict — *no critical or major open*, or *N critical, M major open* —
  and nothing else. No strengths section, no praise, no average, no score:
  a score needs a baseline and an aspect scope to mean anything, and a
  single diff has neither.
- If a ledger exists, quick mode still reads its assumptions and overrides
  (calibration is free) but **does not write to it** — a quick review is
  not a baseline. Say so in the output's first line.
- Stop after the list. Fixing is the user's next task.

## Common mistakes

- **Fixing anything.** The moment the review edits code it stops being a
  measurement and restarts the loop the skill exists to end.
- **Self-reviewing your own fix.** Delegate to a clean-context reviewer;
  keep only merge + ledger in the authoring session. Where dispatch is
  unavailable, declare the self-review — the undeclared one is the failure.
- **Renegotiating the reviewer channel every round.** Settle it once.
- **Scoring by impression.** A score that cannot be reproduced from the
  open findings by the band table is decoration.
- **Blocking on the user mid-review** ("which pattern do you intend?").
  Declare and proceed; overrides come later and persist.
- **Re-litigating carried-forward findings.** Untouched open findings move
  forward as-is; only diff-touched files get re-checked.
- **Double-deducting cross-referenced findings.** One home, one deduction.
- **Slicing one issue into many findings.** Ten occurrences is one
  widespread finding; ten findings is how a score gets nuked by noise.
- **Verifiers that prune by taste.** The gate removes what the code proves
  wrong, nothing else.
- **Handing reviewers the open-findings list with their brief.** Declarations
  first; findings only after the fresh sweep returns.
- **Settling a test-quality candidate by reading it.** Where a test command
  exists, the named mutation is run in a scratch copy.
- **Launching a blind fresh-eyes pass unprompted.** It costs a full review;
  the skill advises in the presented summary, the user schedules.
- **Scoring a quick review.** A number on a single diff is a measurement of
  nothing; quick mode reports tiers and a verdict.
