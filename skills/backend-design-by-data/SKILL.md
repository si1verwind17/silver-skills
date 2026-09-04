---
name: backend-design-by-data
description: >-
  Use when designing the data layer of a backend service — modeling tables or
  documents from requirements, writing DDL, choosing SQL vs document DB,
  designing idempotent or atomic write paths, minimizing DB round-trips,
  deciding constraints and indexes, planning schema evolution, or deciding
  whether multi-system updates need a message queue.
---

# Backend data design

Design starts from the data: translate the requirement into entities and DDL
first, then let the logic revolve around it. SQL examples assume Postgres (the
recommended RDBMS); the concepts are engine-neutral.

## The deliverable

A data design is delivered under **`docs/backend-design/`** in the target
repository unless the user specifies a different location. It consists of
three artifacts, produced so the user can review the reasoning and the next
agent can implement from them without re-deriving anything:

1. **`data-design.md`** containing, in order:
   - Requirements interpretation and **open questions** — every assumption
     made and question asked (id scopes, constraint gauge, growth);
   - Entity model — a Mermaid ER diagram plus per-table rationale (why each
     column, key, and constraint exists);
   - Engine decision with reasoning;
   - **Write-path table** — one row per operation: its idempotency mechanism,
     its atomicity mechanism, and its queue decision. This is the section an
     implementing agent consumes directly;
   - Index plan — each index beside the access pattern it serves;
   - Schema evolution notes.
2. **`data-dictionary.yaml`** — the authored source of column definitions,
   dbt-style (`tables: <name>: {description, columns: <col>: {type,
   description}}`), one entry per table and column stating its purpose, so a
   later analytics agent can know what every column means without a DB
   connection.
3. **When the engine is an RDBMS: a `ddl/` directory** of numbered,
   per-concern, runnable `.sql` files (`01-create-tables.sql`,
   `02-create-indexes.sql`, `03-functions.sql`, seed data) that match the
   design doc exactly — including `COMMENT ON TABLE/COLUMN` statements
   mirroring the data dictionary, so the definitions are also queryable
   inside the database (`pg_description`).

## From requirement to schema

