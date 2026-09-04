# Domain context

Facts about this system that its code cannot state, for agents and
reviewers. Every line carries provenance — `owner (date)`, `repo: <path>`,
`probe (date): <what was run>`, or `unknown`. This file describes the
system as it is; intended behavior lives in requirements documents, and
this file's own history lives in git. Keep the section names and order.

## 1. System in one paragraph

A backend service where providers (businesses) publish when their resources
— staff, rooms — are bookable and customers reserve time against that
published availability; it owns the scheduling half of the problem only,
publishing every booking transition to a message queue and consuming
decisions made elsewhere, and it takes no money and sends no messages. It
is an **example artifact produced by a chain of authoring skills and
reviewed once; it has never been deployed and is published with its review
findings unfixed.** — owner (2026-09-04), repo: `README.md`
The core path is anything that moves a booking's state or the capacity it
holds: create, cancel, reschedule, gate resolution, expiry, auto-complete,
and the outbox event each of those writes. Availability search, listings and
provider setup feed that path but move no booking. — repo: `docs/backend-design/data-design.md` §4

## 2. Module map

| Module | Path | Purpose (one line) | Role |
|---|---|---|---|
| `booking-api` | `src/main/kotlin/dev/booking/{api,core/booking,core/availability,core/listing,core/management,repo,sys}/` | HTTP API: availability search, booking create/cancel/reschedule/approve/decline/attendance, provider onboarding, listings, internal contacts lookup | core path |
| `booking-worker` | `src/main/kotlin/dev/booking/{worker,core/outbox,core/gate,core/sweep}/` | Background workload: outbox relay to Kafka, gate-resolution consumer, hold-expiry and auto-complete sweeps | core path |

Both ship from one jar; which one starts is decided by the active Spring
profile (`api` / `worker`), and the profile split is the real module split.
The worker's three jobs share one deployable and are one module, not three.
— owner (2026-09-04), repo: `src/main/kotlin/dev/booking/BookingApplication.kt:6-13`

Core path: `booking-api` (`POST /v1/bookings` → `fn_create_booking`) →
PostgreSQL writes session, booking, transition and outbox row in one
transaction → `booking-worker` relay publishes `booking-lifecycle-v1`;
inbound `booking-gate-resolution-v1` → `booking-worker` →
`fn_transition_booking`. — repo: `api/BookingController.kt:34-54`, `sql/booking/create_booking.sql`, `core/outbox/OutboxRelay.kt:23-31`, `core/gate/GateResolutionService.kt:34-41`

`docs/backend-design/build-review.mjs` and its `package.json` are local
documentation tooling, not a module. — owner (2026-09-04)

## 3. Documents and their scope

| Document | Covers | Fresh as of | Note |
|---|---|---|---|
| `README.md` | The repository as a whole: what the artifact is, stack, layout, build and run, review outcome | HEAD `41622df`, 2026-08-24 | The only document describing the code as it is; cited for module purpose, stack and "never deployed" |
| `docs/backend-design/requirements.md` (r5) | Intended behavior of the whole service, plus the owner's rulings of 2026-08-24 (LD1–LD18, R1–R33, PD rulings) | 2026-08-24 | Requirements are intended, not current — cited only for owner-decided constants and scope exclusions, never as evidence of what the code does |
| `docs/backend-design/data-design.md` | The PostgreSQL schema, the exclusion-constraint invariant, the ten write paths | 2026-08-24; the `ddl/` it describes is the build's migration source | Cited for schema facts |
| `docs/backend-design/architecture-design.md` | Component split, deployment topology, event catalog, API inventory | 2026-08-24 | Describes a deployment that has never run and disagrees with the code in at least two places — candidate only, never cited for runtime facts |
| `docs/backend-design/stack-selection.md` | Language, framework, broker and version pins | 2026-08-24; agrees with `build.gradle.kts` | Cited for pins |
| `docs/backend-design/ddl/*.sql` | The authoritative schema and every plpgsql write path | HEAD | Not documentation — source code; Flyway migrations are generated from it at build time |
| `docs/backend-design/data-dictionary.yaml` | Column-level schema | Generated from `COMMENT ON` | Generated artifact; `ddl/` is cited instead |
| `docs/review/review.md` | Findings, scores and declared assumptions of one full review @ `61041ed` | 2026-08-24 @ `61041ed`; no code has changed since, so it still describes HEAD's code | Prior review ledger — its claims are candidates to confirm, never a source to cite |
| `docs/backend-design/design-review.html`, `docs/review/review.html` | Rendered views of the above | Generated | Never cited |

Scope and handling of every row above confirmed by owner (2026-09-04).
A fact is cited to a document only within that document's confirmed scope.

