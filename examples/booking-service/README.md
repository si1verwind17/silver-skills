# Booking service

A backend service where providers publish when they are bookable and customers
reserve time against that availability. Providers own resources (staff, rooms)
and offer services with a duration and a capacity; availability is published as
recurring weekly rules with one-off exceptions; bookings are confirmed instantly
or held for provider approval, and afterwards marked completed or no-show.

---

## What this repository actually is

**This is an example artifact, not a product.** It was produced end to end by
running a chain of authoring skills from the skills published in this repository, to see what
that chain yields on a non-trivial domain. Every document and every line of code
here came out of that run, including the mistakes.

The chain, in the order it ran:

| Phase | Skill | Output |
|---|---|---|
| 0 | `writing-business-requirements` | `docs/backend-design/requirements.md` |
| 1 | `backend-design-by-data` | `data-design.md`, `data-dictionary.yaml`, `ddl/` |
| 2 | `backend-architecture` | `architecture-design.md` |
| 3 | `backend-stack-selection` | `stack-selection.md` |
| 4 | `backend-code-conventions` | the service and its tests |
| — | `rendering-design-docs` | `docs/backend-design/design-review.html` |
| — | `reviewing-code-by-aspect` | `docs/review/review.md` |

`building-backend-services` orchestrated phases 0–4, each ending at a human
review gate. Design decisions carry requirement ids (`R1`, `NF4`, `UC5`, `PD17`,
`AQ8`…) and the code cites them, so any rule can be traced from the requirement
that motivated it to the constraint that enforces it.

### Read this before using any of it

The code was reviewed by the same library's review skill, by clean-context
reviewers rather than by its author. **It scored 3/10 on Security and 3/10 on
Logic correctness — two criticals.** The full board:

| LOG | ERR | SEC | PAT | CON | CMP | LGG | TSQ | TYP | RDB |
|---|---|---|---|---|---|---|---|---|---|
| 3 | 4 | 3 | 10 | 4 | 4 | 9 | 4 | 10 | 7 |

- **SEC-001** — `GET /internal/v1/bookings/{ref}/contacts` performs no
  object-level authorization. Any authenticated caller holding any booking
  reference retrieves that booking's name, email and phone.
- **LOG-001** — reschedule accepts and validates a new resource reference,
  carries it through four layers, then discards it. The caller receives success
  for a move that did not happen.

Both are described in full, with evidence, in `docs/review/review.md`. Neither
is fixed. **Do not deploy this.**

That the review found them is the point of including it: they were written by an
agent that believed the code was sound and said so at the gate.

---

## Design

Two decisions shape everything else.

**The database owns correctness.** Capacity, overlap and buffer are one
constraint — a partial GiST exclusion constraint over a `tstzrange` widened by
each service's buffers — so no two sessions can occupy a resource at once, and
the guarantee is held by an index rather than by application code. Requirement
NF4 asks for exactly that: prevention must not be a check-then-write.

```sql
CONSTRAINT session_no_overlap
    EXCLUDE USING gist (resource_id WITH =, occupied_range WITH &&)
    WHERE (booked_count > 0)
```

The permitted state transitions live in a seeded table, not in branches, and the
write paths are plpgsql functions that perform validation, the capacity move, the
history row and the outbox insert in one transaction.

**Availability is computed, never stored.** The only rows describing time are
recurring rules, one-off exceptions, and real occupancy. Nothing is materialised
ahead of time, so editing a rule cannot leave stale slots behind.

Consequences worth knowing:

- A booking's event is written in the same transaction as its state change
  (transactional outbox), and *exactly one event per transition* is enforced by a
  composite foreign key plus a unique constraint — not by convention.
- Events carry **identifiers only**, never PII. `SEC-001` is the endpoint built
  to compensate for that, and is why it matters.
- A gate resolution arriving after a hold has expired does **not** resurrect the
  booking; it publishes a rejection so the sender can compensate.

## Stack

