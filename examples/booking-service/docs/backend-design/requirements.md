# Booking Service — Business Requirements

Status: **approved at the phase-0 review gate** (revision 5)
Amendments: revisions 5's R31/R25 changes were made by phase 1, and phase 2
made no further change here — both amendments await confirmation at the
phase-2 gate.
Date: 2026-08-24
Mode: interview (greenfield; no existing PRD, no external estate to distil)

Revision history:
- **r1** — initial intake from the requester interview.
- **r2** — rulings on PD2, PD3, PD8, PD11, PD12. Notification *delivery* left
  the service entirely; integration became event-driven (LD14); the
  capacity-holding state became the generic **HELD** state resolved by external
  gates (LD16).
- **r5** — amended by phase 1 (data design). R31's buffer arithmetic changed
  from "the larger of" to the **sum** of adjoining buffers: max cannot be
  expressed as a range overlap, so enforcing it would need read-then-decide
  logic, which NF4 forbids; sum is also the better physical reading and can
  express any gap a provider wants. R25 restated so the **session**, not the
  current service row, is authoritative for a booking's snapshotted values.
  See data-design.md OQ1 and OQ14.
- **r4** — PD19 closed. Buffer violations locked as never-offerable (LD18);
  minimum lead time now binds customer-initiated bookings only, so a provider
  may take a phone booking inside their own lead time (R4); gate selection
  restated as policy evaluation at creation time (R33).
- **r3** — rulings on PD16, PD17, PD18, PD20. Event payloads carry ids only, no
  PII. Buffer time added as a first-class concept (LD17, R31, R32), which forced
  R1 to be restated around a **session** — a resource hosts one session at a
  time; a session holds up to `capacity` bookings. "Auto-accept when there is no
  conflict" resolved as already-existing instant mode rather than a third
  confirmation mode (PD20).

---

## 1. Summary & business goal

A backend service that lets **providers publish when they are bookable** and
lets **customers reserve time** against that published availability.

