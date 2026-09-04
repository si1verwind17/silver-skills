# Booking Service — Stack Selection

Status: **draft, awaiting phase-3 review gate** (revision 2)
Date: 2026-08-24
Inputs: `requirements.md` r5, `data-design.md`, `architecture-design.md` (all approved)
Resolves: **PD11** (language, framework, runtime) and **PD15** (broker product and client)

Revision 2 replaces a Scala 3 + ZIO recommendation after the reasoning behind it
was found to be flawed (§3.1) and the requester selected Kotlin + Spring Boot.
All versions re-verified for the new stack.

Every version and maturity claim was verified on **2026-08-24** against the
source named beside it. Nothing here is answered from memory.

---

## 1. Existing-stack assessment

**There is no existing stack.** From the interview of 2026-08-24:

| Question | Answer | Consequence |
|----------|--------|-------------|
| What runs in production today? | Nothing — greenfield | The skill's default (*the existing stack wins*) has nothing to defend; selection rests on service fit |
| Cloud and deployment platform | Nothing yet | LD11's cloud-agnostic requirement is real, not aspirational. No managed broker or managed Postgres may be assumed |
| Team size and skills | Solo, and **explicitly excluded**: "I don't want the stack selection to be impacted by team. Use the proper stack for proper job" | Team familiarity is struck from the criteria. Recorded because it is normally the tie-break and here deliberately is not |
| Organisational constraints | **Open-source only, no paid licences** | Disqualifies source-available brokers (§4). Everything selected is OSI-licensed |
| Mandated corporate standard | None stated | Assumed none |
| Compliance beyond NF5 | None stated | Assumed NF5's GDPR-style handling is the whole obligation |

---

## 2. What this service actually is

- **Two always-on Kubernetes workloads from one image** — an HTTP API, and a
  worker running the outbox relay, a pull consumer and two sweeps. Nothing is
  serverless (LD11, AQ7), so **cold start is not a criterion**; steady-state
  memory and long-lived connection handling are.
- **The correctness core is in the database, not the application.** Phase 1 put
  capacity, overlap and buffer invariants into an exclusion constraint, the
  state machine into `booking_transition_rule`, and the write paths into
  plpgsql functions. The application must not re-implement any of it.
- **What remains in the application is IO orchestration**: validate a token,
  resolve a subject, evaluate a one-line gate policy (R33), call a database
  function, translate one of nine `BK*` SQLSTATEs into an HTTP response (NF8),
  and run three loops — relay, consumer, sweeps — with careful failure handling.
- **Load is modest** (NF2: ~50 req/s search, ~10 req/s writes, ~30 events/s), and
  every request is dominated by a database round trip. No candidate is excluded
  on throughput, and any chosen *for* throughput is chosen for the wrong reason.

---

## 3. Candidate assessment

| Criterion | **Kotlin + Spring Boot** (selected) | Scala 3 + ZIO + tapir | Go | Rust + Axum |
|-----------|-------------------------------------|----------------------|-----|-------------|
| Fit for an IO-orchestration adapter | **Strong.** The framework supplies the loops, scheduling, pooling, token validation and OpenAPI; the code left to write is the part that is actually specific to this service | Strong, but supplies less out of the box | **Strong** — arguably the canonical fit | Adequate; the borrow checker adds friction to mostly-IO code |
| Closed-set modelling (7 states, 9 error codes) | Good — sealed classes give compiler-checked exhaustive `when` | Best — typed error channel makes an unhandled code a compile error | Weak — no sum types | Best — `enum` + `Result` |
| Ecosystem for the required integrations (§5) | **Best.** Every integration is first-party and version-managed by one BOM. Zero assembly risk | Weakest of the four — assembling it meant routing around an abandoned library and accepting a pre-1.0 one (§3.1) | Strong | Good, thinner migration tooling |
| Always-on K8s fit | Good. Highest steady-state memory of the four | Good | Best — smallest images | Best — no GC |
| Iteration speed | **Best** | Weakest — compile times | Best | Weakest |
| Licence cost | £0, all OSI | £0 | £0 | £0 |
| Verified | §5, §6 with sources | Maven Central metadata | — | — |