Chosen in phase 3 against the service's shape, with reasoning and sources in
`docs/backend-design/stack-selection.md`. Versions come from the Spring Boot BOM
except where noted.

Kotlin 2.3.21 · Spring Boot 4.1.1 (Spring MVC on virtual threads, no WebFlux) ·
Temurin 25 LTS · Gradle 9.7.1 · PostgreSQL 18.6 · Apache Kafka 4.3.1 ·
Flyway 12.4.0 · springdoc-openapi 3.1.0 (pinned separately — not in the BOM)

No ORM, deliberately: every write is a single call to a database function, so
JPA's value is value this design refused. `JdbcClient` only.

## Layout

```
src/main/kotlin/dev/booking/
  core/      domain types, pure rules, port interfaces — no framework imports
  repo/      JDBC adapters; SQL loaded from .sql resources at construction
  api/       controllers and wire DTOs
  sys/       clock, id generation, SQL-state translation, security, wiring
  worker/    outbox relay, Kafka consumer, scheduled sweeps
src/main/resources/sql/     33 statements, one file each
docs/backend-design/ddl/    the authoritative schema
docs/review/                the review ledger
```

The DDL under `docs/backend-design/ddl/` is the single source of truth for the
schema. Flyway migrations are **generated from it at build time**, so the two
cannot drift.

## Running it

Two workloads ship from one image, selected by Spring profile: `api` serves
HTTP, `worker` runs the relay, the consumer and the sweeps.

Required environment (no defaults — a deployment missing any of these fails at
startup rather than running misconfigured):

```
BOOKING_DB_URL  BOOKING_DB_USER  BOOKING_DB_PASSWORD  BOOKING_OIDC_ISSUER
```

Optional: `BOOKING_DB_POOL_SIZE` (10), `BOOKING_KAFKA_BOOTSTRAP`,
`BOOKING_KAFKA_GROUP`, `BOOKING_LIFECYCLE_TOPIC`, `BOOKING_GATE_TOPIC`.

```bash
./gradlew build                                    # compile, test, jar
java -jar build/libs/booking-service-0.1.0.jar --spring.profiles.active=api
```

## Tests

72 tests in three tiers: pure unit tests on domain rules, stubbed unit tests for
orchestration (hand-written fakes, no mocking framework), and 24 integration
tests against a real PostgreSQL.

```bash
./gradlew test
```

Integration tests start PostgreSQL 18 via Testcontainers by default. Where Docker
cannot publish ports, point them at an existing database instead:

```bash
./gradlew test \
  -PBOOKING_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/booking_it \
  -PBOOKING_TEST_DB_USER=postgres -PBOOKING_TEST_DB_PASSWORD=...
```

**Known gaps, from the review:** NF4's concurrency property and R14's DST
correctness were verified by hand during design and have **no automated test** —
every test fixture uses UTC, which never observes DST. Neither Kafka adapter is
exercised by any test. See `TSQ-001`, `TSQ-002` and `TSQ-004`.

## Documents

| File | What it is |
|---|---|
| `docs/backend-design/requirements.md` | Locked decisions, 14 use cases, 33 rules, open questions with deciders |
| `docs/backend-design/data-design.md` | Entity model, engine choice, write paths, index plan |
| `docs/backend-design/architecture-design.md` | Components, sequence diagrams, decisions, event catalog |
| `docs/backend-design/stack-selection.md` | Candidate matrix, ecosystem check, pinned versions with sources |
| `docs/backend-design/design-review.html` | All four rendered as one page (generated) |
| `docs/review/review.md` | The review ledger — findings, scores, method |

Both HTML pages are generated views. Regenerate rather than edit:

```bash
# design review page — run from docs/backend-design
(cd docs/backend-design && npm install && node build-review.mjs)

# review ledger page — run from the repository root
node ~/.claude/skills/reviewing-code-by-aspect/scripts/render_review.mjs \
  docs/review/review.md docs/review/review.html
```
