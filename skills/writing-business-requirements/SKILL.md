---
name: writing-business-requirements
description: >-
  Use when turning a business ask into structured requirements before any
  design starts — interviewing a stakeholder from a fuzzy idea, digesting an
  existing PRD or business document into agent-workable form, or checking
  whether requirements are complete enough to begin data/architecture design.
  General-purpose: feeds building-backend-services phase 0 and any future
  build flow.
---

# Writing business requirements

Turn a business perspective into requirements an agent can build from. Two
entry modes, one output: **interview** (the ask is fuzzy — elicit) and
**digest** (a PRD or business doc exists — translate it into the uniform
format, preserving its intent and citing it). The deliverable is the input
contract for the design phases that follow.

## The deliverable

`docs/backend-design/requirements.md` unless the user specifies otherwise.
Every use case, rule, and non-functional carries a **stable id** (UC1, R1,
NF1) so downstream documents can trace to it ("write path implements R3").
Sections, in order:

1. **Summary & business goal** — what this is and why the business wants
   it, in a few sentences. Context, not marketing.
2. **Locked decisions** — choices already made that agents must not
   re-litigate (these become the "approved" baseline the build flow's
   backtracking rule protects).
3. **Actors & roles** — who interacts and what they're trying to do; a
   roles-and-permissions table when the system has operator/back-office
   users.
4. **Use cases** — `UC<n>`: actor, goal, main flow, and **acceptance
   criteria** (verifiable statements; these seed the implementation
   phase's tests). Lifecycle states and their transitions are spelled out
   here — downstream data design models them directly.
5. **Business rules** — `R<n>`: one testable statement each, with its
   source (stakeholder, PRD section, law/policy). Hard guardrails and
   non-goals phrased as rules ("no purchase path anywhere", "single CTA").
6. **Integrations** — external systems touched, direction, and what each
   consumes or provides.
7. **Non-functionals** — `NF<n>`: criticality (mission-critical?
   user-facing?), expected sizing and growth, SLA/latency expectations,
   compliance and data sensitivity (PII, consent, retention).
8. **Environment context** (when known) — cloud, existing estate, team —
   pass-through for stack selection; skip rather than invent.
9. **Out of scope** — explicit exclusions, so agents don't helpfully build
   them.
10. **Pending decisions & open questions** — a table with a **decider**
    column (Business / Tech / Legal / Data / named person). A vague point
    is never "TBD": it is either resolved, or assigned to a decider with
    your stated interim assumption.

## Translation rules (business language → agent-workable)

- Every vague phrase becomes exactly one of: a testable rule (R), a locked
  decision, or a pending decision with a decider. Nothing survives as
  ambient prose an agent must interpret.
- Marketing language is context for §1 only — it never enters use cases or
  rules.
- Numbers a stakeholder can't give yet are recorded as pending with the
  decider named ("attribution window: proposed 30 days — Business+Data to
  confirm"), not invented.
- When digesting an existing PRD: preserve its decided/undecided split
  faithfully, cite the source section per item, and do not silently
  upgrade its wishes ("might want analytics later") into requirements —
  those land as anticipated-future notes on the relevant use case or
  integration.

## Elicitation order (interview mode)

Ask in this order and stop when the stop rule below is met — don't
interrogate exhaustively:

1. Actors and their goals — who touches this and why.
2. The happy path of each core use case, then its failure/edge cases.
3. Lifecycle: what states does the core thing move through, and which
   transitions are forbidden?
4. Business rules and hard guardrails — what must always/never happen?
5. Integrations — what existing systems react to or feed this?
6. Criticality, sizing, growth expectations (this drives how much
   structure every downstream phase applies).
7. Compliance and data sensitivity.
8. What is explicitly out of scope?

## The stop rule — "enough to start design"

Requirements are ready for the design phases when:

- Data design can name the **entities, their lifecycle states, and the
  write paths** from the use cases alone.
- Architecture can read the **integrations, reaction/event needs, and
  criticality** without asking again.
- Stack selection can read the **environment context** (or sees it marked
  unknown-and-asked).
- Everything still unknown is in §10 **with a decider** — not floating.

Anything beyond that level of detail belongs to the design phases, not
here.

## Before delivering — verify

- [ ] Every use case has acceptance criteria; every rule is a single
      testable statement with a source.
- [ ] Every id (UC/R/NF) is unique and referenced consistently.
- [ ] Lifecycle states and forbidden transitions are explicit.
- [ ] No vague phrase remains outside §1 — each became a rule, a locked
      decision, or a pending decision with a decider.
- [ ] Sizing/growth and criticality are stated or explicitly pending.
- [ ] Out-of-scope section exists and is non-empty (an empty one usually
      means it wasn't asked).

## Common mistakes

- Interrogating past the stop rule — requirements that pre-decide the
  design.
- "TBD" without a decider; wishes silently promoted to requirements.
- Acceptance criteria that restate the flow instead of being verifiable.
- Missing forbidden transitions (only the happy lifecycle captured).
- Marketing adjectives inside rules ("fast", "seamless") instead of
  measurable non-functionals.
- Out-of-scope never asked, discovered at implementation time.