### 3.1 Why the first recommendation was withdrawn

Revision 1 recommended Scala 3 + ZIO, arguing this service has "heavy domain
logic" where compile-time safety pays for itself. **That argument does not
survive contact with phase 1.** The whole point of the data design was to move
the hard correctness into Postgres: the state machine is a seeded table, the
invariants are an exclusion constraint, the write paths are database functions
that already build the event payload. Recommending an expressive language on
the grounds that the application is full of hard logic, immediately after
designing the application not to be, was inconsistent.

Two further signals were reported in revision 1 without the conclusion being
drawn: assembling the Scala stack required replacing **`dev.zio:zio-jdbc`, last
published 2023-12-28 and abandoned**, and accepting **`zio-json` at pre-1.0**.
Needing to route around an abandoned library is evidence about ecosystem
maturity, not merely a footnote.

Kotlin + Spring Boot was selected by the requester from the revised assessment.
It is the strongest fit on the two criteria that actually dominate here —
ecosystem completeness for a service that is mostly integration, and iteration
speed — while sealed classes keep the closed-set modelling nearly as strong as
Scala's. Go was declined by the requester on readability grounds; Rust was
declined because its advantages (no-GC tail latency, efficiency economics at
scale, CPU-bound work) are levers this service never pulls.

---

## 4. Broker selection (PD15)

Selected against the capability contract in `architecture-design.md` §6.1. This
choice is independent of the language and is unchanged from revision 1.

| Candidate | Licence | At-least-once | Ordering per key **with parallel consumers** | DLQ / park | Pull | Verdict |
|-----------|---------|---------------|---------------------------------------------|-----------|------|---------|
| **Apache Kafka** | Apache-2.0 | yes | **Native** — partition-by-key is exactly this pairing | Client-side dead-letter topic; Spring Kafka provides `DeadLetterPublishingRecoverer` | yes | **Selected** |
| NATS JetStream | Apache-2.0 | yes | Per-subject ordering, but combining it with parallel consumption needs deliberate subject/consumer design | `max_deliver` plus advisories | yes | Viable fallback, lighter to operate |
| Apache Pulsar | Apache-2.0 | yes | `Key_Shared` subscriptions | Built-in DLQ | yes | Capable, heaviest for this load |
| Redpanda | **No OSI licence reported** by the GitHub API (source-available) | — | — | — | — | **Excluded** by the open-source-only constraint |
| RabbitMQ | MPL | yes | Needs the consistent-hash plugin | Native DLX | yes | Excluded — ordering not native |

**Why Kafka.** §6.1's hardest requirement is a *pairing*: ordering per
`bookingRef` **while** consumers scale horizontally. Kafka's partition model is
that pairing in a single mechanism. Spring Kafka additionally supplies the
dead-letter recoverer the §4 failure paths call for, so the one capability Kafka
leaves to the client is provided by the framework already being adopted.

**Honest caveat.** At ~30 events/s Kafka's capacity is irrelevant and it is the
heaviest component here to operate. NATS JetStream stays pre-vetted: the design
binds to §6.1's contract, not to Kafka. Retention was deliberately not scored —
architecture §6.2 already removed the design's dependence on replay.

---

## 5. Required-integration ecosystem check

