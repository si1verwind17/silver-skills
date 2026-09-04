---
name: backend-stack-selection
description: >-
  Use when choosing or validating the tech stack for a backend service —
  programming language, framework, and runtime — including assessing whether
  the team's existing stack fits a new service, pairing a stack with a
  serverless or Kubernetes target, checking ecosystem/library maturity, or
  comparing candidate stacks for a mission-critical workload.
---

# Backend stack selection

Choosing the language/framework/runtime for a backend service. Core rule:
**this is not language shopping** — the existing stack wins by default, and
alternatives enter only when the existing stack poses a concrete issue for
this service. Anything perishable (runtime performance, serverless support,
library maturity, product landscape) is **researched at decision time with a
web check — never answered from this skill's or the model's memory.**

## The deliverable

`docs/backend-design/stack-selection.md` (beside the architecture design)
unless the user specifies otherwise:

1. **Existing-stack assessment** — what the team runs today and whether it
   fits this service's shape (from the architecture doc's decisions table).
2. **Candidate matrix** — only when alternatives are warranted: candidates ×
   criteria (use-case fit, deployment-target fit incl. cold start,
   required-integration ecosystem check, team familiarity, cost, runtime
   safety). The matrix, the ecosystem table, and the finalized-versions
   table each carry a **Source column**: every perishable cell — cold-start
   figures, maturity/maintenance status, version numbers — names the URL or
   source it was verified against, with the date. A cell without a source
   is an unverified claim, not a finding.
3. **Recommendation with rationale** — and for mission-critical services, an
   explicit pros/cons comparison of the existing stack vs **a named,
   concretely evaluated alternative** (a real stack you researched, never a
   hypothetical "some lighter option").
4. **Finalized versions** — pin the exact version of **every** chosen
   component: language, framework, major libraries, base runtime, build
   tool and plugins — researched now, not deferred. "Latest", version
   ranges (`1.x`), and "confirm at implementation time" are not pins; if a
   version genuinely can't be verified, name it and say why.

## Existing stack first

When the chain provides `docs/backend-design/context.md`, the
existing-stack assessment starts from it (stacks/versions in use, listed
constraints and warts) instead of interviewing from zero — re-verify
anything stale, and treat its warts per the orchestrator's rule (never
copied, divergences recorded). Without it, interview as below.

Start from what the user already runs; the goal is to *compile with the
existing stack*, not to find the theoretically best one. Propose new
candidates only when the existing stack poses a concrete issue for this
service — a cold-start mismatch with the chosen deployment target, a missing
or immature required library, or a shape mismatch (below). Prefer
open-source candidates, but don't dismiss a good paid option.

## What to ask the user

Ask only what the architecture/data-design docs and the repo can't answer:

- Cloud provider (or on-prem/local) and existing deployment platform — is
  there already a k8s cluster? Is serverless in use?
- Current production languages/frameworks and their versions.
- Team skills and size — who maintains this service?
- Organizational constraints: mandated standards, licensing/budget stance,
  compliance requirements.

Deployment target, statefulness, and traffic shape come from the
architecture doc — don't re-ask them.

## Match the stack's weight to the service's shape

The recurring mis-selection is momentum or novelty, not ignorance — the
default stack applied to a service that doesn't need it, or a shiny stack
applied where the default belonged:

- **Heavy domain logic, user-facing** → an expressive, strongly typed stack
  on a stable always-on runtime. Compile-time safety pays for itself here:
  what the compiler catches never 503s a user.
- **Thin async glue** (a subscriber that transforms and forwards) → a
  lightweight serverless-friendly runtime with push consumption; giving it
  the full heavyweight stack and a 24/7 pull deployment is paying rent for
  an empty room.
- **Integration flows** (routing, protocol bridging, many endpoints) → an
  integration framework earns its learning curve *only* when the work is
  genuinely integration-shaped; it does not speed up domain logic, and its
  velocity gains require deep framework fluency first.
- **Protocol/codegen tooling is part of the stack**: if a design leans on
  generated contracts (protobuf/OpenAPI), verify the *chosen language's*
  tooling for it is mature before committing — a DX feature with immature
  tooling costs more DX than it buys. (Opinion: for backend↔frontend
  contract sharing, REST + OpenAPI codegen usually beats gRPC — browser
  gRPC needs a proxy layer.)

## The ecosystem disqualifier

List the integrations this service *requires* — cloud SDKs, DB drivers,
queue clients, crypto, domain-specific libraries — and verify (by research,
now) that the candidate has mature, maintained support for each. A stack
that forces a migration later because a required integration never existed
is the most expensive selection mistake; young languages have historically
shipped without even major cloud SDKs.

## Team experience is the tie-break — with warnings

No existing stack and no user preference? Recommend from team experience
and business requirements. When they conflict (team knows a stack that fits
the requirement poorly), **go with team experience and say the risks out
loud** — name specifically what the familiar stack will struggle with
(throughput ceiling, runtime failures the compiler won't catch, cold
starts) so the user accepts the trade knowingly.

## Availability and cost

High availability for user-facing services justifies always-on Kubernetes
over serverless when the runtime cold-starts poorly — repeated 503s cost
more than nodes. Spot/preemptible instances are for **stateless and async
workloads only — never user-facing paths**. State the cost consequence of
each pairing in the matrix.

## Before delivering — verify

- [ ] Existing stack assessed first; alternatives appear only with a named
      concrete issue.
- [ ] The elicitation questions (current versions, licensing/budget, org
      standards) are recorded with stated assumptions — even when you
      proceeded without answers.
- [ ] Every perishable claim (cold start, ecosystem maturity, versions) has
      a decision-time research citation, not a memory answer.
- [ ] Every required integration has a verified mature library for the
      recommended stack.
- [ ] The recommendation names its risks explicitly when team experience
      overrode requirement fit.
- [ ] Finalized versions pinned for every selected component.
- [ ] Mission-critical service → existing-vs-recommended pros/cons table
      present.

## Common mistakes

- Language shopping when the existing stack was fine — or momentum applying
  the default stack to a thin service that needed something lighter.
- Heavy domain logic placed in an integration framework or on a cold-start-
  prone serverless runtime because that's where the last service went.
- Choosing a stack for a codegen/protocol feature without checking that
  language's tooling maturity.
- Trusting benchmark blogs or the model's memory instead of decision-time
  research.
- Leaving versions unpinned, so the implementing agent guesses.
- User-facing workloads on spot instances.
