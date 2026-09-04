# Code Review Ledger

Method: aspect-scored review — severity sets the band, count refines within
it; scores derive from open findings by fixed arithmetic. This document is
the persistent ledger: scope, assumptions, overrides, open findings, archive,
history. Finding IDs are stable and never reused. Keep the section names and
order of this template — incremental reviews and future maintainers navigate
by them.

## Summary — 2026-08-24 @ 61041ed (full review, 10 aspects, scope: whole repo)

**Worst aspects: Security and Logic correctness, both at 3/10.** Two criticals,
in different aspects, both on reachable paths:
- **SEC-001** — the internal contact endpoint performs no object-level
  authorization: any authenticated caller holding any booking reference
  retrieves that booking's name, email and phone.
- **LOG-001** — reschedule accepts and validates a new resource reference,
  carries it through four layers, then discards it at the controller; the
  caller receives success for a move that did not happen.

First full pass; no prior baseline, so every aspect ran a complete review. All
ten aspect scores are new. Reviewing was performed by six clean-context
subagents fanned out by reading pass, because this session authored the code —
the orchestrating session only merged findings, adjudicated severity and
computed scores.

Two findings were merged during the merge step: the reschedule defect was
raised independently by the structure reviewer as a minor readability issue and
by the logic reviewer as a critical; it is one defect with one fix, homed in
LOG at critical severity and cross-referenced from RDB. The "client mistakes
logged at ERROR" symptom was likewise merged into ERR-001, its root cause.

| Aspect | Score | Worst open | Open findings |
|---|---|---|---|
| Logic correctness (LOG) | **3** | critical | LOG-001 C, LOG-002 m |
| Error handling (ERR) | **4** | major | ERR-001 M(w), ERR-002 M |
| Security (SEC) | **3** | critical | SEC-001 C |
| Code pattern (PAT) | **10** | — | — |
| Concurrency safety (CON) | **4** | major | CON-001 M(w), CON-002 M |
| Contract & compatibility (CMP) | **4** | major | CMP-001 M(w), CMP-002 M |
| Logging (LGG) | **9** | minor | LGG-001 m |
| Test quality (TSQ) | **4** | major | TSQ-001 M, TSQ-002 M, TSQ-003 M(w), TSQ-004 M, TSQ-005 m |
| Type & null safety (TYP) | **10** | — | — |
| Readability & maintainability (RDB) | **7** | minor | RDB-001 m(w), RDB-002 m, RDB-003 m, RDB-004 m |