| Integration | Why the design needs it | Library | Verified 2026-08-24 |
|-------------|------------------------|---------|---------------------|
| HTTP server | The §5 API inventory | Spring MVC on Tomcat | Tomcat 11.0.24, BOM-managed |
| **OpenAPI generation** | AQ8 requires the spec generated from code, never hand-kept | `springdoc-openapi-starter-webmvc-ui` | **3.1.0** — the 3.x line targets Spring Boot 4; 2.x targets Boot 3. Not in the Boot BOM, so we pin it ourselves |
| Postgres driver exposing raw SQLSTATE | Nine `BK*` custom SQLSTATEs must reach the API layer (NF8) | `org.postgresql:postgresql` | 42.7.13, BOM-managed. See §5.1 — this needs deliberate handling under Spring |
| SQL execution | Calling plpgsql functions and mapping rows | **`JdbcClient`** (Spring Framework 7 core) | 7.0.9. No extra dependency, and no ORM — see §5.2 |
| Connection pooling | Both workloads hold pools | HikariCP | 7.0.2, BOM-managed |
| Schema migrations | Data-design §8 requires numbered per-concern migrations | `flyway-core` + `flyway-database-postgresql` | 12.4.0, BOM-managed. Community edition is Apache-2.0 |
| Kafka client, pull consumption | Relay publish and gate-resolution consumption (AQ7) | `spring-kafka` over `kafka-clients` | spring-kafka 4.1.1, kafka-clients 4.2.1, both BOM-managed |
| JWT/JWKS validation, fail closed | R17, PD1 | `spring-boot-starter-oauth2-resource-server` | Spring Security 7.1.1, BOM-managed. JWKS fetching, caching and rotation are built in |
| JSON | Event envelopes and API payloads | **Jackson 3.0** | Boot 4's default. Jackson 2 ships deprecated — see §5.3 |
| Scheduling for the two sweeps | UC11, UC12 | `@Scheduled` (Spring core) | Built in; no dependency |
| Metrics, health, tracing | NF7, NF11 | Micrometer + Actuator | Micrometer 1.17.1, BOM-managed |
| Integration testing against real Postgres and Kafka | NF10 requires the concurrency and DST cases as automated tests | Testcontainers | **2.0.5** via `testcontainers-bom` — see §5.4 for the coordinate change |

No gaps were found. Every integration is first-party or BOM-managed, which is
the concrete form of this stack's main advantage.

### 5.1 Custom SQLSTATEs need deliberate handling — a real trap

Spring translates `SQLException` into its own `DataAccessException` hierarchy.
The nine `BK*` codes are not codes Spring knows, so by default they surface as
`UncategorizedSQLException` and the specific code is **buried** — a naive
implementation would turn every business rejection into an opaque 500, breaking
NF8's requirement that errors name the violated rule.

The fix is small but must be deliberate: register a custom
`SQLExceptionTranslator` that maps `BK001`–`BK009` onto typed application
exceptions, and let the default translator handle everything else. Recorded here
so phase 4 treats it as a requirement rather than discovering it in testing.

### 5.2 No ORM — deliberately

JPA/Hibernate is the reflex choice with Spring Boot and would be **actively
harmful** here. Every write in this design is a single call to a plpgsql
function that performs its own upsert, guarded update, history insert and outbox
insert atomically. An ORM's value — managing entity state, dirty checking,
cascading, generating SQL — is value this design has deliberately refused, and
its entity cache would sit between the application and invariants the database
alone enforces.

`JdbcClient` is used instead: it calls functions, binds parameters and maps rows,
and does nothing else. This is a Spring project that does not use Spring Data,
and that is the correct outcome.

### 5.3 Jackson 3 is the Boot 4 default

Spring Boot 4 moves to Jackson 3.0 with Jackson 2 shipping deprecated. Jackson 3
changes its package root, so any third-party library still compiled against
Jackson 2 can drag in the deprecated path. Nothing selected here does, but the
event payloads and API models must be written against Jackson 3 from the start.

### 5.4 Testcontainers changed coordinates in 2.x

The Boot 4.1.1 BOM manages `testcontainers-bom` at 2.0.5. The Postgres module's
artifact **moved**: `org.testcontainers:postgresql` stops at 1.21.4, and the 2.x
module is `org.testcontainers:testcontainers-postgresql` (2.0.5). Both
coordinates were checked against Maven Central. Using the old artifact name with
the new BOM resolves to nothing.

---

## 6. Finalized versions

**How these are pinned.** With Spring Boot, the honest pin is what the BOM
resolves, not a version picked per library — overriding BOM entries creates
combinations nobody tested. Everything below marked *BOM* was read out of
`spring-boot-dependencies-4.1.1.pom` on Maven Central, which is the authoritative
statement of what Boot 4.1.1 ships with. Only two versions are ours to choose.