A provider (a business) owns one or more **resources** — the things that can
actually be booked, such as a staff member or a room — and offers one or more
**services** with a fixed duration (e.g. "Haircut, 30 min"; "Yoga class,
60 min, 12 seats"). Providers describe their opening hours as **recurring
rules** rather than slot by slot, with one-off exceptions for holidays and
extra openings. Customers search for available start times, book, cancel, or
reschedule; providers either accept bookings instantly or approve each
request, and afterwards mark whether the customer attended.

The business goal is to own the *scheduling* half of the problem correctly —
never double-book, never lose a booking, always show truthful availability —
so that commercial concerns layered on later (payment, notifications,
marketplace discovery, reviews) build on a trustworthy calendar. This service
is one microservice among several: it publishes what happened to a message
queue and consumes decisions made elsewhere. It sends no email, takes no
money, and knows nothing about who reacts to its events (LD14).

---

## 2. Locked decisions

Decided with the requester on 2026-08-24. Downstream phases implement these
and must not re-litigate them; changing one is a user decision, not a design
adjustment.

| id | Decision |
|----|----------|
| LD1 | **Unified capacity model.** Every bookable time range carries a capacity N; 1:1 appointments are simply capacity = 1. There is no separate "class" code path. |
| LD2 | **Confirmation mode is configurable per provider.** Both instant-confirm and provider-approval flows exist and are first-class. |
| LD3 | **Identity is external.** An external IdP authenticates; this service trusts a verified subject id and stores only its own customer/provider profiles. It never stores credentials. |
| LD4 | **Attendance is tracked.** Providers mark past bookings COMPLETED or NO_SHOW; unmarked ones auto-complete after a grace period. |
| LD5 | **Recurring availability rules are in scope.** Providers define repeating patterns; the system derives bookable start times from them. |
| LD6 | **Multiple resources per provider are in scope.** A provider may have many independently bookable staff/rooms. |
| LD7 | **Services have durations.** A booking's length is determined by the booked service, not by a fixed global slot size. |
| LD8 | **Reschedule is atomic.** Moving a booking releases the old time and takes the new one in a single all-or-nothing operation, preserving booking identity. |
| LD9 | **Notification *delivery* is out of scope; notification *triggering* is in scope.** This service publishes booking lifecycle events; a separate notification service consumes them and sends email/SMS. This service has no messaging vendor, no template, no channel concept. (Ruling on PD2.) |
| LD10 | **Payments are out of scope.** No pricing, capture, refund, payout, or invoice logic, and no payment data in this service's model. The lifecycle is shaped so a payment step can be added later without a state-machine change (LD16, UC14). |
| LD11 | **Target environment: Kubernetes, cloud-agnostic.** No dependency on any single vendor's managed services. |
| LD12 | **Production, moderate scale** (see NF2) — not a throwaway POC, not marketplace-scale hot-slot contention. |
| LD13 | **Full design chain with four review gates** (requirements → data → architecture → stack → implementation). |
| LD14 | **Integration is event-driven, via a message queue.** This service publishes **every** booking lifecycle transition to one versioned event stream and lets consumers filter; it never calls a consumer directly and never learns who subscribes. It also **consumes** gate-resolution events (LD16). (Ruling on PD2 and PD8.) |
| LD15 | **One booking takes exactly one unit of capacity.** A customer wanting three seats in a class makes three bookings. (Ruling on PD12.) |
| LD16 | **The capacity-holding intermediate state is generic.** A booking in **HELD** holds capacity while awaiting resolution of exactly one external *gate*, identified by a hold reason and governed by that gate's TTL. Today the only gate is `AWAITING_PROVIDER_APPROVAL`, resolved through this service's own API. A future `AWAITING_PAYMENT` gate, resolved by a consumed event, is a new gate value — not a new state machine. |
| LD17 | **Services carry buffer time.** Each service declares buffer-before and buffer-after; the buffer is reserved on the resource but is never itself bookable, so sessions cannot sit back-to-back unless a service declares zero buffer. |
| LD18 | **A buffer violation is never offerable to the provider.** It is rejected outright in every confirmation mode, permanently — this is a locked decision, not a deferred question. Supporting an override would require sessions creatable in violation of R31, surrendering the store-enforced invariant NF4 demands. A provider wanting tighter turnover lowers the buffer. |

---

## 3. Actors & roles

| Actor | Who they are | What they are trying to do |
|-------|--------------|----------------------------|
| **Customer** | An end user authenticated via the IdP | Find a time that works, book it, change or cancel it, see their upcoming and past bookings |
| **Provider admin** | Owner/manager of a provider business | Configure the business, its resources and services; publish availability; approve or decline requests; see the calendar; record attendance |
| **Resource** | A bookable staff member or room, owned by a provider | Is booked *against*; may optionally be linked to a user account so that person can see their own calendar (R24) |
| **System** | Scheduled internal processes | Expire holds past their deadline, auto-complete past bookings, dispatch pending outbound events |
| **Notification service** | A separate service, not built here | Consumes the booking event stream and delivers email/SMS. This service neither knows nor cares that it exists (LD9). |
| **Payment service** | A separate service, not built here, not yet built at all | Would consume `BookingHeld` and publish a gate resolution this service consumes (LD10, UC14) |

### Roles & permissions

| Capability | Customer | Provider admin | Resource-linked user | System |
|---|---|---|---|---|
| Search availability | ✅ (public) | ✅ | ✅ | — |
| Create booking | ✅ (as self) | ✅ (on behalf of a customer; bypasses minimum lead time, R4) | ❌ | ❌ |
| Cancel booking | ✅ (own, within policy) | ✅ (own provider's) | ❌ | ❌ |
| Reschedule booking | ✅ (own, within policy) | ✅ (own provider's) | ❌ | ❌ |
| Approve / decline a held booking | ❌ | ✅ | ❌ | ❌ |
| Mark COMPLETED / NO_SHOW | ❌ | ✅ | ❌ | ✅ (auto-complete only) |
| Manage resources, services, availability | ❌ | ✅ | ❌ | ❌ |
| Read a provider's full calendar | ❌ | ✅ | ✅ (own bookings only) | — |

Authorization is scoped: a customer may act only on their own bookings; a
provider admin only within their own provider (R18). Inbound *events* are not
a way around this — a consumed gate resolution may only resolve the gate it
names, and can perform no other transition (R28).

---

## 4. Use cases

### UC1 — Provider sets up its business

**Actor:** Provider admin. **Goal:** Become bookable.
**Main flow:** Create the provider profile (name, IANA timezone, confirmation
mode, booking policy values per R4–R8) → add one or more resources → add one
or more services (name, duration, capacity, which resources may perform it).

**Acceptance criteria**
- A provider cannot be created without a valid IANA timezone.
- A service specifies a duration in minutes > 0 and a capacity ≥ 1 (default 1).
- A service is bookable only on resources explicitly linked to it as eligible.
- A service declares buffer-before and buffer-after in minutes, each ≥ 0 and
  defaulting to 0 (R31).
- A provider with zero resources or zero services returns no availability and
  accepts no bookings, rather than erroring at booking time.
- Policy values omitted at creation fall back to the system defaults of PD3.

### UC2 — Provider publishes recurring availability

**Actor:** Provider admin. **Goal:** Publish when a resource is bookable
without entering every day by hand.
**Main flow:** For a resource, define a rule: days of week, start and end time
of day, effective-from date, optional effective-until date. Repeat for other
patterns. The published schedule is the union of a resource's active rules.

**Acceptance criteria**
- A rule expands in the **provider's** timezone and remains correct across DST
  boundaries (R14): a rule for 09:00–17:00 local yields 09:00–17:00 local on
  both sides of a DST change.
- Two overlapping rules on the same resource union rather than duplicate — a
  time is available once or not at all.
- A rule with no effective-until is open-ended but only ever materialises
  availability out to the booking horizon (R5).
- Ending or editing a rule never cancels an existing booking; the response
  lists every non-terminal booking that now falls outside published
  availability (R20).

### UC3 — Provider adds a one-off exception

**Actor:** Provider admin. **Goal:** Close a holiday, or open an unusual
Saturday.
**Main flow:** Create an exception for a resource over a concrete date/time
range, of type BLOCK (removes availability) or OPEN (adds availability).

**Acceptance criteria**
- A BLOCK exception removes the range from availability even where a rule
  covers it; BLOCK wins over OPEN and over rules.
- An OPEN exception makes a range available even where no rule covers it.
- Creating a BLOCK over existing non-terminal bookings does not cancel them;
  the affected bookings are returned so the provider can act (R20).

### UC4 — Customer searches availability

**Actor:** Customer (unauthenticated browsing permitted).
**Goal:** See bookable start times for a service in a date range.
**Main flow:** Ask for provider + service + date range (+ optional resource
preference) → receive the list of start times that can actually be booked,
each with the resource(s) that could serve it and remaining capacity.

**Acceptance criteria**
- Every returned start time satisfies R1, R3, R4 and R5 at the moment of the
  response — a returned time is one that would be accepted by a booking made
  immediately.
- A start time whose full service duration does not fit inside published
  availability is not returned.
- A start time whose session would violate the buffer of an adjoining session
  is not returned, even though the time itself is unbooked (R31).
- A time at full capacity — counting HELD as well as CONFIRMED bookings — is
  not returned as available (R16).
- Results are expressed in UTC with the provider timezone stated, so a client
  can render local time unambiguously (R14).
- A search spanning more than the maximum queryable window is rejected with a
  clear error rather than silently truncated (window: PD7).

### UC5 — Customer books, instant-confirm provider

**Actor:** Customer. **Goal:** Reserve a time.
**Main flow:** Submit provider, service, start time, resource (or let the
system choose an eligible one, PD14), customer contact details, idempotency
key → capacity is taken and the booking is **CONFIRMED** in one transaction →
`BookingConfirmed` is published (R22).

**Acceptance criteria**
- Two concurrent requests for the last remaining unit of capacity result in
  exactly one CONFIRMED booking and one rejection — never two (NF4).
- Replaying the same idempotency key returns the original booking and creates
  no second one, and publishes no second event (R15).
- A booking violating R1, R3, R4, R5 or R31 is rejected with a reason
  identifying which rule failed.
- A buffer violation is rejected in every mode and is never offered to the
  provider as a decision (LD18).
- A short-notice request is rejected when the **customer** makes it and
  accepted when the **provider** makes it on the customer's behalf (R4).
- The response carries a stable booking id and its full state.
- The booking commits and its event is recorded atomically; a message-broker
  outage delays event delivery but never fails or delays the booking (R22).

### UC6 — Customer requests, approval-mode provider

**Actor:** Customer, then provider admin. **Goal:** Request a time and get a
decision.
**Main flow:** Same submission as UC5, but the booking is created **HELD** with
hold reason `AWAITING_PROVIDER_APPROVAL` and holds capacity → `BookingHeld` is
published → the provider approves (→ CONFIRMED) or declines (→ DECLINED,
capacity released) through this service's API → the outcome event is
published. If neither happens before the hold deadline, the system expires it
(UC11).

**Acceptance criteria**
- A HELD booking consumes capacity exactly as a CONFIRMED one does, so the
  same time cannot be over-requested.
- Approve and decline are rejected on any booking not in HELD, and on a HELD
  booking whose hold reason is not `AWAITING_PROVIDER_APPROVAL`.
- Approving a booking whose start time has already passed is rejected.
- The hold deadline is `min(now + the gate's TTL, booking start time)` (R8).
- The customer may withdraw a held request (→ CANCELLED) before a decision.

### UC7 — Cancellation

**Actor:** Customer or provider admin. **Goal:** Release a booking.
**Main flow:** Cancel a HELD or CONFIRMED booking with a reason → capacity is
released → `BookingCancelled` is published.

**Acceptance criteria**
- A customer cancellation later than the provider's cancellation window before
  start is refused, with the window stated in the error (R6).
- A provider may cancel at any time before start, and must supply a reason (R7).
- The resulting record states who cancelled and why.
- Cancelling a booking whose start time has passed is refused — attendance is
  recorded via UC9 instead (R27).
- Cancelling an already-terminal booking is refused (§4.1 forbidden
  transitions), and is distinguishable from "booking not found".

### UC8 — Reschedule

**Actor:** Customer or provider admin. **Goal:** Move a booking to another
time without losing it.
**Main flow:** Submit the booking id and a new start time (and optionally a
new resource) → old capacity released and new capacity taken atomically → the
booking keeps its id, gains a history entry, and `BookingRescheduled` is
published.

**Acceptance criteria**
- The operation is all-or-nothing: if the new time cannot be taken, the
  booking remains exactly as it was on its original time (R9).
- The target time must satisfy R1, R3, R4, R5, R31 — the same checks as a new
  booking (R10).
- Rescheduling never changes the booking id.
- On an approval-mode provider, a **customer**-initiated reschedule returns the
  booking to HELD/`AWAITING_PROVIDER_APPROVAL` for re-approval; a
  **provider**-initiated reschedule keeps it CONFIRMED (R11).
- Rescheduling a terminal booking is refused.
- Reschedules are counted, and a limit may be enforced later (PD5).

### UC9 — Provider records attendance

**Actor:** Provider admin. **Goal:** Record what actually happened.
**Main flow:** After the booking's end time, mark it COMPLETED or NO_SHOW →
the corresponding event is published.

**Acceptance criteria**
- Marking before the booking's end time is refused (R12).
- Only CONFIRMED bookings can be marked; terminal ones are refused.
- COMPLETED and NO_SHOW are terminal and cannot be swapped afterwards.

### UC10 — Viewing bookings

**Actor:** Customer, provider admin, resource-linked user.
**Goal:** See the relevant bookings.
**Main flow:** Customer lists their own upcoming and past bookings; provider
admin lists the provider's bookings filtered by resource, date range and
state; a resource-linked user sees only their own.

**Acceptance criteria**
- A customer never sees another customer's booking; a provider admin never
  sees another provider's (R18).
- Listings are paginated and ordered by start time deterministically.
- Each booking shows its current state, hold reason when HELD, times in UTC
  plus the provider timezone, service, resource, and its transition history.

### UC11 — System expires stale holds

**Actor:** System. **Goal:** Stop dead holds from holding capacity.
**Main flow:** Periodically, HELD bookings past their hold deadline become
EXPIRED, capacity is released, and `BookingExpired` is published.

**Acceptance criteria**
- Capacity held by an expired booking is released and immediately bookable.
- Expiry is idempotent and safe to run concurrently on multiple instances —
  a booking is expired at most once and publishes at most one event.
- A gate resolved in the same instant as expiry resolves to exactly one
  outcome, never both (R28).
- Expiry works identically for any hold reason, present or future (LD16).

### UC12 — System auto-completes unmarked bookings

**Actor:** System. **Goal:** Keep history truthful without nagging providers.
**Main flow:** CONFIRMED bookings whose end time is older than the
auto-complete grace period become COMPLETED, recorded as system-completed
rather than provider-marked, and published as `BookingCompleted`.

**Acceptance criteria**
- Auto-completion never overrides an explicit provider mark.
- The record and the event distinguish system auto-completion from a
  provider's mark.
- Auto-completion is idempotent across concurrent runs.

### UC13 — Publishing the booking lifecycle event stream

**Actor:** System. **Goal:** Let every other service in the estate react to
bookings without this service knowing they exist.
**Main flow:** Every committed booking state transition records an outbound
event; a dispatcher delivers those events to the message queue in one
versioned stream. Consumers (notification service, future payment service,
analytics) subscribe and filter.

**Acceptance criteria**
- Every transition in §4.1 produces exactly one event; no transition is
  silent, and no event is published for a transition that did not commit
  (R22).
- The event is recorded atomically with the transition, so an event can
  neither be lost after a committed booking nor delivered for a rolled-back
  one (R22).
- Delivery is at-least-once; each event carries a unique event id and the
  booking's transition sequence number so consumers can dedupe and order
  (R29).
- Events for the same booking are delivered in transition order (R29).
- A broker outage delays delivery and never rejects, delays, or rolls back a
  booking write (R22, NF3).
- The payload is self-describing and versioned; adding a consumer requires no
  change to this service (R29).
- No event carries payment, pricing, or notification-channel data (R21, LD9).

### UC14 — Resolving a hold via an external gate (designed now, built later)

**Actor:** An external service (the future payment service). **Goal:** Let a
decision made in another service confirm or release a held booking, without
this service learning what that decision was about.

**Intended flow, once a payment service exists:** customer books at a provider
that requires payment → this service creates the booking **HELD** with hold
reason `AWAITING_PAYMENT` and publishes `BookingHeld` → the payment service
consumes it and collects money → it publishes a gate resolution → this service
consumes the resolution and either confirms the booking or releases it,
publishing the outcome. The hold deadline bounds the whole exchange: if no
resolution arrives in time, the hold expires and the capacity is freed (UC11).

**In this scope:** the HELD state, hold reasons, per-gate TTLs, the internal
"resolve this gate" operation shared by the API and by any future consumer,
and the rejection/compensation rule (R28) are all built and tested. The
`AWAITING_PAYMENT` gate value, the inbound payment topic, and any payment data
are **not** built (LD10).

**Acceptance criteria**
- A hold reason is an extensible value; adding one requires no change to the
  state machine, the expiry job, or the capacity accounting (LD16).
- Provider approval (UC6) and an inbound gate resolution funnel through the
  same internal transition operation, so both obey identical guards and both
  publish identical outcome events.
- A gate resolution naming a booking that is no longer HELD — already expired,
  cancelled, declined, or confirmed — **does not change the booking**, and is
  published as a rejection event stating the booking's actual state, so the
  sender can compensate (e.g. refund) (R28).
- A gate resolution naming a hold reason other than the booking's current one
  is rejected the same way.
- Replayed resolutions are idempotent: the second delivery of the same
  resolution changes nothing and publishes no second outcome event (R29).
- No price, currency, payment status, or refund concept exists in this
  service's model, API, or events (R21).

---

### 4.1 Booking lifecycle

States: **HELD**, **CONFIRMED**, **CANCELLED**, **DECLINED**, **EXPIRED**,
**COMPLETED**, **NO_SHOW**.
Terminal: CANCELLED, DECLINED, EXPIRED, COMPLETED, NO_SHOW.
Capacity-holding: HELD and CONFIRMED only.

A **HELD** booking additionally carries a *hold reason* naming the single
external gate it awaits, and a *hold deadline* (R8, LD16). In this scope the
only hold reason is `AWAITING_PROVIDER_APPROVAL`.

| From | To | Trigger | Conditions |
|------|----|---------|-----------|
| _(none)_ | HELD | Customer/provider books where a gate applies (today: approval-mode provider) | R1, R3, R4, R5, R31, R15, R33 |
| _(none)_ | CONFIRMED | Customer/provider books where no gate applies (today: instant-mode provider) | R1, R3, R4, R5, R31, R15, R33 |
| HELD | CONFIRMED | Gate resolved positively — provider approves (API), or a consumed resolution (future) | Before start time, hold reason matches (R28) |
| HELD | DECLINED | Gate resolved negatively — provider declines (API), or a consumed resolution (future) | Before start time, hold reason matches (R28) |
| HELD | EXPIRED | System, hold deadline passed | R8 |
| HELD | CANCELLED | Customer withdraws / provider cancels | Before start time |
| HELD | HELD | Customer reschedules | R9, R10 — new time only, hold reason and deadline unchanged |
| CONFIRMED | CANCELLED | Customer cancels | Within cancellation window (R6) |
| CONFIRMED | CANCELLED | Provider cancels | Before start time, reason required (R7) |
| CONFIRMED | CONFIRMED | Provider reschedules | R9, R10 |
| CONFIRMED | HELD | Customer reschedules at approval-mode provider | R9, R10, R11 — hold reason `AWAITING_PROVIDER_APPROVAL` |
| CONFIRMED | COMPLETED | Provider marks | After end time (R12) |
| CONFIRMED | NO_SHOW | Provider marks | After end time (R12) |
| CONFIRMED | COMPLETED | System auto-completes | End time + grace period (R13) |

Every row above publishes exactly one event (UC13, R22).

**Forbidden transitions** (must be rejected, not silently ignored):

- Any transition out of a terminal state — including CANCELLED → CONFIRMED
  ("un-cancel"), DECLINED → CONFIRMED, EXPIRED → anything, and swapping
  COMPLETED ↔ NO_SHOW. **A late gate resolution is the realistic way this
  gets attempted, and it must fail loudly rather than resurrect (R28).**
- COMPLETED or NO_SHOW before the booking's end time.
- CANCELLED after the booking's start time — use UC9 instead (R27).
- HELD → COMPLETED or NO_SHOW without passing through CONFIRMED.
- Approve/decline by anyone other than an admin of the owning provider, or by
  a gate resolution naming a different gate than the booking's hold reason.
- Reschedule of a terminal booking.
- CONFIRMED → HELD for any reason other than a customer-initiated reschedule
  at an approval-mode provider (R11).

### 4.2 Other lifecycles

- **Resource:** ACTIVE ⇄ INACTIVE. An INACTIVE resource yields no availability
  and accepts no new bookings; its existing non-terminal bookings survive and
  must be cancelled or rescheduled explicitly. Deletion is forbidden while
  non-terminal future bookings exist (R19).
- **Service:** ACTIVE ⇄ INACTIVE, same rule. Changing a service's duration or
  capacity never mutates existing bookings — those keep the values they were
  booked with (R25).
- **Availability rule:** ACTIVE → ENDED (by setting effective-until). Ended
  rules are retained for audit; they stop producing availability from their end
  date. Rules are never hard-deleted while any booking references the period
  they justified (R20).
- **Outbound event:** PENDING_DISPATCH → DISPATCHED, with retry on failure. An
  event is never dropped; repeated failure is an alertable condition, not a
  silent discard (R22, NF11).

---

## 5. Business rules

Source is the requester interview of 2026-08-24 unless stated otherwise.

| id | Rule |
|----|------|
| R1 | A **session** is the tuple (resource, service, start, end): the unit a resource is actually occupied by. A resource hosts **at most one session at any instant** — two bookings on one resource that differ in service or in time range may never overlap at all. Within a single session, HELD + CONFIRMED bookings may coexist up to that service's capacity (R2). This is what "capacity N" means: N bookings sharing one session, never N overlapping sessions. |
| R2 | Service capacity defaults to 1 and must be ≥ 1. Capacity is a property of the service, applied per resource per time range. |
| R3 | A booking's entire time range must fall inside the resource's published availability: covered by an active rule or an OPEN exception, and not intersecting any BLOCK exception. Buffer is governed separately by R31 and R32. |
| R4 | A **customer-initiated** booking's start must be at least the provider's minimum lead time in the future. Default 60 minutes (PD3, resolved). A **provider-initiated** booking bypasses the lead time — the provider is present and consenting — but never bypasses R1, R3 or R31, which are physical rather than preferential. Booking in the past is rejected for everyone. |
| R5 | A booking's start must be no further ahead than the provider's booking horizon. Default 90 days (PD3, resolved). Unlike R4 this binds providers too: the horizon also bounds how far availability is computed (UC2), so a booking beyond it would sit outside computed availability. |
| R6 | A customer may cancel until the provider's cancellation window before start. Default 24 hours (PD3, resolved). Later than that, only the provider may cancel. |
| R7 | A provider may cancel any non-terminal booking before its start time and must supply a reason. |
| R8 | A HELD booking's hold deadline is `min(now + the gate's TTL, booking start time)`. The TTL is a property of the gate, not of the booking: provider-approval default 24 hours (PD3, resolved); a future payment gate would carry a much shorter one (PD16). |
| R9 | Reschedule is atomic: releasing the old time and taking the new one either both succeed or both have no effect. |
| R10 | A reschedule target is validated against exactly the same rules as a new booking (R1, R3, R4, R5, R31), against the booking's snapshotted values (R25). |
| R11 | A customer-initiated reschedule at an approval-mode provider returns the booking to HELD/`AWAITING_PROVIDER_APPROVAL`; a provider-initiated reschedule leaves it CONFIRMED. |
| R12 | COMPLETED and NO_SHOW may be set only after the booking's end time, and only by an admin of the owning provider (or by the system per R13). |
| R13 | A CONFIRMED booking still unmarked once its end time is older than the auto-complete grace period becomes COMPLETED, flagged as system-completed. Default grace 7 days (PD4). |
| R14 | All instants are stored and exchanged in UTC. Each provider declares an IANA timezone; recurring rules and exceptions are interpreted in that timezone and must expand DST-correctly. |
| R15 | Booking creation is idempotent on (customer, idempotency key): a replay returns the original booking, creates nothing new, and publishes no second event. |
| R16 | Availability presented to a customer must reflect committed state; a start time may not be shown as available while a HELD or CONFIRMED booking holds its last unit of capacity. |
| R17 | Caller identity is taken only from a verified IdP token. The service stores no passwords, password hashes, or credentials, and issues no auth tokens (LD3). |
| R18 | A customer may read and modify only their own bookings; a provider admin only entities belonging to their own provider. Cross-tenant access is denied, and denial is indistinguishable from absence for other tenants' records. |
| R19 | A resource or service with non-terminal future bookings may be deactivated but never deleted. |
| R20 | Editing or ending an availability rule, or adding a BLOCK exception, never auto-cancels bookings. Bookings that fall outside published availability as a result are returned to the provider as conflicts to resolve. |
| R21 | This service holds no price, currency, payment, invoice, or refund data, performs no payment operation, and puts no such field in any event (LD10). |
| R22 | An outbound event is recorded **atomically with the state transition that caused it** — a committed transition can never lack its event, and no event may exist for an uncommitted one. Delivery to the broker is asynchronous and at-least-once: broker unavailability delays delivery and must never fail, block, or roll back a booking write. |
| R23 | Every booking state transition is recorded immutably with actor, timestamp, from-state, to-state, and reason. History is append-only and never rewritten. |
| R24 | A resource may optionally be linked to an IdP subject, granting that person read access to that resource's bookings only. |
| R25 | A booking's duration, capacity and buffer values are those of the **session it joins** — which, for the first booking into a session, are the service's values at that moment. A later booking joining an existing session inherits that session's values: everyone in one class attends the same class. Editing a service never alters an existing session or booking. |
| R26 | Availability is derived from rules, exceptions and existing bookings; it is never edited directly. There is no API to "mark a slot taken" outside of booking. |
| R27 | A booking may not be cancelled once its start time has passed. After start, the only remaining transitions are the attendance ones of R12. |
| R28 | A gate resolution — from the API or from a consumed event — applies only to a booking that is currently HELD **with a matching hold reason**. Against any other state or reason it changes nothing, and is reported back as a rejection stating the booking's actual state, so the sender can compensate. A late resolution never resurrects a terminal booking. |
| R29 | Events are versioned and self-describing. Each carries a unique event id and a per-booking transition sequence number; delivery is at-least-once, ordered per booking id. Consuming a duplicate must be a no-op. Adding or removing a consumer requires no change to this service. |
| R30 | This service never sends email, SMS, or push, holds no recipient template or channel preference, and has no notification vendor dependency (LD9). |
| R31 | Each service declares buffer-before and buffer-after in minutes (≥ 0, default 0). Two **distinct** sessions on the same resource must be separated by at least the **sum** of the adjoining buffers — the earlier session's buffer-after plus the later one's buffer-before — because cleanup and setup are distinct activities on one resource and cannot happen at once. A provider wanting a specific total gap sets one side and leaves the other at zero. Bookings within one session never buffer against each other (R1). Buffer time is reserved on the resource and is never offered as bookable time. |
| R32 | A booking's own time range must fit inside published availability (R3); its **buffer need not** — a buffer may extend past the end of published hours. Buffer still blocks other sessions wherever it falls. |
| R33 | Which gate, if any, a new booking must pass is decided by **evaluating the provider's booking policy at creation time**, never by reading a static mode flag. Today that policy yields `AWAITING_PROVIDER_APPROVAL` for approval-mode providers and no gate otherwise. Adding a condition later — holding short-notice requests rather than rejecting them — is then a new policy rule, not a state-machine change (LD16, PD19). |

---

## 6. Integrations

| System | Direction | What it provides / consumes | Notes |
|--------|-----------|------------------------------|-------|
| **External IdP** (OIDC) | Inbound, synchronous | Verified subject id, and claims identifying whether the caller is a customer or a provider admin | Issuer and claim mapping not yet chosen (PD1). Tokens are validated; credentials are never stored (R17). |
| **Message queue** | Outbound | The booking lifecycle event stream — every transition of §4.1, plus gate-resolution rejections (R28) | The estate's messaging backbone. Broker technology is a phase-2/3 decision (PD15). This service publishes and does not know its consumers (LD14). |
| **Message queue** | Inbound | Gate-resolution events that confirm or release a HELD booking (UC14) | The mechanism is built and tested in this scope; the only *producer* envisaged (payment) is not built (LD10). |
| **Notification service** | Indirect, via the queue | Consumes booking events and delivers messages | Not built here; no direct coupling, no shared code, no synchronous call (LD9, R30). |
| **Payment service** | Indirect, via the queue, future | Would consume `BookingHeld` and produce a gate resolution | Not built here and not built yet. Ordering intent recorded in UC14. |

**Anticipated future, not requirements:** external calendar sync
(Google/Outlook two-way), waitlists for full time ranges, and a marketplace-wide
provider search. Each is noted so the design does not actively preclude it,
but none is built in this scope (§9).

---

## 7. Non-functionals

| id | Requirement |
|----|-------------|
| NF1 | **Criticality:** user-facing production service. A booking failure is visible to a paying business's customers. Target availability 99.9% monthly for the read/availability path and the booking write path. |
| NF2 | **Sizing (interim, PD6):** ~500 providers, ~2 000 resources, ~10 000 bookings/day, peak ~50 req/s availability search and ~10 req/s booking writes; ~3× growth expected within 12 months. Design must not require re-architecture at 3×. |
| NF3 | **Latency:** availability search p95 < 300 ms, p99 < 800 ms; booking create/cancel/reschedule p95 < 500 ms, measured server-side and **excluding** broker delivery, which is off the request path (R22). |
| NF4 | **Concurrency correctness:** double-booking must be impossible under concurrent requests. Prevention must rest on a store-enforced guarantee (constraint, atomic conditional write, or serialised transaction), never on a check-then-write in application code. This is the single most important correctness property of the service. |
| NF5 | **Data sensitivity:** the service holds PII — names, email addresses, phone numbers, and a behavioural history of appointments. Access is tenant-scoped (R18), PII is encrypted in transit and at rest, and never written to logs. Event payloads carry **no** PII at all — ids only, with consumers resolving contact details themselves (PD17, resolved). Export and erasure on request must be possible. Retention interim: 24 months after a customer's last booking (PD9, Legal). |
| NF6 | **Deployment:** Kubernetes, cloud-agnostic (LD11). Service instances are stateless and horizontally scalable; scheduled work (UC11, UC12) and event dispatch (UC13) must be safe with multiple instances active. |
| NF7 | **Observability:** structured logs with a correlation id per request, metrics for booking outcomes and rejection reasons by rule, and distributed tracing propagated **through** the event stream, so a booking and the reactions it triggered can be traced end to end. |
| NF8 | **API:** versioned HTTP/JSON, backwards-compatible within a major version. Errors identify the violated rule id where one applies. |
| NF9 | **Durability:** no committed booking and no event for a committed transition may be lost. RPO ≤ 5 minutes, RTO ≤ 1 hour (interim, PD10). |
| NF10 | **Testability:** every acceptance criterion in §4 must be expressible as an automated test, including the concurrency ones (NF4), the DST one (R14, UC2), and the late-gate-resolution one (R28, UC14). |
| NF11 | **Event delivery health:** undispatched events are observable and alertable. Dispatch lag p95 < 5 s under normal operation; sustained lag or repeated dispatch failure raises an alert rather than accumulating silently. |

---

## 8. Environment context

- **Runtime target:** Kubernetes, cloud-agnostic (LD11). No dependency on
  vendor-specific managed services in the design; a managed Postgres-class
  database and a message broker may be assumed to exist alongside.
- **Cloud:** not fixed — the design must run on any of them.
- **Existing estate:** none. This is a greenfield repository with no sibling
  services, no shared contracts, and no house conventions to mirror. The
  microservice estate described in §6 is intended, not existing: this service
  is the first of it, so it defines the event contract rather than adopting one.
- **Team stack and language preference:** not stated; deliberately left to the
  phase-3 gate (PD11).

---

## 9. Out of scope

Explicitly excluded from this build. An agent must not helpfully add them.

- **Payments in every form** — pricing, checkout, capture, deposits, refunds,
  payouts, invoices (LD10, R21). Only the gate mechanism of UC14 exists, with
  no payment gate value and no payment topic.
- **Notification delivery** — email, SMS, push, templates, channel preferences,
  quiet hours, vendor integration (LD9, R30). This service publishes events;
  something else sends messages.
- **Authentication and account issuance** — registration, passwords, tokens,
  sessions, password reset, MFA. Owned by the external IdP (LD3).
- **Reviews, ratings, and reputation.**
- **Waitlists / standby queues** for full time ranges.
- **External calendar sync** (Google, Outlook, CalDAV), in either direction.
- **Marketplace discovery** — provider search, ranking, recommendations,
  geo-search. Availability search within a *known* provider is in scope (UC4);
  finding a provider is not.
- **Messaging/chat between customer and provider.**
- **Analytics, reporting, or BI beyond the listing endpoints of UC10.**
- **Any frontend, mobile app, or admin UI.** This is a backend service only.
- **Group bookings made by one customer for several attendees** — a booking
  takes exactly one unit of capacity (LD15).
- **The other services named in §6.** The notification service and the payment
  service are consumers this service is designed for, not deliverables of this
  build.

---

## 10. Pending decisions & open questions

Each is either resolved, or carries a stated interim assumption the design
proceeds on. None is a bare "TBD".

### Resolved by the requester on 2026-08-24

| id | Question | Ruling |
|----|----------|--------|
| PD2 | Notification vendor, and whether SMS is in scope | **Neither.** Notification delivery leaves this service entirely; it publishes events to the queue and a notification service consumes them (LD9, LD14, R30). |
| PD3 | Booking policy defaults, and whether they are per provider | **Per provider, with system defaults** 60 min lead time / 90-day horizon / 24 h cancellation window / 24 h approval hold (R4, R5, R6, R8). |
| PD8 | Whether the payment handoff fires on held or confirmed bookings | **Superseded.** The service publishes *every* lifecycle transition as one stream and consumers filter (LD14, UC13). The payment flow is hold → publish → external decision → consume → confirm (UC14). |
| PD11 | Language, framework, runtime | **Resolved at the phase-3 gate:** Kotlin 2.3.21 + Spring Boot 4.1.1 (Spring MVC on virtual threads) on Temurin 25 LTS, built with Gradle 9.7.1. Selected on service fit with team familiarity excluded by the requester's instruction. See stack-selection.md §3 and §7. |
| PD12 | May one booking take several units of capacity? | **No.** One booking = one unit; three seats means three bookings (LD15). |
| PD16 | TTL for a future payment gate | **15 minutes, system-wide, when that gate is built.** Recorded so R8's TTL stays a per-gate property rather than a single constant. |
| PD17 | How much customer PII belongs in an event payload | **Ids only.** Consumers resolve contact details themselves; no name, email or phone leaves this service in an event (NF5, R29). |
| PD18 | Can provider approval arrive asynchronously, as a consumed event? | **No.** Provider approval is a synchronous API action; only non-human gates arrive as events (UC14). |
| PD19 | Should a short-notice or buffer-violating request be offerable to the provider rather than rejected? | **Split.** Buffer: never offerable, locked (LD18) — an override would cost R31 its store-enforced status. Short notice: rejected for customers, but a **provider-initiated** booking bypasses minimum lead time (R4), which covers the phone-call case without a two-tier availability response. Gate selection is policy-evaluated (R33), so building true short-notice holds later stays additive. |
| PD20 | Should a provider have an "auto-accept when there is no conflict" mode? | **No new mode needed** — a conflicting booking is never created in either mode, so this is exactly instant-confirm (LD2). Short-notice and buffer-violating requests are handled by R4 and R31 as hard rejections (PD19). |
| PD15 | Which message broker product, and what ordering guarantee it gives per booking id (R29) | **Resolved at the phase-3 gate: Apache Kafka 4.3.1** (Apache-2.0), client via Spring Kafka 4.1.1. Chosen against the capability contract in architecture-design.md §6.1 because partition-by-key delivers ordering per key *and* horizontal consumer parallelism with one mechanism. NATS JetStream is the pre-vetted fallback. See stack-selection.md §4. |

### Still open

| id | Question | Interim assumption | Decider | Needed by |
|----|----------|--------------------|---------|-----------|
| PD1 | Which IdP / token format (OIDC issuer, claim names, how provider-admin role is asserted)? | Standard OIDC with JWT bearer tokens; `sub` is the stable subject; role and provider membership resolved locally from the subject, not from a vendor claim | Tech + Business | Phase 2 |
| PD4 | Auto-complete grace period, and whether NO_SHOW should ever be automatic | 7 days, always auto-completing to COMPLETED, never NO_SHOW (R13) | Business | Phase 1 |
| PD5 | Is there a limit on how many times one booking may be rescheduled? | No limit in v1; the count is recorded so a limit can be added without a model change (UC8) | Business | Phase 1 |
| PD6 | Real sizing and growth figures | The NF2 figures | Business | Phase 2 |
| PD7 | Maximum date range a single availability search may span | 62 days per query, paginated beyond that (UC4) | Tech | Phase 2 |
| PD9 | PII retention period and erasure obligation (which regulations apply) | 24 months after the customer's last booking; erasure supported on request (NF5) | Legal | Phase 1 |
| PD10 | RPO/RTO targets, and whether multi-region is required | RPO 5 min, RTO 1 h, single region (NF9) | Business + Tech | Phase 2 |
| PD13 | May the same customer hold two bookings that overlap in time? | Allowed — the service does not police a customer's personal calendar | Business | Phase 1 |
| PD14 | When several resources are eligible and the customer expresses no preference, how is one chosen? | Deterministic least-loaded-then-lowest-id selection, so the choice is testable and stable | Tech | Phase 1 |

---

## Traceability note for downstream phases

- **Data design (phase 1)** can name entities directly from §4 and §5:
  Provider, Resource, Service (with duration, capacity and buffer), service↔
  resource eligibility, AvailabilityRule, AvailabilityException, Booking (with
  hold reason and hold deadline),
  BookingTransition (R23), CustomerProfile, and an outbound-event record
  (R22). Lifecycles are in §4.1/§4.2; write paths are UC5, UC6, UC7, UC8, UC9,
  UC11, UC12, and the gate resolution of UC14. **NF4 and R1 are the design's
  central constraint** — the capacity/overlap guarantee must be store-enforced,
  and how availability is represented (materialised time rows vs. computed
  overlap checks) is a phase-1 decision this document deliberately leaves open.
  R1's **session** is likewise a modelling choice, not a mandate: it may become
  a real row that bookings hang off, or stay an implied grouping — but whichever
  is chosen must make R1 (one session per resource at a time, N bookings per
  session) and R31 (buffer between sessions, never within one) store-enforced.
  **R22 is the second structural constraint**: the transition and its event
  must commit together, which is a data-layer decision, not an afterthought.
- **Architecture (phase 2)** can read integrations from §6, the event stream
  and its guarantees from UC13/UC14/R22/R28/R29, scheduled work from
  UC11/UC12, and criticality and scale from §7. The event catalogue it produces
  must cover every transition in §4.1 plus the rejection event of R28.
- **Stack selection (phase 3)** can read the environment from §8, with the
  language decision recorded as open (PD11) and the broker as PD15.
