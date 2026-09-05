# silver-skills

Agent skills for scored code review and for building backend services from
requirements. Each skill is a plain-Markdown `SKILL.md` in the open Agent
Skills format, usable from Claude Code, GitHub Copilot, Antigravity CLI,
Cursor, Codex and any other host that reads it.

What these skills add, compared with asking an agent without them:

- **Reviews that end, and that you can compare over time.** Every finding has
  a severity tier and evidence. Every aspect gets a 0–10 score computed from
  its findings. A ledger file is kept between reviews, so the next review
  checks the open findings instead of finding them again.
- **The facts the code cannot tell you, written down once.** Which modules
  exist, what external systems really do, units and fees, the scale it runs
  at, the trust model, and the risks the owner has decided to accept. Every
  line says where it came from, so a reviewer rates severity from facts, not
  guesses.
- **A design process with checkpoints.** Requirements, data design,
  architecture and stack choice are separate documents with stable ids, and a
  person reviews each one before the next step starts and before any code is
  written.

## Install

The skills follow the [Agent Skills](https://agentskills.io) layout
(`skills/<name>/SKILL.md`), so any host that reads that layout can install them.

**Claude Code** — as a marketplace with two plugins:

```
/plugin marketplace add si1verwind17/silver-skills
/plugin install aspect-code-review@silver
/plugin install backend-development@silver
```

**GitHub Copilot, Cursor, Codex and others via GitHub CLI** — installs into the
shared `.agents/skills/` directory of the current repo; see `gh skill install
--help` for a user-wide install:

```
gh skill install si1verwind17/silver-skills
```

**Antigravity CLI (`agy`, the successor of Gemini CLI)** — reads the same
`.agents/skills/` directory, so the `gh skill install` line above installs for
it too. For a user-wide install, copy the `skills/<name>/` directories into
`~/.antigravity/skills/`. `/skills` inside `agy` lists what it loaded.

**Anything else** — copy the `skills/<name>/` directories you want into your
agent's skills directory (`.agents/skills/`, `.claude/skills/`, `.github/skills/`,
`.codex/skills/`, or the user-level equivalent).

After installing, you ask in plain words and the agent picks the matching
skill from its description; the "Try" prompts under each plugin show what to
say. The domain-context skill sends questions about intended behavior to
`writing-business-requirements`, so install both plugins, or copy both sets of
skills, if you want that to work.

Prerequisites: none for the skills themselves. The two HTML renderers
(`rendering-design-docs`, and the review ledger's `render_review.mjs`) need
Node 18 or later.

## Plugins

### `aspect-code-review`

Scored, documented code review by aspect with a persistent ledger, plus the owner-confirmed domain-context document the review reads first.

```
/plugin install aspect-code-review@silver
```

- [`reviewing-code-by-aspect`](skills/reviewing-code-by-aspect/SKILL.md) — Reviews a codebase, a diff or a PR one aspect at a time — logic, error handling, security, concurrency, contracts and more — with severity tiers, a 0–10 score per aspect, and a ledger that persists across reviews so the next run re-checks open findings instead of starting over.
- [`documenting-domain-context`](skills/documenting-domain-context/SKILL.md) — Writes down what the code cannot say — module map, external-party outcomes, units and fees, operating envelope, trust model, accepted hazards — as a provenance-tagged file the review reads first, confirmed with the owner in two short batches of questions.

Try:

> Write the domain context for this repo; I'm the owner, ask me what you need.

> Review this repository by aspect and give me a scored ledger.

> Quick review of this PR, no ledger.


### `backend-development`

Requirements to implementation for backend services: business requirements, data design, architecture, stack selection, code conventions, and a human-readable design-review page, orchestrated end to end.

```
/plugin install backend-development@silver
```

- [`writing-business-requirements`](skills/writing-business-requirements/SKILL.md) — Turns a fuzzy business ask or an existing PRD into structured requirements with stable ids, locked decisions and owner-assigned open questions.
- [`building-backend-services`](skills/building-backend-services/SKILL.md) — Orchestrates the whole chain — requirements, data design, architecture, stack selection, implementation — as phases with a human review gate between each.
- [`backend-design-by-data`](skills/backend-design-by-data/SKILL.md) — Designs the data layer from the requirements: tables or documents, DDL, constraints and indexes, idempotent and atomic write paths, schema evolution.
- [`backend-architecture`](skills/backend-architecture/SKILL.md) — Draws service boundaries, decides where state lives, sync versus async interactions, events and topics, caching, and serverless versus Kubernetes.
- [`backend-stack-selection`](skills/backend-stack-selection/SKILL.md) — Chooses or validates language, framework and runtime against the team's existing stack, the deployment target and library maturity.
- [`backend-code-conventions`](skills/backend-code-conventions/SKILL.md) — Language-neutral conventions for service code: layers, business versus technical logic, error handling, logging, naming, unit and integration tests.
- [`rendering-design-docs`](skills/rendering-design-docs/SKILL.md) — Renders a design-document set into one HTML page for a human review gate; agents keep reading the Markdown.

Try:

> Build a backend service from these requirements, with a review gate after each phase.

> Turn this PRD into structured requirements before we design anything.

> Render docs/backend-design into a single review page.


## Examples

Complete outputs produced by these skills. They live outside `skills/` so
that installers do not mistake them for skills.

### `examples/booking-service`

A backend service produced end to end by the `backend-development` chain — requirements, data design, architecture, stack selection, then the Kotlin service and its tests — and then reviewed by `aspect-code-review`. It is an example, not a product: its own README says do not deploy it, and the two critical findings from the review were left unfixed on purpose.

- [`README.md`](examples/booking-service/README.md) — what the example is and what the review found
- [`docs/backend-design/`](examples/booking-service/docs/backend-design/) — requirements, data design, architecture, stack selection, DDL, and the rendered `design-review.html`
- [`docs/domain-context.md`](examples/booking-service/docs/domain-context.md) — the owner-confirmed domain context
- [`docs/review/review.md`](examples/booking-service/docs/review/review.md) — the scored ledger, with `review.html` beside it

## Using the skills with other agents

Every skill is a self-contained Markdown file with no tool-specific
instructions and no dependency on the marketplace manifest. Give any agent the
path to a `SKILL.md` and it can follow it.

## License

MIT — see `LICENSE`.