| Component | Pinned version | Source |
|-----------|---------------|--------|
| **Spring Boot** | **4.1.1** | `.../org/springframework/boot/spring-boot/maven-metadata.xml` |
| Kotlin | **2.3.21** | BOM `<kotlin.version>`. Boot 4 requires Kotlin 2.2+ |
| JDK | **Temurin 25 LTS** | `docs.spring.io/spring-boot/system-requirements.html` — Boot 4.1.1 supports Java **17 to 26 inclusive**; `api.adoptium.net` — 25 is `most_recent_lts` |
| Gradle (Kotlin DSL) | **9.7.1** | `services.gradle.org/versions/current`. Boot 4.1.1 requires Gradle 8.14+ or 9.x |
| Spring Framework | 7.0.9 | BOM `<spring-framework.version>` |
| Spring Security | 7.1.1 | BOM `<spring-security.version>` |
| Spring Kafka | 4.1.1 | BOM `<spring-kafka.version>` |
| kafka-clients | 4.2.1 | BOM `<kafka.version>` |
| PostgreSQL JDBC driver | 42.7.13 | BOM `<postgresql.version>` |
| HikariCP | 7.0.2 | BOM `<hikaricp.version>` |
| Flyway | 12.4.0 | BOM `<flyway.version>` |
| Tomcat | 11.0.24 | BOM `<tomcat.version>` |
| Micrometer | 1.17.1 | BOM `<micrometer.version>` |
| Logback | 1.5.38 | BOM `<logback.version>` |
| Testcontainers | 2.0.5 via `testcontainers-bom`, module `org.testcontainers:testcontainers-postgresql` | BOM; coordinate verified per §5.4 |
| Jackson | 3.0 (Boot 4 default) | Boot 4.0 release notes |
| **springdoc-openapi** | **3.1.0** | `.../org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml`. **Not** in the BOM — ours to pin |
| **PostgreSQL server** | **18.6** | `endoflife.date/api/postgresql.json` — 18 latest 18.6, EOL 2030-11-14. See §8 |
| **Apache Kafka broker** | **4.3.1** | `.../org/apache/kafka/kafka-clients/maven-metadata.xml`; Apache-2.0 per `api.github.com/repos/apache/kafka` |
| Base image | `eclipse-temurin:25-jre-alpine` | Temurin 25 LTS per Adoptium |

### Pins that were a judgement call

- **BOM versions over newest available.** Latest releases exist for several
  components — Flyway 13.3.0, HikariCP 7.1.0, kafka-clients 4.3.1, Kotlin
  2.4.10 — and each was deliberately **not** taken. The BOM's combination is the
  one Spring tests together; overriding four entries to gain nothing this service
  needs would trade tested integration for version novelty.
- **Broker 4.3.1 with client 4.2.1.** Deliberate and safe: Kafka clients
  interoperate with newer brokers, the broker should not be held back to match a
  BOM, and the client should not be dragged ahead of what Spring Kafka tests.
- **Spring MVC with virtual threads, not WebFlux.** JDK 25 makes
  `spring.threads.virtual.enabled=true` the simple path to high concurrency on
  blocking code. Every database call here is JDBC to a plpgsql function, and the
  relay and consumer are naturally blocking loops. WebFlux would mean R2DBC,
  which would forfeit the JDBC SQLSTATE path §5.1 depends on and complicate
  transaction management — reactive complexity bought for a workload whose
  bottleneck is one database round trip.

---

## 7. Recommendation, and the honest comparison

**Selected: Kotlin 2.3.21 + Spring Boot 4.1.1 (Spring MVC, virtual threads) on
Temurin 25 LTS, built with Gradle 9.7.1, against PostgreSQL 18.6 and Apache
Kafka 4.3.1.**

NF1 makes this mission-critical, so the skill requires weighing it against a
named, concretely evaluated alternative. That alternative is **Scala 3.3.8 + ZIO
2.1.26 + tapir 1.13.31**, researched in full in revision 1.