Rows stay in catalog order (the History table's column order), never sorted
by score — so any two reviews line up row-for-row. The worst aspects are
named in the prose above, not by reordering the table.

Average (trend only, never the headline): **5.8**.

## Review scope

Whole repository at 61041ed — 123 tracked files: 53 Kotlin production sources,
17 Kotlin test sources, 33 `.sql` resources, 4 DDL files, the Gradle build,
`application.yaml`, and the four design documents under `docs/backend-design/`.
Working tree was clean at review time; no files were excluded from review for
dirtiness.

## Declared assumptions (scout pass @ 61041ed, 2026-08-24)

Evidence-based detections; each states the weakest claim its evidence
supports. Override any of these in User overrides — overrides persist and
bind future reviews.

- **Language(s)**: Kotlin 2.3.21 (70 files: 53 main, 17 test), SQL (37 files:
  33 resources + 4 DDL), Kotlin DSL build (2), YAML (2), Markdown (4), one
  Node generator script.
- **House pattern standard**: repo convention, uniformly applied — `core/`
  holds domain types, pure rules and port interfaces with no framework
  imports; `repo/` holds JDBC adapters whose SQL is loaded from `.sql`
  resources at construction; `api/` holds controllers and wire DTOs; `sys/`
  holds configuration, clock, id generation and exception translation;
  `worker/` holds Spring adapters. Evidence: zero Spring/Jakarta imports under
  `core/`, zero layer-skip imports from `api/` or `worker/` into `repo/`, all
  30 `sql.load()` calls in constructor position.
- **Auth model**: in-code OAuth2 resource server; JWT verified by Spring
  Security, issuer required with no default. Tenant scoping is application-side
  in `core/booking/BookingAuthorization.kt`. Evidence: `sys/SecurityConfig.kt`,
  `application.yaml`.
- **Deployment model**: assumed Kubernetes, two workloads (`api`, `worker`)
  from one image; basis: Spring profiles in code plus the design documents —
  **capability evidence only**. No Dockerfile, manifests, ingress config or CI
  are committed, so the repo does not prove where or how this runs. SEC-001 and
  CMP-001 both note where their consequence depends on this assumption.
- **Consumed contract surfaces**: published Kafka stream
  `booking-lifecycle-v1` (payload built by `fn_emit_event` in the DDL);
  consumed topic `booking-gate-resolution-v1`; 16 HTTP endpoints whose intended
  inventory is `docs/backend-design/architecture-design.md` section 5.
- **Tests**: three declared tiers — pure unit, stubbed unit with hand-written
  fakes (no mocking framework, confirmed absent from the build), and `*IT`
  integration tests against a real PostgreSQL via Testcontainers or an external
  database named by `BOOKING_TEST_JDBC_URL`. 72 tests, all passing at this hash.
  Gaps found are recorded as TSQ findings, not treated as intentional.
- **Exclusions**: `docs/backend-design/data-dictionary.yaml` and
  `docs/backend-design/design-review.html` are generated artifacts;
  `gradle/wrapper/` is vendored; `build/` is untracked.
- **Scoring interpretation**: within the worst open tier each finding counts
  one unit (widespread counts two; doubling never applies to criticals);
  score = band start − step × (units − 1), clamped at the band floor
  (critical 3/−1/0, major 6/−1/4, minor 9/−0.5/7). The first unit is free —
  it sets the band.

## User overrides

_None yet._

## Open findings

Severity: C = critical, M = major, m = minor; (w) = widespread (counts
double). All findings open @ 61041ed. Finding bodies stay pinned to their
discovery hash. Aspect sections stay in catalog order.

---

### Logic correctness (LOG) — 3/10 · 2 open

**LOG-001 · C · isolated — Reschedule silently discards the caller's requested new resource**
- `src/main/kotlin/dev/booking/api/BookingController.kt:88` — hardcodes `newResourceId = null`
- `src/main/kotlin/dev/booking/api/BookingDtos.kt:45` — `RescheduleRequest.resourceRef` declared on the wire
- Failing input: `POST /v1/bookings/{ref}/reschedule` with
  `{"startsAt": "<valid future time>", "resourceRef": "<a different eligible resource>"}`
  for a booking currently on resource A. UC8 specifies "the booking id and a new
  start time (and optionally a new resource)". Actual: the booking moves to the
  new time and stays on resource A, with a success response. `TransitionResponse`
  carries no resource field, so the client cannot detect the divergence.
- The plumbing beneath the controller is complete and unused:
  `core/booking/BookingLifecycleService.kt:72,79` accepts and forwards it,
  `repo/JdbcBookingLifecycle.kt:73` binds it, and `ddl/03-functions.sql:569`
  applies `COALESCE(p_new_resource_id, v_resource)` — the database function was
  written specifically to honour a resource change.
- Zero reschedule invocations exist in the test suite (verified), so no test
  protects any part of UC8.
- Cross-ref: RDB — raised independently there as a dead wire-contract field.
  One defect, one fix, homed here; deducted once.

**LOG-002 · m · isolated — Dead branch in the retry-backoff overflow guard**
- `src/main/kotlin/dev/booking/core/outbox/OutboxDomain.kt:36-43`
- `exponent = attemptCount.coerceIn(0, 30)` then `1L shl exponent` yields at most
  2^30, so the `delaySeconds <= 0` disjunct guarding against overflow is
  unreachable for every input. The `coerceIn` is the real protection; the
  disjunct duplicates it ineffectually and reads as if it were load-bearing.

---

### Error handling (ERR) — 4/10 · 2 open

**ERR-001 · M · widespread — Client input errors that are not bean-validation surface as 500 defects**
- `src/main/kotlin/dev/booking/api/ApiExceptionHandler.kt:25,39-45` — only
  `MethodArgumentNotValidException` is recognised; everything else reaches the
  `Exception::class` catch-all, which answers 500 `INTERNAL`
- `src/main/kotlin/dev/booking/core/listing/Listing.kt:37-38` via
  `api/ListingController.kt:27-31,41-45` — `Page.init`'s `require()` throws
  `IllegalArgumentException` on `?limit=0` or `?limit=99999`, straight from an
  unclamped request parameter
- `repo/JdbcAvailabilityAdminRepository.kt`, `repo/JdbcProviderSetupRepository.kt`
  — neither routes through `translatingRuleViolations` (verified: 0 uses in
  either), so DDL CHECK violations reachable from client fields
  (`ddl/01-create-tables.sql:94` timezone, `:238` rule window, `:263` exception
  window) arrive as generic `DataAccessException`
- Every `@PathVariable UUID` and `@RequestParam Instant` across all controllers —
  a malformed value raises `MethodArgumentTypeMismatchException`, caught by the
  same catch-all before Spring's own 400 mapping applies
- Failing input: `GET /v1/bookings?limit=0` with a valid token returns 500 with
  a stack trace logged at ERROR, where 400 is the correct answer.
- Cross-ref: LGG — the same paths log routine client mistakes at ERROR with a
  full stack trace, indistinguishable from a real defect to an operator. Same
  root, same fix, deducted once here.

**ERR-002 · M · isolated — The documented dead-letter contract for the gate consumer does not exist**
- `src/main/kotlin/dev/booking/worker/GateResolutionListener.kt:20-23` — the
  class comment states a malformed message "is logged and rethrown so the
  container's error handler can dead-letter it rather than silently discarding a
  decision another service believes it made"
- Verified: zero `try`/`catch` in `onMessage`, and zero
  `CommonErrorHandler` / `DeadLetterPublishingRecoverer` beans anywhere in
  `src/main` or `application.yaml`
- Without that bean the listener falls back to the framework default: a few
  immediate retries, then the record is logged by the framework and skipped —
  precisely the silent discard the comment claims the design avoids. The channel
  is documented as carrying compensating decisions (a refund against an expired
  hold), so a dropped message has financial consequence once a producer exists.

---

### Security (SEC) — 3/10 · 1 open

**SEC-001 · C · isolated — Internal contact endpoint has no object-level authorization; any authenticated caller can retrieve any tenant's PII**
- `src/main/kotlin/dev/booking/api/InternalController.kt:28` — `contacts(bookingRef)`
  takes no caller identity at all
- `src/main/kotlin/dev/booking/core/listing/ListingService.kt:45` — passes the
  reference straight through with no ownership or tenancy check
- `src/main/resources/sql/listing/booking_contacts.sql:12` — the only predicate
  is `WHERE b.public_ref = :bookingRef::uuid`; no caller-scoping join
- `src/main/kotlin/dev/booking/sys/SecurityConfig.kt:25-28` — `/internal/v1/**`
  is not distinguished from `/v1/**`; both fall under `anyRequest().authenticated()`
- Named path: any subject holding an ordinary end-user JWT — the exact population
  every public endpoint accepts → `GET /internal/v1/bookings/{bookingRef}/contacts`
  → `displayName`, `email`, `phone`, `cancellationReason` for **any** booking,
  owned or not. Verified: zero `hasAuthority`, `hasRole`, `SCOPE_`, `X509` or
  client-credentials checks exist anywhere in `src/main`.
- `docs/backend-design/architecture-design.md:256` documents this endpoint as
  requiring "service-to-service auth" and never publicly routed. Neither is
  implemented; the second is a deployment claim no committed manifest supports
  (see the deployment assumption above). R18's tenant scoping — enforced in
  `BookingAuthorization`, `ListingService.providerCalendar`,
  `ProviderSetupService.forProvider` and `AvailabilityManagementService.forProvider`
  — is simply absent here. Classic IDOR (CWE-639) on a PII-bearing surface.
- This endpoint exists to compensate for PD17 keeping PII out of the event
  stream; as written it returns that PII to a wider audience than the events
  would have.

---

### Code pattern (PAT) — 10/10 · 0 open

No findings. Sweep backing the negative claim: all 53 Kotlin production files,
all 33 `.sql` resources, the four DDL files and `build.gradle.kts` read in full;
checked for framework imports under `core/` (none), layer-skip imports into
`repo/` from `api/` or `worker/` (none), `sql.load()` outside constructor
position (none of 30), and SQL identifier splicing (none — all 33 files use
named `:param::type` binds exclusively).

---

### Concurrency safety (CON) — 4/10 · 2 open

**CON-001 · M · widespread — A shared, non-thread-safe PRNG backs every identifier the service mints**
- `src/main/kotlin/dev/booking/sys/TimeAndIdConfig.kt:21` — `RandomGenerator.getDefault()`
  registered as a singleton bean
- `src/main/kotlin/dev/booking/sys/IdGenerator.kt:26-33` — `UuidV7Generator.newId()`
  calls `nextInt` then `nextLong` on that shared instance with no synchronization
- Consumers on hot paths: `sys/CorrelationIdFilter.kt:30` (every request without
  an inbound correlation header), plus `BookingService`, `BookingLifecycleService`,
  `AvailabilityManagementService`, `ProviderSetupService` and
  `GateResolutionService` — all singletons invoked from concurrent virtual
  threads (`spring.threads.virtual.enabled=true`)
- `getDefault()` returns `L32X64MixRandom`, whose own documentation states
  instances "are not thread-safe... designed to be split, not shared, across
  threads"; its state fields are plain and non-volatile.
- Demonstrated, not inferred: 64 virtual threads drawing the same
  `nextInt`/`nextLong` pair the generator uses produced **709 duplicate draws in
  12,800** on this JDK (25.0.2). Two bookings minted in the same millisecond can
  therefore receive byte-identical `public_ref` values; the unique constraint at
  `ddl/01-create-tables.sql:359` then fails one otherwise-valid request.
  Request-scoped and loud, hence major rather than critical.

**CON-002 · M · isolated — Unordered two-row lock acquisition in `fn_reschedule_booking` can deadlock two concurrent reschedules**
- `docs/backend-design/ddl/03-functions.sql:579-598` — releases the old session
  row then takes the new one, both `session` rows, in one transaction, in
  fixed old-then-new order with no global row ordering
- Interleaving: two reschedules swapping slots on one resource. T1 locks session
  S2 on release; T2 locks S1 on release; T1's `INSERT ... ON CONFLICT` targets S1
  and blocks on T2; T2's targets S2 and blocks on T1. Circular wait; Postgres's
  detector kills one transaction after `deadlock_timeout`. The surviving request
  succeeds, the other fails with a deadlock error rather than a domain rejection.

---

### Contract & compatibility (CMP) — 4/10 · 2 open

**CMP-001 · M · widespread — Migrations apply at every pod's startup across two independently scaled workloads, with no rolling-deploy coordination**
- `src/main/resources/application.yaml:16-18` — `flyway.enabled: true`, no
  profile-specific override; verified that no `@Profile` gates Flyway in either
  workload
- `build.gradle.kts:64-80` — one generated migration resource set, consumed
  identically by both workloads from the same jar
- The first pod of *either* workload to start on a new version advances the
  schema for the whole deployment, while old-version pods of the other workload
  keep serving. Flyway's own lock prevents concurrent corruption of the migration
  itself; it does not address the rolling window. No migration job, leader gate,
  or expand/contract discipline exists in the repo. Not yet triggered — V1–V4 are
  an additive baseline — but the delivery mechanism carries the hazard.
- Consequence depends on the deployment assumption above.

**CMP-002 · M · isolated — The availability endpoint is documented as unauthenticated but the filter chain requires a token**
- `docs/backend-design/architecture-design.md:242` — "Unauthenticated browsing
  permitted", stated for this endpoint alone in the API inventory
- `src/main/kotlin/dev/booking/sys/SecurityConfig.kt:26-27` — only `/actuator/**`
  is permitted; `anyRequest().authenticated()` covers the availability path
- A client built to the published contract calls
  `GET /v1/providers/{ref}/availability` without a bearer token and receives 401
  before reaching the controller. Implementation and documented contract
  disagree; whichever is authoritative, the other is wrong today.

---

### Logging (LGG) — 9/10 · 1 open

**LGG-001 · m · isolated — Worker-side logs never carry a correlation id**
- `src/main/kotlin/dev/booking/sys/CorrelationIdFilter.kt` — a
  `OncePerRequestFilter`, so it runs only on the HTTP pipeline
- `core/outbox/OutboxRelay.kt:45-48`, `worker/WorkerSchedules.kt`,
  `worker/GateResolutionListener.kt:40-43` — every worker log line renders the
  correlation field as `-` under the pattern at `application.yaml:71-73`
- The gate listener has an available surrogate (`resolution.messageId`) that is
  never placed in MDC, so a saga cannot be followed across the consumer and the
  booking-side transitions it causes.

---

### Test quality (TSQ) — 4/10 · 5 open

**TSQ-001 · M · isolated — NF4's concurrency guarantee has no test exercising concurrent access**
- `src/test/kotlin/dev/booking/repo/JdbcBookingRepositoryIT.kt:176` — the nearest
  test calls `create()` twice sequentially on one thread
- Verified: zero concurrency primitives anywhere in `src/test` (swept for thread,
  executor, coroutine, async, parallel, latch, future, concurrent — 0 matches).
  The sequential test proves the exclusion constraint rejects a *later*
  conflicting insert, not that it survives two *simultaneous* ones — which is
  the failure mode NF4 names as "the single most important correctness property
  of the service".

**TSQ-002 · M · isolated — R14 (DST-correct availability) is untested and the fixtures structurally exclude it**
- Provider fixtures at `JdbcBookingRepositoryIT.kt:68`, `BookingLifecycleIT.kt:78`,
  `AvailabilityManagementIT.kt:71`, `ProviderOnboardingIT.kt:86` — verified: the
  only timezone literal present in the entire test suite is `'UTC'`
- UTC never observes DST, so no test can observe an offset change across a
  transition date even incidentally. The DST-sensitive logic lives in
  `fn_search_availability`; `AvailabilityWindowTest` covers only window-span
  bounds. Zero DST or non-UTC zone tokens in `src/test` (verified).

**TSQ-003 · M · widespread — The four integration classes share one database and wipe tables unscoped to their own fixtures**
- `src/test/kotlin/dev/booking/repo/TestDatabase.kt:32-47` — one lazy singleton
  `DataSource` for the whole run
- `JdbcBookingRepositoryIT.kt:53-63`, `BookingLifecycleIT.kt:67-73`,
  `AvailabilityManagementIT.kt:58-64`, `ProviderOnboardingIT.kt:68-75` — each
  runs table-wide `DELETE FROM` in `@BeforeTest` with no scoping to its own rows
- No `junit-platform.properties` exists and `tasks.test` sets no parallel
  configuration, so classes happen to run sequentially today. Nothing in the test
  code enforces that: enabling parallel class execution, or two runs pointed at
  the same `BOOKING_TEST_JDBC_URL`, has one class's fixture wipe destroy
  another's in-flight rows.

**TSQ-004 · M · isolated — Both Kafka adapters are exercised only through fakes that assume they agree**
- `src/main/kotlin/dev/booking/worker/KafkaEventPublisher.kt:30-44` builds an
  envelope; `src/main/kotlin/dev/booking/worker/GateResolutionListener.kt:46-68`
  parses a different shape
- Verified: zero test references to either class. `OutboxRelayTest` and
  `BookingLifecycleIT` use `EventPublisher` fakes that only record calls;
  `GateResolutionServiceTest` and `BookingLifecycleIT` construct `GateResolution`
  directly in Kotlin, never through `parse()`; `ApplicationContextIT:57` disables
  the listener and asserts only that beans exist. An envelope-shape change or a
  field-name mismatch is caught by nothing.

**TSQ-005 · m · isolated — A test asserts nothing and its name claims more than its body checks**
- `src/test/kotlin/dev/booking/core/booking/GatePolicyTest.kt:27-32` —
  `every confirmation mode has a decided gate` iterates the enum calling
  `selectGate` with no assertion. `selectGate` is an exhaustive `when` with no
  `else`, so the property the name claims is a compiler guarantee; the test can
  only fail if the function throws, and the two preceding tests already assert
  both values.

---

### Type & null safety (TYP) — 10/10 · 0 open

No findings. Sweep backing the negative claim: every `requireNotNull`, `!!` and
`error(...)` site across 11 files traced to its guard; every enum under `core/`
diffed code-for-code and id-for-id against `ddl/04-seed-lookups.sql` and the
`RAISE ... ERRCODE` sites in `ddl/03-functions.sql` (all match); all 16 Elvis
operators checked for failure-into-default collapse (none found); `AvailabilityWindow`,
`Page` and the UUIDv7 bit layout checked against RFC 9562. One asymmetry noted
below the evidence bar and recorded as intentional rather than as a finding.

---

### Readability & maintainability (RDB) — 7/10 · 4 open

**RDB-001 · m · widespread — Provider authorization resolution duplicated across three call sites**
- `src/main/kotlin/dev/booking/core/management/AvailabilityManagement.kt:106-116`
- `src/main/kotlin/dev/booking/core/management/ProviderSetup.kt:96-106`
- `src/main/kotlin/dev/booking/core/listing/ListingService.kt:36-38`
- All three implement the same two-step rule — resolve `providerRef`, then check
  membership, else answer as absent — with no shared abstraction across two
  packages. A change to the rule requires three edits in lockstep and nothing
  enforces they stay consistent.

**RDB-002 · m · isolated — Magic integer state ids mixed with symbolic text codes inside one function**
- `docs/backend-design/ddl/03-functions.sql:261,357,360,464,473,502-506,603-611`
- `fn_transition_booking` tests the target state by text code at line 464 and by
  raw seeded integer at 502-506, for the same concept in the same function; a
  reader must open `04-seed-lookups.sql` to decode `1`/`2`/`3` at each site.

**RDB-003 · m · isolated — Shared test helper defined at the bottom of an unrelated test class's file**
- `src/test/kotlin/dev/booking/repo/AvailabilityManagementIT.kt:198-202` defines
  `object JdbcClientHolder`, consumed from `ProviderOnboardingIT.kt:48`
- `TestDatabase.kt` already exists as the home for shared integration-test
  infrastructure; a reader of `ProviderOnboardingIT` has no local clue where the
  helper comes from.

**RDB-004 · m · isolated — Terse naming and chained regex in the design-doc generator**
- `docs/backend-design/build-review.mjs:37-63,84-118,134-161`
- Pervasive single-letter identifiers combined with chained `.replace()` calls;
  the only file in scope with this naming density, against descriptive naming
  throughout the Kotlin sources.

## Credited as intentional (recorded to prevent re-litigation)

- **The broad `catch (Exception)` in `core/outbox/OutboxRelay.kt:33`** — the
  comment claims it is an integration edge where every failure has one remedy,
  and the claim was verified line-by-line: the reason is persisted via
  `recordFailure`, counted into `DispatchReport`, and logged with event id,
  booking ref and attempt number. Not an error-swallow.
- **Nine `Optional.orElse(null)` sites across six repositories** — all are
  zero-row translations on plain SELECTs, not catches around an error; each
  caller treats the resulting null as a distinct domain outcome. Not a
  benign-default collapse.
- **`core/listing/ListingService.kt:24-27` returning `emptyList()`** when a
  caller has no customer profile — documented as a deliberate business decision
  ("not an error").
- **The lease-free outbox claim in `sql/outbox/claim_due_events.sql`** — the
  comment's trade (duplicate publishes permitted, ordering preserved) was
  verified to hold: only the oldest undispatched event per booking is claimed,
  `publish` blocks on the broker acknowledgement before `markDispatched`, and
  `fn_transition_booking` locks the booking row so outbox ids for one booking
  are strictly increasing. Duplicates are possible; reordering and loss are not.
- **`GateResolutionListener.parse`'s `requireNotNull` guards on external input** —
  loud failure on a malformed message is the documented intent. Note this credits
  only the *loud failure* half; the dead-letter half is ERR-002.
- **`ConfirmationMode.valueOf` used bare** at `JdbcBookingLifecycle.kt:44` and
  `JdbcBookingRepository.kt:41`, unlike `BookingState.ofCode`'s custom divergence
  message — no currently reachable input triggers it, so it fails the evidence
  bar. Worth revisiting only if a `confirmation_mode` row is added without a
  matching enum entry.

## Resolved findings (archive)

One line per resolved finding — `<ID> · <C|M|m> · <title> · <files> ·
resolved @<hash>`; full bodies live in this document's git history. A
reintroduced defect reopens its ID here (moves back to Open findings with
`reopened @<hash>`), never gets a new one.

_None yet._

## History

| Date | Hash | LOG | ERR | SEC | PAT | CON | CMP | LGG | TSQ | TYP | RDB | Worst | Avg |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-08-24 | 61041ed | 3 | 4 | 3 | 10 | 4 | 4 | 9 | 4 | 10 | 7 | SEC/LOG 3 | 5.8 |

Per-aspect baselines (diff basis for the next incremental review):
all ten aspects @ 61041ed, scope: whole repo. No unbaselined files — the tree
was clean and every tracked file was in scope.
