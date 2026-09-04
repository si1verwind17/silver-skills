---
name: backend-architecture
description: >-
  Use when designing the system architecture of backend services — drawing
  service boundaries, deciding stateless vs stateful and where state lives,
  adding caches, handling concurrent mutation of shared entities, choosing
  sync (REST/gRPC) vs async (message queue) interactions, designing events
  and topics, push vs pull consumption, or weighing serverless against
  Kubernetes deployment.
---

# Backend architecture

Architecture decisions for backend systems: where state lives, where the
boundaries are, and how parts talk. Cloud examples reference managed
pub/sub and serverless generically — recommend concrete products at design
time from the user's cloud (GCP, AWS, local), never from memory. When the
chain provides `docs/backend-design/context.md`, read it first for the
estate's deploy/messaging reality and integration constraints — conform at
boundaries marked constraint, never mirror a listed wart, and record
divergences per the orchestrator's rule.

## The deliverable

Delivered under **`docs/backend-design/`** (beside the data design) unless
the user specifies otherwise:

1. **`architecture-design.md`** containing, in order:
   - Requirements interpretation and **open questions** (every assumption
     and question — service split, cache tolerance, sync/async choices);
   - A Mermaid **component diagram** (services, DBs, queues, caches) and
     one **sequence diagram per key flow** — keep diagram source portable
     across renderers: never use `;` inside message or note text (several
     Mermaid builds parse it as a statement separator and fail the whole
     diagram even where the latest build tolerates it); use `<br/>` or
     dashes to separate clauses;
   - A **decisions table** — per service: stateless/stateful (and why),
     cache (yes/no + staleness bound), deployment target (serverless/k8s +
     cold-start note); per interaction: sync or async, protocol, and
     **failure path** — what retries this hop (the structural mechanism:
     redelivery, relay re-scan, client retry against an idempotent path),
     and what happens when it keeps failing (park the row, DLQ, alert —
     and whether one poison message can block the ones behind it);
   - An **API inventory** — endpoint, purpose, consumer, sync/async;
   - An **event catalog** — topic name, triggering transition, JSON payload
     sketch, known and anticipated consumers, versioning approach;
   - Scaling notes.
2. **`openapi/<service>.yaml` draft skeletons — only when a consumer needs
   the contract before implementation exists** (frontend team, partner).
   Otherwise the authoritative spec is generated from code during
   implementation (endpoint-first frameworks do this); a hand-kept spec
   next to generated code is a drift risk, not an asset.

## Stateless by default

Write services stateless so they scale horizontally and can ride
serverless. Read-only data loaded at startup (config, reference data) is
fine; **mutable runtime state in process memory is not** — state lives in
the database, arbitrated by the guarded/idempotent write paths of the data
design. Deciding to be stateful is an explicit, justified exception, not a
drift.

## Per-entity single-writer — the rare exception

When multiple actors interactively mutate the *same* entity at the same
time (several operators editing one ticket), DB guards alone make a poor
experience — later writers keep losing. The pattern: a sharded,
single-instance-per-entity-id actor that hydrates state once and processes
commands **sequentially from its queue** — concurrency serialized by
construction, no locks. Reach for it only when interactive concurrent
mutation is a real requirement: it makes the service stateful and adds a
sharding runtime (entity framework + coordinator). Everything else stays
with database arbitration.

## Caching

Add a cache when a read is **known** to be hot — data fetched on every
request or user action whose value rarely changes (account, permission,
profile, reference-data lookups are the classic class) — relational
databases scale reads poorly. Two conditions before caching
anything: the data must be **stable for a known period** (otherwise users
see stale data), and you must state the staleness bound (TTL, or
invalidate-on-write) in the decisions table. Prefer a shared cache over
in-process memory when the service scales horizontally. The smell of a
*missing* cache: a request that chains several sequential external calls
for data that rarely changes.

## Service boundaries

Split by requirement, not by fashion: a POC or budget-constrained project
is one service; an environment that already runs microservices (or a
domain with clearly separate ownership, scaling, or blast radius) splits.
**Ask the user when unsure** — and when the requirements left the split
undecided, applying these criteria yields a *recommendation*, not a
settled fact: record the split in the open questions with your
recommendation, the assumption you proceeded on, and what would trigger
revisiting it. Two hard rules regardless of split:

- **Never share tables or a database between independently deployed
  services.** Coordination happens through APIs or events; a shared table
  means a schema change in one service silently breaks the other.