Start with the core entity the requirement implies (an order system → an
`order` table) and expand by interrogating the domain: which ids, which
status, which dates (`paid_at`?), which links to other systems (`payment_id`)?
When the chain provides `docs/backend-design/context.md`, read it first:
existing DB conventions and shared data contracts inform the design
(conform at constraint boundaries; never mirror a listed wart; record
divergences per the orchestrator's rule).

Discover cardinality as you go — one order, many items → a child `order_item`
table **keyed by the parent's primary key**, never by a duplicated natural
string. Challenge every additional table (tag? item?) against the actual
requirement. If the requirement is too vague to decide, ask the user; if it's
clear, write the DDL.

**Pin down each id's semantics explicitly** — is `profile_id` a global
sequence, or does numbering restart per account? Getting this wrong is a
requirements misread that surfaces months later; when the scope of an id is
ambiguous, ask. References to external systems are stored as plain id columns
(no FK across service boundaries). Avoid reserved words as table names
(`order` needs quoting forever).

## DDL defaults

- Every table — **including lookup, helper, and log tables** — gets
  `created_at` with `DEFAULT now()`, named exactly `created_at` (not
  per-table variants like `changed_at` or `received_at`); updatable tables
  also get `updated_at`, supplied on update by the backend from its injected
  clock. Insert-only tables drop `updated_at`, never `created_at`.
- An indexable primary key — **`bigint` identity by default**. Random
  UUIDs (v4) as keys cost real insert throughput and index health: every
  insert lands on a random B-tree leaf (cold pages, splits, bloat, WAL
  amplification) and the 16-byte width repeats in every referencing FK
  index. Use a UUID key only when ids are generated outside the database
  (client-side, multi-writer) or exposed publicly where enumerable ids
  leak — and then **time-ordered UUIDv7, not v4**, to restore insert
  locality. A hybrid is fine: bigint PK internally, a separate unique UUID
  column as the public identifier. When the public identifier must be short
  or human-friendly (read over the phone, typed from an email) and a UUID
  doesn't fit, the reference must still be **non-enumerable**: padding,
  prefixes, checksums, or a standard base-N encoding of a sequential value
  are order-preserving — anyone holding one reference can compute its
  neighbors (and ticket/order volume between two references). Derive it
  through a bijective scramble (e.g. multiply by an odd constant mod 2^k
  before encoding) or generate a random code behind a unique constraint
  with insert-retry. The test to apply: given one valid reference, can a
  stranger produce another valid one, or read how many exist? If yes, it's
  the sequential id in costume. Be honest about what a scramble buys: it
  defeats casual enumeration, not an adversary — the constant is
  recoverable from a handful of known id/reference pairs. When guessing a
  reference must be genuinely infeasible (it acts as a bearer credential),
  use random codes or UUIDs, never a derived value.
- One timestamp type (`timestamptz`) across the whole schema — never epoch
  `BIGINT` in one log table and `timestamptz` in its sibling.
- Statuses: FK to a lookup table or a DB enum — never free-text `VARCHAR`
  statuses or magic-number `CHECK (status_id IN (1,2,3))` duplicated across
  tables.
- Real columns for core entities. A JSONB column that holds the whole
  business object forces `->>'field'::type` casts into every query and view
  and forfeits constraints — reserve JSONB for genuinely unstructured
  payloads.
- Split append-only tables (status logs, failure details, dead letters) from
  updatable entities; logs are insert-only by design.

**Columns holding encoded values state their exact format.** A column
carrying a path, URL, code, or any encoded reference gets its precise
stored format in the data dictionary and the `COMMENT ON` ("full request
path incl. leading slash, e.g. `/articles/foo`" — not just "the
redirect source"). Producer and consumer both cite the column's stated
format; a format left implicit is a seam where two modules each guess
differently, both pass their own tests, and every real request misses.

## Constraints: gauge, don't default

Add FK/unique/check constraints where the requirement genuinely demands
integrity; skip them where it doesn't. When unclear, ask the user and give a
recommendation. Both failure directions are real: shipping a core table with
its FKs commented out ("todo, confirm") leaves the central data unenforced,
while reflexive constraints on everything makes operations fight the schema.
Friction inserting test data by hand is a signal to write seed scripts — not
to weaken production constraints. For soft-state uniqueness, use a partial
unique index (`UNIQUE ... WHERE status <> 'cancelled'`) so terminal rows
don't block new active ones.

## Engine choice

Relations plus enforced schema → Postgres. Access pattern is single-document
retrieval with no relational needs → a document DB — do not pick one from
memory: research the current options at decision time (the cloud already in
use, managed vs self-hosted, streaming/changefeed needs) and recommend one to
the user with reasoning. In a document DB, design write paths to touch a
single document — multi-document writes should not exist by design.

## Idempotency lives in the query

For every write path ask: should this create a new record or reuse one?
Inserts are keyed by a caller-provided unique key (`ON CONFLICT DO NOTHING` /
upsert). State transitions are guarded updates:

```sql
UPDATE "order" SET status = 'paid', updated_at = $now
WHERE order_id = $id AND status = 'unpaid';
```

so a redelivered message can't re-apply paid→paid. Zero rows matched is
ambiguous by itself — it covers "already applied" (fine for a retrying
consumer), "row doesn't exist", and "illegal state". Treat it as
already-applied only on paths where redelivery is the expected cause;
where the distinction matters, re-read the row and classify instead of
assuming success.

For state-machine transitions, **classify a suspected duplicate by the
row's current state, never by matching action history**: a retry is
recognized because the current state is one this exact call could have
left the row in (the call's target plus its own cascade); any other
state is a real illegal-transition error. Audit-log/EXISTS guards
("this action was already recorded") look equivalent but false-match
every legitimately repeating action — annual re-review cycles,
per-revision resubmit loops, any workflow that revisits a state by
design — and each patch (scoping by revision, discriminating creation
rows) just moves the false match somewhere else. State is the truth;
history is commentary. Guard inside
procedures too: `WHERE status NOT IN (<terminal states>)` prevents overwriting
final states.

## Atomicity and round-trips

Two or more writes that belong together execute in **one network trip**: a
stored procedure/function, or a data-modifying CTE
(`WITH upd AS (UPDATE ...) INSERT ...`) — whichever the client stack supports.

## Multi-system updates go through a queue

A "double update" is multiple systems each updating **their own** data from
the same source event (order completed → delivery and payment each react).
The source publishes the event once its required business logic has
succeeded; each consumer updates its own DB via the queue, so a failed
consumer retries independently. Never write another service's database
directly. And if the project is too small for microservices, don't imitate
the pattern — one database, one atomic update. Why it matters: partial
fan-out failures show users a paid-but-undelivered (or delivered-but-unpaid)
state with no retry path; queue consumers converge.

## Indexes follow access patterns

Create indexes from the queries the requirement implies — composite indexes
mirroring real `WHERE` clauses — plus the ops/debug paths ("sometimes we only
have the `account_id`"). Not blanket, not skipped.

## Schema evolution

Design expecting change: numbered migration folders with per-concern files
(tables / indexes / functions / seed data), additive changes first, and test
migration guards — an `information_schema` check that can never match makes
the guarded statement silently never run.

## Before delivering — verify

Walk these checks against your actual output files; fix, don't rationalize:

- [ ] Every table — lookup, helper, inbox/dedup, and log tables included —
      has `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, named exactly
      `created_at`; `updated_at` present on updatable tables only.
- [ ] The ER diagram, `data-dictionary.yaml`, and `ddl/` agree
      column-for-column — walk each table in all three.
- [ ] Every ambiguity you resolved by assumption appears in the open
      questions section.
- [ ] Every write path has a row in the write-path table.
- [ ] `COMMENT ON` statements cover every table and column in the DDL.

## Common mistakes

- Id scope misread (per-parent numbering vs global sequence) — ask early.
- Statuses as strings or magic-number CHECKs; needed FKs left commented out.
- Child tables joined by a duplicated natural key with no FK to the parent.
- One JSONB column as the whole schema of a core entity.
- Mixed timestamp representations across sibling tables.
- Indexes invented without an access pattern, or debug paths left unindexed.
- Constraints dropped for test-data convenience instead of writing seeds.
