---
name: backend-code-conventions
description: >-
  Use when writing or refactoring backend service code in any language —
  structuring modules and layers, separating business from technical logic,
  designing error handling, adding logging, naming things, or setting up unit
  and integration tests. Language-neutral concepts; pair with a stack-specific
  skill when one exists for the codebase.
---

# Backend code conventions

Language-neutral conventions for maintainable backend services. Core principle:
structure follows the data and the reader — code is organized around business
data, and reads like a book to the next developer. Examples use generic domain
names (`OrderService`, `OrderRepo`); apply the concepts in any language.

## Ask before structuring

How much structure a module deserves depends on its expected size and future
growth. When the requirements don't say, **ask the user** ("Will this module
grow — should I structure it for extension, or keep it minimal?") and briefly
say why you're asking. Time pressure ("keep it quick", "it's small") is a
constraint, not an answer to this question — ask it anyway. Small module, no planned features → write it plainly;
applying full SOLID ceremony there is over-engineering. Complex or growing
domain → separate modules/classes now, while it's cheap. Principles like SOLID
are guidelines to serve maintainability, not rules to satisfy.

## Layer by responsibility

Business logic is whatever operates on business data (`createOrder`,
`getOrderByOrderNo`, `writeStatusLog`). Technical logic is everything that
merely makes the system work — serde, HTTP, retries, config, crypto. Keep them
in separate layers: core/service (business, domain types only), repo (data
access — SQL and retry noise hidden inside the implementation, never on the
interface), api (wire contract only), sys (config/serde/wiring).

- **Domain types ≠ wire DTOs.** Core code never depends on request/response
  types; map at the boundary even when the shapes look identical today. A JSON
  field rename must not recompile business logic.
- **Shape validation at the edge, business validation in core.** "Is this JSON
  well-formed" and "is this a valid order" are different questions in
  different layers.
- **Repeated technical plumbing means a missing helper.** The same
  retry/unwrap/convert boilerplate copy-pasted across services drifts
  invisibly; extract one shared combinator.
- **SQL as raw strings belongs in `.sql` files** the code loads (easier to
  review and maintain than string literals — typical in Python); where the
  library offers a typed query DSL, prefer the DSL over raw SQL.
- **In-process retries: one bounded shared helper, integration edges
  only.** Prefer the architecture's structural retries (queue redelivery,
  scheduled re-runs, idempotent client retries) first. Where an in-process
  retry is warranted (DB call, external API), implement it once as a
  shared combinator with a hard bound (stop-after-elapsed, capped
  backoff) — and **never wrap a non-idempotent operation in a retry**: a
  retried double-charge is worse than a failed request.

## Business flows read as chains of named steps

Compose a flow as a linear sequence of single-purpose, named operations, with
error handling attached after the chain — not interleaved:

```
for
  order <- createOrder(msg)
  _     <- orderRepo.addUnpaidOrder(order)
  _     <- orderRepo.addStatusLog(order.no, Unpaid)
  _     <- ack(msg)
  _     <- publishOrderUnpaid(order)
yield ()
```

The naming test for bunched logic: if a function can't be named honestly
without "and", split it. Each step is then testable alone, and an edit's blast
radius is one step.

## Naming and visibility signal intent

- Methods: verb-first with domain nouns (`addUnpaidOrder`, `cancelOrder`).
  Multi-operation services are nouns (`OrderRepo`); a single-action operation
  is a verb phrase (`CreateNewOrder`).
- Use the language's visibility tools as documentation: internals not meant
  for other modules are private/package-private (DB row models fenced inside
  the repo layer, query builders hidden off the public interface).

## Every error has one owner

Decide each error's single fate at design time: **recover** (only when the
recovery has business meaning), **propagate with added context** (ids, what
was being attempted), or **fail loudly**. Model expected errors as closed
typed sets where the language allows (encode retryability in the type), keep
one exhaustive boundary handler that maps each to a log + response, and let
unexpected defects crash loudly after one contextual log.

**A constant becomes configuration only when a requirement says so.**
Named constants beside their use are the default for behavioral values
(result limits, display heuristics, thresholds); promote one to runtime
configuration only when a requirement demands no-deploy tunability or
per-environment variance. Config sprawl is a real cost — every entry
must be documented, boot-validated, and kept from drifting across
environments — so "someone might want to tune it" doesn't qualify;
an actual requirement does.

**Sort failures by whose fault they are, and fail configuration at
boot.** Malformed *input* at a trust boundary is a domain outcome — a
typed rejection (verify-false, 400), never an exception escaping. Bad
*configuration* (empty secret, unparseable URL, missing key) is a
defect: validate it where the component is constructed so a
misconfigured deployment dies at startup — never discovered as
per-request errors in production, and never silently absorbed into the
input-rejection path (a wrong secret quietly failing every legitimate
request is worse than a crash). The tell-tale smell is a half-guarded
function: the caller-supplied argument wrapped in a try/either while a
config-derived value on the same path can still throw.

Never: catch-alls that erase the error type, string-typed errors, logging the
same failure at multiple layers (helper logs, then caller logs again),
fail-fast that drops the context (which path? which id?), or log-and-ignore —
a log line is not handling. In effect systems, a log effect that isn't
sequenced into the chain never runs. Error paths are the least-exercised code
in the system: review and test them like happy paths (unreachable match
branches and copy-paste case lists hide there).

## Logs are events for the 3am reader

`error` = someone must act; `warn` = degraded but coping; `info` = a business
event happened; `debug` = diagnosis. Exactly one layer owns outcome logging —
the boundary handler that already owns error mapping; business code does not
log expected errors. One log per outcome, tagged with correlation/domain ids —
never raw payloads or secrets. **Every** outcome branch of a request logs the
event, including shape-validation rejections and not-found — an unlogged
branch is invisible in production. No narration ("got here", "calling db").

## Testing: three tiers

1. **Pure unit tests** on business logic — data in, data out, no mocks. Most
   tests live here. To enable it, each business rule is a **standalone pure
   function in the domain layer** — never a private method of the
   orchestrating service, which leaves the rule testable only through stubs.
2. **Stubbed unit tests** for orchestration — service logic with repos/clients
   stubbed. Verifies wiring, not the database.
3. **A thin layer of real-infrastructure integration tests** on repos and
   adapters — a stub can never fail because of the database; only this tier
   catches wrong SQL, schema drift, mapping bugs. When the real service isn't
   available locally, prefer in order: real engine in a container
   (Testcontainers) → official emulator (Firestore, Pub/Sub via the gcloud
   emulator images) → community fake (e.g. fake-gcs-server for GCS) →
   CI-only tests tagged to skip without credentials, run against an isolated
   sandbox project. (Tool availability current as of 2026-08.)

Two structural prerequisites, from day one: inject clock and random
seed/generators — no inline now()/random() anywhere business data is created,
which includes model field defaults (e.g. a dataclass `default_factory`) and
timestamps placed into events or log rows — and wrap every external service
behind your own interface so the test double is a swapped implementation, not
a rewrite.

## Common mistakes

- Ignored or incorrect error handling — see "Every error has one owner"; the
  catch-all and the double-log are the two most frequent.
- Bunching separable logic into one function — apply the "and" naming test.
- Messy logging — narration logs, payload dumps, asymmetric branches.
- Business code typed against wire contracts, or validation rules hidden in
  request models.
- Copy-pasted technical plumbing instead of a shared helper.