- **One owner per capability.** A rewrite that leaves the old service
  deployed beside the new one ("duplicated, not decoupled") creates
  ownership ambiguity worse than either version alone — finish
  migrations.

## Sync vs async — from the requirement's semantics

Does the caller need the answer to proceed? Sync request/response. Is it
fire-and-forget, fan-out, or retryable-later? Async via queue. Both
mistakes are common: HTTP call chains for semantically-async work
(blocking cascades, retry storms) and queues where the caller then polls
for a result it needed synchronously.

**Retries are a design decision, structural first.** Decide at design time
which failures are retried by the architecture itself — queue redelivery,
a scheduled relay re-run, clients retrying against idempotent write paths
— and record it in the decisions table's failure-path slot for **every**
async hop and external call, even when the mechanism falls out of the
design "for free" (a relay re-scanning unpublished rows *is* the retry —
say so, and say what happens to the row that never succeeds). An
implicit mechanism nobody wrote down is where poison messages and
head-of-line blocking hide. In-process retry loops are the last resort,
reserved for integration edges; their mechanics (bounded, shared helper,
never around non-idempotent operations) belong to the code conventions.

Protocol choice for sync: **REST by default, and always for anything
front-facing.** gRPC is for internal service-to-service calls that need
high throughput, streaming, or generated typed clients across languages —
it earns its toolchain weight there and nowhere else.

## Event design

- **Publish every business-state transition of core entities by default**
  (`order-unpaid`, `order-paid`, `order-completed`), even with no consumer
  yet: unconsumed messages simply expire per topic config, and the consumer
  that arrives a year later costs nothing to serve — retrofitting the
  publish later costs a release and backfill. Confirm with the user; the
  recommendation is to publish.
- **Payloads carry meaningful data, not bare ids** — consumers should act
  without calling back. Cautions that come with this default: every
  consumer couples to the payload shape, and sensitive fields (PII)
  spread to every topic that embeds them — keep sensitive data minimal or
  referenced.
- JSON payloads; serde per language. Topic naming
  `<domain>-<event>-v<N>`. **Versioning**: no in-place schema breaking —
  default approach is a new `-v2` topic; ask the user if requirements
  demand something richer.
- Publish after the required business logic has succeeded (see the data
  design's queue rules for ownership and atomicity).

## Push vs pull consumption

Decided by the consumer's deployment target. **Push** behaves like an HTTP
endpoint — natural for scale-to-zero serverless (no idle cost), but the
endpoint must verify deliveries genuinely come from the broker. **Pull**
requires a 24/7 consumer but gives flow control, batching, and
backpressure — right for Kubernetes residents with sustained throughput.

## Serverless vs Kubernetes

Serverless (e.g. Cloud Run) fits stateless, spiky, scale-to-zero
workloads; Kubernetes fits stateful services (sharded entities), sustained
throughput, and pull consumers. **The runtime's cold-start behavior is
part of this decision**: non-native JVM services cold-start poorly on
serverless; runtimes that start fast fit it well — but weigh that against
maintainability for complex domain logic. Full language/runtime selection
belongs to stack selection; here, record the pairing constraint in the
decisions table.

## Before delivering — verify

Walk these against your actual output files; fix, don't rationalize:

- [ ] Every service appears in the decisions table with stateless/stateful,
      cache, and deployment target each justified.
- [ ] Every interaction in the component diagram is classified sync/async
      with a protocol, and appears in the API inventory or event catalog.
- [ ] Every core entity's business-state transitions appear in the event
      catalog — including ones with no current consumer.
- [ ] No two services share a table or database anywhere in the design.
- [ ] Every ambiguity you resolved by assumption appears in open questions —
      **including the service-split decision itself** whenever the
      requirements didn't decide it.
- [ ] Every event payload carrying personal data has a PII note in the
      event catalog.

## Common mistakes

- Mutable runtime state in process memory instead of the DB.
- Two services sharing a database or tables; RW/RO "by convention".
- Old and rewritten services both left deployed — duplicated, not
  decoupled.
- Caching data that changes underneath users; or no cache in front of
  repeated sequential external calls.
- HTTP chains for async semantics; queues plus result-polling for sync
  semantics.
- gRPC everywhere internally by default; REST would have done.
- Serverless chosen (or rejected) without checking runtime cold-start.
- Events retrofitted after a consumer finally appears, instead of
  published from day one.