## 4. Operating envelope

- Instances / replicas: none running — the service has never been deployed.
  A reviewer judges concurrency against the designed topology: at least two
  `booking-api` and two `booking-worker` instances, with both sweeps active
  on every worker replica. — owner (2026-09-04)
- Tenants: many providers in one PostgreSQL schema; a provider is the tenant
  and scoping is application-side, not row-level security. — repo: `src/main/resources/sql/provider/administered_provider_ids.sql`
- Throughput: designed for ~500 providers, ~2 000 resources, ~10 000
  bookings/day, peak ~50 req/s availability search and ~10 req/s booking
  writes; **never measured**. — owner (2026-09-04), design targets from `requirements.md` NF2
- Dataset sizes / retention: no production data has ever existed. A retention
  period of 24 months after a customer's last booking is a design intent with
  no code behind it — no retention or erasure job exists in the repository. — repo: HEAD (no such job), `requirements.md` NF5/PD9
- Latency budget / batch windows: designed for availability search p95
  < 300 ms and booking writes p95 < 500 ms; never measured. Fixed by
  configuration, not by load: relay scan every 500 ms in batches of 100,
  hold-expiry sweep every 30 s in batches of 500, auto-complete sweep hourly.
  A single availability search may span at most 62 days. — owner (2026-09-04) for the budget; repo: `src/main/resources/application.yaml:48-56`, `core/availability/AvailabilityDomain.kt:30`

## 5. Trust and deployment model

- Authn / authz location: **in-code**. JWT bearer tokens are verified by the
  Spring OAuth2 resource server against a required issuer with no default, so
  a deployment without one fails at startup; every path except `/actuator/**`
  requires an authenticated token, including `/internal/**`. Authorization is
  application-side and derived from `provider_admin` membership resolved from
  the database, never from a token claim or role. — repo: `sys/SecurityConfig.kt:22-29`, `core/booking/BookingAuthorization.kt:29-37`
- Trusted client decisions: none that change a rule. Actor capacity is derived
  from provider membership, so a caller cannot elect to be a provider and so
  cannot bypass the minimum lead time; an explicit resource preference is
  honoured but re-validated in the database. The client does supply contact
  details, which fill only the gaps the token's claims leave. — repo: `core/booking/BookingService.kt:30-38`, `api/BookingController.kt:111-115`
- Secrets: database URL, user and password; the OIDC issuer URI; optionally
  Kafka bootstrap servers, consumer group and the two topic names — all read
  from environment variables at startup, none with a default except the
  optional ones. No secret is committed. — repo: `src/main/resources/application.yaml:10-46`
- Runtime platform (actual, not supported): **none**. The service has never
  run outside its own test suite. No Dockerfile, Kubernetes manifest, ingress
  configuration or CI pipeline is committed, so nothing in the repository
  fixes where or how it would run; Kubernetes with two deployments from one
  image is a design intent only. — owner (2026-09-04), repo: HEAD (128 tracked files, no deployment artifact)

## 6. Invariants

- A resource hosts at most one occupied session at any instant, buffers
  included, and this is held by a GiST exclusion index rather than by
  application code — never a check-then-write. Enforced by the schema
  (Shared), relied on by both modules. — repo: `docs/backend-design/ddl/01-create-tables.sql` `session_no_overlap`
- Within one session, HELD plus CONFIRMED bookings never exceed the session's
  capacity; the guard is one conditional `UPDATE`, so two racers for the last
  unit produce exactly one winner. Enforced by the schema. — repo: `docs/backend-design/data-design.md` §4.1
- A committed state transition always has exactly one outbound event, and no
  event exists for a transition that did not commit: a composite foreign key
  plus a unique constraint on `(booking_id, transition_sequence_no)`. Enforced
  by the schema; the reverse direction rests on `fn_transition_booking` being
  the only writer of `booking_state_id`. — repo: `docs/backend-design/data-design.md` §4.3
- No event on any topic carries a name, email, phone or free-text reason —
  identifiers only. Enforced by `fn_emit_event`, which is the only writer of
  event payloads. — repo: `docs/backend-design/ddl/03-functions.sql:163-215`
