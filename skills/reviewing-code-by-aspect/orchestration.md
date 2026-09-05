# Orchestration

How a full or incremental review is executed: who reads what, in which
order, and how claims become findings. SKILL.md holds the flow and the
scoring; this file holds the mechanics it points to.

## Subagent clusters

Fan out by shared reading pass, not per aspect — fifteen agents re-reading
the same code waste tokens; one agent reading through fifteen lenses goes
shallow:

- **structure** — pattern + format + readability
- **error journeys** — error handling + logging
- **trust boundaries** — security + contract
- **hot paths and lifecycles** — memory + CPU + concurrency + state
- **test lens** — testable + test quality
- **intent and types** — logic + type/null (+ requirements alignment when
  selected)

A selected custom aspect joins the nearest cluster or runs alone.

Each reviewer returns findings with evidence and its file checklist; only
the orchestrator writes the ledger and computes scores. Reviewers never
receive the whole ledger: the archive, history and other aspects' findings
stay with the orchestrator, which needs them for ID allocation, reopen
detection and arithmetic — ledger size costs the orchestrator once, not
every cluster.

## The reviewer brief — sweep before reconcile

A reviewer receives, up front: the scout declarations, domain facts, user
overrides, credited decisions, and the domain-context checklist lines
(invariants, hazards, not-exercised operations) its aspects must answer.
Every proposed critical or major it returns carries its worksheet line. It receives its own aspects' **open
findings only after it has returned a fresh sweep** of its scope. Context
and prior findings are different inputs: declarations make severities
converge and are always supplied; a list of known findings supplied before
the sweep seeds it — the reviewer re-adjudicates the list and samples
around it, and a defect the list never named stays unfound. The reconcile
step then labels each prior finding resolved / still open / reopened and
each fresh finding new or duplicate; the orchestrator may do this itself
when the open list is short. In incremental mode the fresh sweep covers the
diff-touched files only — the rule is about ordering, not scope.

The brief also carries the **file checklist**: every file in the reviewer's
scope, which it confirms file by file before returning. A companion file is
not covered by its implementation.

## Verification

Every critical or major proposed by a reviewer goes to a clean-context
verifier with the finding, its worksheet line (`reach · residue · signal`)
and the code, asked to refute it. The verifier's mandate (SKILL.md ›
Scoring, verification gate) is asymmetric — remove only what the code
proves wrong — and a refutation must attack the finding's own failing
input, not a precondition owned by another open defect. Its answer is
*refuted*, *kept*, or a *fact correction* naming one worksheet answer and
the line that contradicts it; **a verifier never proposes a tier**, and the
orchestrator recomputes the tier from the corrected line. Inline mode
substitutes re-derivation: the reviewer rebuilds the failing input from
the code, not from the finding's prose. Survivors get IDs; the rest are
recorded as candidates, unscored.

**Test-quality candidates are run, not read.** A TSQ major claims a
concrete behavior change the missing test would let through; where a test
command exists, apply *that* mutation — the one the candidate names, never
a substitute — in a scratch copy and run the suite. Green promotes the
candidate to a verified major; red refutes it; unrun stays a candidate.
Restore the copy afterward — the review never leaves a mutation in the
tree.

## Journey enumeration — paths that mutate state outside the process

Cross-layer defects hide between locally-correct layers: a caller omits a
field, a model defaults it, a handler treats the resulting outcome as
success. Aspect-scoped reading does not find these — no single lens owns
the journey. For every operation that changes state outside the process
(an order to an exchange or payment provider, a queue publish, a write
another system consumes) the review fills a fixed table before scoring
LOG, ERR and CMP:

- **rows:** each operation the caller can issue, and each variant a default
  can select on its behalf;
- **columns:** completed · partial · accepted-but-pending (rests, queued,
  async) · rejected pre-flight · rejected by the external party · ambiguous
  (timeout, 5xx, decode failure) · superseded elsewhere (cancelled,
  expired, completed by another path);
- **per cell:** which layer decides, what is recorded, what is signalled,
  what protective state changes.

An empty cell is **filed** — as a finding under its home aspect or as a
credited decision that names the consequence — never only noted in the
assumptions block: a narrated cell has not been reviewed and dissolves into
some unrelated finding by the time the ledger is read. A cell whose
decision is "success" with nothing recorded is usually the critical. The
table is reproducible — the same operations and outcome classes exist on
every pass — which makes C/M recall on these paths deterministic where
aspect reading is sampling. Record the operations enumerated and any empty
cells in Declared assumptions, one line each.

## Reviewer independence — the reviewer is never the author

When the session running this skill also produced the code under review
(typically a re-review right after implementing fixes), it dispatches the
reviewing to a fresh **clean-context** agent — never one that inherits the
authoring session's context — and keeps only the orchestrator role. Author
grades own work is precisely the bias fan-out exists to remove, so this
binds **regardless of diff size**. A session that did not write the code is
already independent.

**Fallback — declared self-review.** Some environments gate agent dispatch
behind the user's permission or offer none. Independence is then
unavailable, not optional: the review still runs, working from the code and
diff as written and never from session memory of what a fix intended,
walking each aspect's checks mechanically. Record the limitation where the
score is read — one line under Review scope, `Reviewer: author session — no
independent agent available (<reason>)`, and the same line in the presented
summary. Its scores are labeled, not discounted: the undeclared
author-review is the failure; a declared one is honest evidence a later
independent pass can revise. This is the fallback when independence is
impossible, never a cheaper tier chosen against an available one.

## Recall — the ledger ratchets; single passes sample

Discovery is sampling, not enumeration: two competent full passes surface
overlapping but different finding sets, and an incremental review verifies
fixes without re-sweeping untouched code. The ledger absorbs this by being
a ratchet — the union of all verified findings across passes — so recall is
a property of the ledger's lifetime, not of one run. A 10 earned through
fix-verification alone means "the known findings are resolved", never "no
defects exist".

**The blind fresh-eyes pass** is the recall tool: a clean-context reviewer
runs a full review on a snapshot stripped of the ledger's findings, credits
and history, the git history, and any finding-ID references in code
comments or test names — all of which leak the prior taxonomy — but
**supplied with the ledger's Declared assumptions and User overrides**:
calibration is shared, discovery is not; without the scale and trust-model
context a blind reviewer rates a band harsher for reasons unrelated to the
code. The in-context orchestrator then verifies each new claim against the
code and merges survivors under new IDs. Blind reviewers re-litigate
settled judgments; discovery is theirs, severity adjudication stays
in-context.

**Never launch a blind pass on your own initiative** — it costs a full
review. When signals accumulate (several rounds since the last fresh full
pass, high scores resting only on fix-verification, a milestone the user
mentioned), append one line to the *presented summary* — never the ledger,
not History, not a scope caveat: "N rounds since the last fresh-eyes pass;
scores above X are fix-verified, not re-swept — consider a blind full review
(full-repo fan-out cost) or a cheaper scoped one (highest-stakes modules
only, or a rotating aspect subset)." The user schedules it. Convergence is
declared only after consecutive blind passes confirm nothing new.
