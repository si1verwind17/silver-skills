# Code Review Ledger

Method: aspect-scored review — severity sets the band, count refines within
it; scores derive from open findings by fixed arithmetic. This document is
the persistent ledger: scope, assumptions, overrides, open findings, archive,
history. Finding IDs are stable and never reused. Keep the section names and
order of this template — incremental reviews and future maintainers navigate
by them.

## Summary — <date> @ <short-hash> (<full review | incremental>, <N> aspects, scope: <whole repo | paths>)

**Worst aspect(s): <aspect> at <score>/10.** <One line per open critical —
the headline is the worst aspect, never the average.>

<What changed in THIS review only — resolved / new / reopened findings.
Rewrite this section in place each review: it is a snapshot, never an
appended changelog; prior reviews' narratives live in the archive and git
history.>

| Aspect | Score | Worst open | Open findings |
|---|---|---|---|
| <Aspect name> (<ABBR>) | **<n>** | <critical / major / minor / —> | <IDs with C/M/m and (w)> |

Rows stay in catalog order (the History table's column order), never sorted
by score — so any two reviews line up row-for-row. The worst aspects are
named in the prose above, not by reordering the table.

Average (trend only, never the headline): **<n>**.

## Review scope

<Whole repo, or the modules/paths reviewed. A scoped review's scores cover
only this scope — label them with it wherever they are shown.>

Context: <`docs/domain-context.md @<hash>, owner-confirmed <date>` — or
`declared from code (no owner-confirmed context)`>.

<Only when independence was unavailable: `Reviewer: author session — no
independent agent available (<reason>)`. Omit the line entirely when an
independent reviewer produced the findings.>

## Declared assumptions (scout pass @ <hash>, <date>)

Evidence-based detections; each states the weakest claim its evidence
supports. Override any of these in User overrides — overrides persist and
bind future reviews.

- **Language(s)**: <languages, module layout, approximate size>
- **House pattern standard**: <per language; precedence: repo conventions >
  dominant pattern > community idiom — with the evidence>
- **Auth model**: <e.g. perimeter/sidecar vs in-code — evidence>
- **Deployment model**: <assumed model + basis + the scale the code runs at
  (instances, tenants, throughput) that MEM/CPU/CON severities are judged
  against. In-repo manifests, config mounts, or platform-override code
  prove the code *supports* a platform, not that it runs there — word as an
  assumption, never as fact>
- **Consumed contract surfaces**: <published artifacts, HTTP/gRPC APIs,
  who consumes them>
- **Tests**: <test setup found; whether gaps look intentional>
- **Exclusions**: <generated / vendored / build-output paths not reviewed>
- **Domain facts**: <external-system semantics findings rely on — who bears
  a fee and in what unit, whether an accepted request can rest unfilled,
  token/timestamp validity windows, delivery guarantees. Reviewers apply
  these; they need not already know them.>
- **Sweeps run**: <one line per mechanical sweep (aspects.md): pattern ·
  scope · hits · adjudication summary>
- **Domain-context checklist**: <when `docs/domain-context.md` exists — one
  line per §6 invariant (held / violated at file:line / not reachable), per
  §7 hazard (credited / filed as ID / not found), and per not-exercised
  operation — verdict and location only, no narrative; omit the bullet when
  the file is absent>
- **External-state journeys**: <operations enumerated for the journey table
  (SKILL.md) and any empty cells>
- **Scoring interpretation**: none → 10; minors only → 9 flat (listed and
  counted, never deducted); within the worst open C/M tier each *verified*
  finding counts one unit (widespread majors count two; doubling never
  applies to criticals); score = band start − step × (units − 1), clamped
  at the band floor (critical 3/−1/0, major 6/−1/4). The first unit is free
  — it sets the band: a single widespread major is 2 units → 6 − 1×1 = 5,
  never 4. Unverified C/M claims are listed as candidates and do not
  count.

## User overrides

_None yet._

## Open findings

Severity: C = critical, M = major, m = minor; (w) = widespread (counts
double). All findings open @ <hash — advance every review; finding bodies
stay pinned to their discovery hash>. Aspect sections stay in catalog order
(same as the summary table); a `---` rule before each aspect header keeps
the section boundary visible mid-scroll.

---

### <Aspect name> (<ABBR>) — <score>/10 · <count> open

**<ABBR>-<NNN> · <C|M|m> · <isolated|widespread> — <title>**
- `<file:line>` <occurrence list — one finding, many occurrences, never one
  finding per occurrence>
- Worksheet: reach <caller + condition, or *latent*> · residue
  <external|process|request|none> · signal <none|caller|operator> → <tier>
- <The evidence this finding's class requires — failing input, source→target
  path, scale/heat, per aspects.md. A claim without evidence is not a
  finding.>
- Cross-ref: <aspects that reference this finding — one home, one deduction>

**Candidates (unverified, not scored)**
- <ABBR> · <C|M> · <title> · `<file:line>` — <why verification failed or
  was skipped; what would confirm it>. Delete this block when empty.

## Credited as intentional (recorded to prevent re-litigation)

- <Decision found in evidence — comment, typed ignore, version bump, release
  note — and what would-be finding it settles.>

## Resolved findings (archive)

One line per resolved finding — `<ID> · <C|M|m> · <title> · <files> ·
resolved @<hash>`; full bodies live in this document's git history. A
reintroduced defect reopens its ID here (moves back to Open findings with
`reopened @<hash>`), never gets a new one.

_None yet._

## History

| Date | Hash | <one column per aspect ABBR> | Worst | Avg |
|---|---|---|---|---|

<When a scoring rule changes, add one line here marking the boundary and
the rule; rows on either side are not comparable and are never rescored.>

Per-aspect baselines (diff basis for the next incremental review):
<aspect @ hash, plus scope where narrower than whole repo. Dirty/unreviewed
files listed here are unbaselined — full re-check next run.>
