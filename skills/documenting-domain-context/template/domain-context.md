# Domain context

Facts about this system that its code cannot state, for agents and
reviewers. Every line carries provenance — `owner (date)`, `repo: <path>`,
`probe (date): <what was run>`, or `unknown`. This file describes the
system as it is; intended behavior lives in requirements documents, and
this file's own history lives in git. Keep the section names and order.

## 1. System in one paragraph

<What it does, for whom, and the sentence that separates a core path from
a side path.> — <provenance>

## 2. Module map

| Module | Path | Purpose (one line) | Role |
|---|---|---|---|
| <name> | `<dir/>` | <what it does, for whom> | core path · supporting |

Core path: <the modules, in order, that a request, decision or record
passes through when it moves the money, data or state the business cares
about>. — <provenance>

## 3. Documents and their scope

| Document | Covers | Fresh as of | Note |
|---|---|---|---|
| `<path>` | <the module or topic the owner confirmed it describes> | <date, commit, or unknown> | <generated for one module · superseded by code · partial> |

A fact is cited to a document only within that document's confirmed scope.

## 4. Operating envelope

- Instances / replicas: <n> — <provenance>
- Tenants: <n / single> — <provenance>
- Throughput: <requests, messages, rows per unit time> — <provenance>
- Dataset sizes / retention: <…> — <provenance>
- Latency budget / batch windows: <…> — <provenance>

## 5. Trust and deployment model

- Authn / authz location: <perimeter · sidecar · in-code>, evidence <…> —
  <provenance>
- Trusted client decisions: <what the client may decide> — <provenance>
- Secrets: <which exist, where they live at runtime> — <provenance>
- Runtime platform (actual, not supported): <…> — <provenance>

## 6. Invariants

- <statement that must never be false, and which module enforces it> —
  <provenance>

## 7. Module context

One subsection per module, in map order, then **Shared**. Every heading
below appears in every subsection; `n/a` is a valid body.

### <Module name> (`<path>`)

- **Business context**: <what the module does for the business, in at
  most three lines — the rule it enforces, the record it keeps, the
  decision it makes> — <provenance>
- **External parties**: per party this module talks to — mutating
  operations; outcomes per operation <completed · partial ·
  accepted-but-pending · rejected pre-flight · rejected by the party ·
  ambiguous · superseded>; idempotency key and how a lost response is
  resolved; validity / replay window; error codes whose meaning is not
  what the name suggests — <provenance>
- **Money, units and ownership**: <quantity: unit; fee: borne by whom,
  charged in what unit, rounding; authority when two sources disagree> —
  <provenance>
- **States and transitions**: <entity: states; terminal; supersedable
  from outside by …; valid-but-not-enabled configurations> —
  <provenance>
- **Hazards**: <hazard, with the consequence named> — accepted by
  <owner (date)>, reason <…> · or — declined by <owner (date)>
  (only hazards the owner has ruled on; unruled observations are not
  written)
- **Not exercised**: <code path, integration or flag that exists but is
  not exercised in production> — <provenance>

### Shared (`<paths>`)

<A party, schema or store used by more than one module is described here
once, under the same headings; module subsections refer to it by name.>

## 8. Provenance and freshness

- Mined against commit `<hash>` on <date>.
- Last owner confirmation: <date>, by <role>.
- Owner review of `repo:` lines: <date> · not yet reviewed.
- Open unknowns: <every fact marked unknown above, so a later pass can
  ask again>.