| | **Kotlin + Spring Boot** (selected) | Scala 3 + ZIO + tapir |
|---|---|---|
| Nine `BK*` codes → HTTP | Sealed classes plus a custom `SQLExceptionTranslator` (§5.1). Correct, but the translator is a step that must not be forgotten | Typed error channel — forgetting a code is a compile error |
| Seven-state machine | Enforced in the database either way. Sealed classes + exhaustive `when` for the API projection | Marginally stronger, same enforcement point |
| Relay, consumer, sweeps | `@Scheduled`, Spring Kafka listener containers, virtual threads. Mostly configuration rather than code | Fibers and scopes; stronger guarantees, more code to write |
| Ecosystem assembly risk | **None.** One BOM, every integration first-party | Real — an abandoned library and a pre-1.0 library (§3.1) |
| OpenAPI for AQ8 | springdoc 3.1.0 generates from annotated controllers | tapir derives it from the serving endpoints |
| Iteration speed | **Faster** | Slower compiles |
| Steady-state memory | Higher — Spring's footprint | Moderate |
| Onboarding another maintainer | **Easiest of any candidate** | Hardest |

**Why this is the right answer for this service.** The application layer is
mostly integration: HTTP in, one function call, translate, publish, consume,
schedule. That is precisely the shape Spring Boot removes work from, and the
column that decides it is ecosystem completeness, not expressiveness — because
phase 1 already moved the expressiveness-hungry part into the database.

**Choose Scala 3 + ZIO instead if** the application layer later grows genuine
in-process domain logic that the database cannot own, or if the `BK*` translator
of §5.1 proves to be a recurring source of mistakes rather than a one-time
setup.

### Risks accepted

| Risk | Mitigation |
|------|-----------|
| **Spring buries custom SQLSTATEs by default** (§5.1) — the highest-likelihood defect in this pairing | A custom `SQLExceptionTranslator` is a named phase-4 requirement, and NF10 already demands an automated test per rejection rule, which would catch a regression |
| **Spring Boot 4 and Jackson 3 are recent majors.** Fewer community answers exist than for Boot 3 | Every component is BOM-managed, so the combination is vendor-tested rather than assembled. Boot 4.1.1 is a patch release of an established line, not a `.0` |
| The reflex to reach for Spring Data/JPA | §5.2 records the decision and its reason. An ORM here would sit between the application and invariants only the database enforces |
| Highest steady-state memory of the candidates | Irrelevant at NF2's load; noted for the record |
| Kafka is heavy to operate for ~30 events/s | NATS JetStream pre-vetted (§4); the design binds to §6.1's contract |

---

## 8. Backtrack into phase 1 — PostgreSQL 18.6

`data-design.md` §3 pinned **PostgreSQL 16** with a hard floor of 14
(multiranges). Phase 3 moves the pin to **18.6**; the floor is unchanged.

16 was chosen before the environment was known. This is confirmed greenfield with
no existing database to match, so starting two majors behind only inherits an
earlier end-of-life: 16 reaches EOL 2028-11-09 against 18's 2030-11-14
(`endoflife.date/api/postgresql.json`, checked 2026-08-24).

**Verified, not assumed.** The full schema and both scenario suites were executed
against `postgres:18-alpine` (PostgreSQL 18.6): all four DDL files loaded clean,
all 12 asserted domain errors raised identically, and event parity held at 14
transitions / 14 events / 1 rejection — identical to the phase-1 run on 16.
`data-design.md` is updated to match.

---

## 9. Consistency with the other documents

- **PD11 resolved**: Kotlin 2.3.21 + Spring Boot 4.1.1 on Temurin 25 LTS.
- **PD15 resolved**: Apache Kafka 4.3.1, client via Spring Kafka 4.1.1.
- Every component architecture §2 demands has a pinned version in §6: HTTP
  server, OpenAPI generator, Postgres driver, pooler, migrations, Kafka client,
  JWT/JWKS validation, JSON, scheduling, metrics, test containers.
- Deployment target reads the same here as in architecture §3: Kubernetes,
  always-on, no serverless, no vendor-managed dependency.
- AQ7's pull consumption is satisfied by Spring Kafka's listener containers.
- AQ8's requirement that OpenAPI be generated rather than hand-kept is satisfied
  by springdoc 3.1.0.
- The two workloads of architecture §3 ship from one image, selected by Spring
  profile (`api` / `worker`), so `booking-api` and `booking-worker` remain one
  service with one build (§4.1 of the architecture).
- Phase 3 amends phase 1 once, recorded in both documents: the PostgreSQL pin (§8).

Requirements items still open and untouched by this phase: PD1, PD4, PD5, PD6,
PD7, PD9, PD10, PD13, PD14.
