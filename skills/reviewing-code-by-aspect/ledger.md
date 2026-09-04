# The ledger

`docs/review/review.md` (or the path the user names) is the review's only
durable output and the input to every later review. Start a new one from
`template/review.md` and keep its section names and order — incremental
reviews and future maintainers navigate by them, and consistency across
repos is part of what makes ledgers readable.

## Sections, in order

1. **Summary** — worst aspect and score first, what changed in this review,
   the per-aspect table in catalog order, the average as trend only.
2. **Review scope** — whole repo or the paths reviewed; the `Context:`
   line (owner-confirmed file and hash, or declared from code); the
   declared self-review line when independence was unavailable.
3. **Declared assumptions** and **User overrides** — including custom
   aspect rubrics, the exclusion list, sweeps run, journeys enumerated.
4. **Open findings** grouped by aspect, catalog order, a `---` rule before
   each `### <Aspect> (<ABBR>) — <score>/10 · <n> open` header so the
   boundary stays visible mid-scroll; candidates listed per aspect after
   the scored findings.
5. **Credited as intentional** — decisions recorded to prevent
   re-litigation.
6. **Resolved findings (archive)** — one line each.
7. **History** — one row per review (date, hash, per-aspect scores, worst,
   average) plus each aspect's current baseline hash and scope.

## Findings

IDs are `ABBR-NNN` — aspect abbreviation, hyphen, three-digit zero-padded
sequence, the same form in every aspect (never `SEC-3`, never `PAT-a`), and
never reused — so incremental reviews, overrides and fix tasks can name
them. Each finding records: severity, isolated/widespread, title,
occurrence list (`file:line`), the evidence its class requires, cross-refs,
and status (open / open, reopened @hash / resolved @hash /
accepted-by-override).

## Hygiene — the document must not grow without bound

- **On resolve, collapse to one archive line**: `ID · severity · title ·
  files · resolved @hash`. Git history keeps the full evidence; the archive
  exists only to prevent ID reuse and to recognize reopens. A reopened
  finding returns to Open findings with its body rebuilt from current
  evidence.
- **Prune a credited-as-intentional entry** when the diff removes the code
  it refers to — it exists to prevent re-litigation of live code only.
- **Archive overflow:** once the archive passes roughly 200 lines, move
  lines older than the last few reviews to `docs/review/archive.md` and
  keep only `ID · severity · title` in the ledger — all reopen detection
  and ID uniqueness need. The orchestrator searches `archive.md` on a
  suspected reopen.
- **Snapshot, not changelog — an incremental review rewrites in place,
  never appends.** The Summary describes the current review only; prior
  reviews' resolution narratives are deleted, not preserved — a kept
  paragraph goes stale and eventually contradicts the board (a "still
  open" claim beside its own resolution). Inside aspect sections, a
  resolved finding's body and fix story leave; at most a one-line "IDs
  resolved — see archive" pointer remains. Hash references outside
  Declared assumptions and History advance with every review (Review
  scope, the Open-findings header); finding bodies stay pinned to their
  discovery hash, and the Open-findings header says so once.
- **The ledger records reviews, never action items.** An ops step a fix
  requires (a migration to apply, a config to redeploy) is reported in the
  presented summary, not written into the document — a note no one checks
  off never resolves.
- **A scoring-rule change** (a band redefined, a deduction removed) gets
  one History line marking the boundary and the rule; rows on either side
  are not comparable and are never rescored.

## Tidy on read — gated by a tripwire

The ledger is edited between reviews by fix sessions and humans, and prose
accretes where it should not. Before dispatch, the orchestrator checks the
ledger it reads: Summary over roughly 40 lines, Declared assumptions over
roughly 60, or any narrative of change inside either ("*amended @hash, the
earlier declaration was wrong*") means normalise first — Summary to the
current snapshot, each assumption bullet to what is true now, one History
line for a change that matters. A clean ledger costs nothing; a separate
tidy agent is dispatched only when the tripwire fires, with a narrow brief
and mechanical acceptance: it touches only Summary, Declared assumptions
and Credits; every finding ID present before is present after; User
overrides are byte-identical; no score, tier or finding text changes; the
result has fewer lines. The orchestrator checks those invariants before
accepting — a tidy that changed meaning is rejected, not merged.

## Committing

Recommend that the user commit the ledger — the skill never commits. A
committed ledger travels with the repo, keeps its history, and teaches any
future maintainer its own method; an untracked one dies with the checkout.

## Rendering and export

`scripts/render_review.mjs` in this skill's folder (Node, no dependencies)
turns the ledger into derived views. The markdown stays the only source of
truth: views are regenerated after every ledger update, never hand-edited,
never edited by agents. The script lives here, not in reviewed repos —
one copy, versioned with the ledger format it parses.

**Views are derived, so they are not committed.** When a ledger is first
created, copy `template/gitignore` to `docs/review/.gitignore` (it ignores
`*.html` and `*.json` in that directory); the render script also writes it
beside its output when none exists. **Never overwrite or extend an existing
`.gitignore`** — a user who deleted the ignore lines has chosen to commit a
view, and that choice stands. A committed view is not an error to fix; it
is regenerated like any other on the next ledger update.

**A view is never read before it is regenerated.** An existing `.html` or
`.json` is treated as stale on sight: file timestamps are not evidence (a
checkout rewrites them, a copied file carries its own), and the script
costs under a second and no tokens. So any task that wants the JSON (a CI
gate, a fix task picking findings by ID) or the HTML runs the script first
and reads its output; a missing view is generated the same way, never a
reason to stop — the ledger is always sufficient input. Each view carries
`source_sha256` (JSON top-level field; HTML `<meta name="source-sha256">`)
— the hash of the ledger text it was built from — so a reader that cannot
run the script (a CI job checking a committed view, a human) can still
tell whether the view matches the ledger: `sha256sum review.md` must equal
the stamp, otherwise the view is stale and its contents are not to be
trusted.

**After every ledger write, regenerate whichever views already exist** in
the ledger's directory — the review that changed the ledger is the moment
its cached views went stale. Do not create a view nobody asked for: no
existing `.html` or `.json` means no render unless the user requests one.

- **HTML page** — `node <skill>/scripts/render_review.mjs <ledger>.md
  [<out>.html]` writes a self-contained page beside the ledger. Its one
  addition over the markdown is a threshold-banded score chart (aspects in
  catalog order, bands ≥8 ship · 4–7 schedule fixes · ≤3 do not ship, band
  icons and labels on every row so color never carries the band alone) with
  a history slider when the History table has more than one row. Chart
  data is parsed from the ledger's own summary table and cannot drift from
  it.
- **JSON** — `node <skill>/scripts/render_review.mjs <ledger>.md --json
  [<out>.json]` emits the summary table, open findings (ID, aspect, tier,
  widespread, title, occurrences), candidates, and history rows for CI
  gates, dashboards, or a fix task that wants to pick findings by ID.
  Omitting `<out>` writes to stdout.