- A booking that is not the caller's own and not owned by a provider the
  caller administers is answered as absent, never as forbidden. Enforced by
  `booking-api`; **not** enforced on `/internal/v1/bookings/{ref}/contacts`
  (see that module's hazards). — repo: `core/booking/BookingDomain.kt:126-133`
- A permitted state transition is a row in `booking_transition_rule`, not a
  branch: anything absent from the seed is refused. Enforced by the schema. — repo: `docs/backend-design/ddl/04-seed-lookups.sql:43-63`

## 7. Module context

One subsection per module, in map order, then **Shared**.

### booking-api (`src/main/kotlin/dev/booking/{api,core/booking,core/availability,core/listing,core/management,repo,sys}/`)

- **Business context**: Serves every customer- and provider-facing operation
  and decides *policy* only — which gate a booking must pass, which resource
  to pick — delegating every invariant to one plpgsql call. — repo: `core/booking/BookingService.kt:7-14`
- **External parties**:
  - *External IdP (OIDC)*, read-only, no mutating operation. Identity is the
    token's `sub`; the profile's name, email and phone are token claims
    written on first use and never re-read, so a changed email never reaches
    the profile the contacts endpoint serves — intended or not, `unknown`. — repo: `api/CurrentActor.kt:29-41`, `sql/customer/upsert_customer.sql:7-8`
  - *PostgreSQL* (Shared): outcomes are completed, or rejected pre-flight as
    one of nine `BK001`–`BK009` domain rules. Create is idempotent on the
    caller's `Idempotency-Key`: a replay returns the original booking and
    emits no second event. Any other SQLSTATE is answered `500`. — repo: `core/booking/BookingDomain.kt:47-56`, `api/ApiExceptionHandler.kt:39-45`
  - *notification-service*: sole intended caller of the contacts endpoint; not built, and no caller has ever existed. — owner (2026-09-04)
- **Money, units and ownership**: No money at all — no price, currency,
  payment, invoice or refund in the model, the API or any event. Durations
  and policy windows are **minutes**, horizon and grace **days**, one booking
  is one unit of capacity. Instants are **UTC**; recurring rules are
  wall-clock times in the provider's IANA timezone. Where a booking and the
  current service row disagree, the **session** is authoritative. — `requirements.md` R2/R4–R8/R14/R21/R25/LD15 (owner rulings, 2026-08-24)
- **States and transitions**: `HELD`, `CONFIRMED`, `CANCELLED`, `DECLINED`,
  `EXPIRED`, `COMPLETED`, `NO_SHOW`; the last five terminal, and only `HELD`
  and `CONFIRMED` hold capacity. A `HELD` booking names exactly one gate,
  today only `AWAITING_PROVIDER_APPROVAL`. Resources, services and rules are
  deactivated or ended, never deleted. — repo: `docs/backend-design/ddl/04-seed-lookups.sql:4-14`
- **Hazards**:
  - The internal contacts endpoint performs no object-level authorization:
    any end-user token reads any booking's name, email and phone (SEC-001) —
    **accepted by owner (2026-09-04)**: the artifact is published with its
    review findings intact as evidence of what the review caught, not deployed.
  - Reschedule discards the caller's requested new resource and answers
    success for a move that did not happen (LOG-001) — **accepted by owner
    (2026-09-04)**, same reason.
  - `POST /v1/providers` has no authorization check and makes its caller the
    provider's first administrator, so provider tenancy is self-service to any
    holder of an issuer token — **declined by owner (2026-09-04)**. — repo: `core/management/ProviderSetup.kt:61-66`
- **Not exercised**: the internal contacts endpoint has no caller and never
  has; `resource.idp_subject` is written at creation and read by nothing, so a
  resource-linked user has no read access anywhere; the reschedule plumbing
  for a resource change reaches the database and is called by nobody. — repo: `api/InternalController.kt:27`, `repo/JdbcProviderSetupRepository.kt:51`, `api/BookingController.kt:88`

### booking-worker (`src/main/kotlin/dev/booking/{worker,core/outbox,core/gate,core/sweep}/`)

- **Business context**: Runs the three background jobs that keep the booking
  record true outside a request — the outbox relay, the gate consumer, and the
  hold-expiry and auto-complete sweeps. It decides nothing the API does not:
  every state change goes through the same database functions. — repo: `worker/WorkerSchedules.kt`, `core/sweep/Sweeps.kt:6-9`
- **External parties**:
  - *Kafka producer* → `booking-lifecycle-v1`, keyed by booking reference,
    blocking on the acknowledgement before the row is marked dispatched.
    Outcomes: accepted, or failed and retried by the next scan, never dropped.
    Rows are claimed without a lock, so **an accepted publish whose
    acknowledgement was lost is republished**: delivery is at-least-once and
    consumers must dedupe on `eventId`. Only the oldest undispatched event per
    booking is claimed, so a stuck event delays that booking alone. — repo: `worker/KafkaEventPublisher.kt:30-44`, `sql/outbox/claim_due_events.sql:1-10`
  - *Kafka consumer* ← `booking-gate-resolution-v1`. Outcomes per message:
    applied; duplicate, deduped on `inbox_message.message_id` so a redelivery
    changes nothing; rejected — the booking is no longer held on that gate,
    nothing changes and `BookingGateResolutionRejected` is published carrying
    its actual state so the sender can compensate; or unknown booking, which
    changes nothing and **publishes nothing at all** — whether that is right
    is `unknown`. A late resolution never resurrects a terminal booking. — repo: `core/gate/GateResolutionService.kt:34-83`
  - No producer for the consumed topic and no consumer of the published one has
    ever existed; the published stream is an **unpublished draft**, so a breaking
    change to it is free until a consumer exists. — owner (2026-09-04)
- **Money, units and ownership**: No money. Units as for `booking-api`; the worker adds only delivery timings and a capped retry backoff, both in §4. — repo: `core/outbox/OutboxDomain.kt:31-43`
- **States and transitions**: An outbox event is `PENDING_DISPATCH →
  DISPATCHED`, never dropped; repeated failure is meant to be alertable and no
  alerting exists in the repository. The sweeps drive `HELD → EXPIRED` and
  `CONFIRMED → COMPLETED` (system-completed, never overriding a provider's
  mark); both are idempotent and every replica may sweep at once. — repo: `docs/backend-design/data-design.md` §4 paths 6–9
- **Hazards**:
  - The gate consumer's comment states a malformed message is rethrown so the
    container's error handler can dead-letter it; no such handler is
    configured, so the framework retries a few times and skips the record —
    once a producer exists, a compensating decision the sender believes was
    delivered is dropped silently (ERR-002) — **declined by owner
    (2026-09-04)**. — repo: `worker/GateResolutionListener.kt:20-23`
- **Not exercised**: nothing on this module's inbound or outbound edge has
  ever carried a message; `inbox_message` and `BookingGateResolutionRejected`
  have never been used; the `AWAITING_PAYMENT` gate is designed but not seeded
  and not reachable; neither Kafka adapter is referenced by any test. — repo: `docs/backend-design/ddl/04-seed-lookups.sql:13-16`, `src/test/`

### Shared (`docs/backend-design/ddl/`, `src/main/resources/sql/`, and the parties both modules use)

- **PostgreSQL 18.6 (floor 14), single primary, `btree_gist` required** — the
  schema is the authority for correctness, not the application. 21 tables;
  every multi-statement write is one plpgsql function, one round trip, one
  transaction, and the `ddl/` directory is the build's Flyway migration source
  so schema and migrations cannot drift. Both modules connect through one
  Hikari pool of 10 by default. — repo: `build.gradle.kts:64-80`, `docs/backend-design/data-design.md` §3
- Availability is **computed, never materialised**: the only rows describing
  time are recurring rules, one-off exceptions and real occupancy, so editing
  a rule cannot leave stale slots behind, and there is no way to mark a slot
  taken outside of booking. `BLOCK` exceptions beat both rules and `OPEN`.
  A window may not cross midnight. — repo: `docs/backend-design/data-design.md` §1.2
- Domain rejections cross the boundary as custom SQLSTATEs `BK001`–`BK009`
  and keep their identity from the constraint to the HTTP response; the
  application maps them, and any other SQLSTATE is a defect. — repo: `core/booking/BookingDomain.kt:47-56`
- **External IdP (OIDC)**: used by `booking-api` only; issuer supplied by
  environment, no default, startup fails without it. This service stores no
  credential and issues no token. — repo: `sys/SecurityConfig.kt:9-16`
- **Kafka topics** `booking-lifecycle-v1` (published) and
  `booking-gate-resolution-v1` (consumed), both keyed by booking reference,
  both named by environment variables with those defaults. Neither has a
  counterpart in existence. — owner (2026-09-04), repo: `src/main/resources/application.yaml:42-46`
- Migrations run at every pod's startup in both workloads, from the same jar,
  with no leader gate or migration job in the repository. — repo: `src/main/resources/application.yaml:16-18`

## 8. Provenance and freshness

- Mined against commit `41622df` on 2026-09-04. The code is unchanged since
  `61041ed`, the hash the review ledger was written against.
- Last owner confirmation: 2026-09-04, by the repository owner.
- Owner review of `repo:` lines: not yet reviewed — the owner answered the two
  interview batches and ruled on the hazards above; the remaining `repo:`
  lines stand as base assumptions.
- Open unknowns:
  - Whether the customer profile snapshotting the IdP claims at first use, and
    never re-reading the token afterwards, is intended behavior or a defect
    (`booking-api`, External parties).
  - Whether a gate resolution naming a booking this service has never seen
    should be answered with a rejection event rather than with silence
    (`booking-worker`, External parties).
