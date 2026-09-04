---
name: rendering-design-docs
description: >-
  Use when turning a set of design/requirements documents (requirements.md,
  data-design.md, architecture-design.md, stack-selection.md, or any domain's
  equivalent doc set) into a single HTML review page for a human reader —
  typically to present a review gate. The page is for people only; agents
  keep consuming the markdown.
---

# Rendering design docs

Produce one polished HTML page a human can open, read top-to-bottom, and
act on at a review gate. The markdown documents remain the **only source of
truth**: the page is generated from them, regenerated on any change, never
hand-edited, and never read by agents.

## The deliverable

`design-review.html`, written beside the doc set it renders (the same
`docs/backend-design/` — or the repo-root docs dir when rendering a
multi-service system view). One file:

- **Self-contained** styling: inline CSS, readable typography, no external
  stylesheets. Diagrams: prefer pre-rendering Mermaid blocks to inline SVG
  when a renderer is available locally — then the page works fully
  offline. A Mermaid CDN include is the acceptable fallback; it is the
  one permitted external dependency, and it means diagrams need network —
  say so in the page's footer note.
- Opens with an HTML comment and a visible footer note: *generated from
  the markdown sources on <date> — do not edit; regenerate instead; agents
  must consume the markdown, not this page.*

## Page structure (in order)

1. **Header** — project/service name, generation timestamp, and a source
   list: every rendered document with its **status badge** (APPROVED /
   DRAFT / missing) taken from the doc's own status line.
2. **Gate summary — the action panel.** Aggregate across *all* rendered
   docs: every pending decision and open question, each with its source
   document, its decider, and the interim assumption in force. This is
   what the human must act on; it comes first, not buried per-doc.
3. **Per-document sections**, one per source doc, in chain order
   (requirements → data design → architecture → stack selection →
   others), each with:
   - all headings, tables, and lists faithfully rendered;
   - every Mermaid block rendered as a diagram;
   - SQL/DDL and code in syntax-styled code blocks;
   - the doc's own open questions kept in place (the gate summary links
     to them, it doesn't replace them).
4. **Navigation** — a table of contents (sidebar or top) linking every
   section; stable ids (UC1, R3, NF2…) become anchors, and references to
   an id elsewhere link to its anchor.

## Faithfulness rules

- Render what the documents say — **never add, reword, summarize away, or
  "fix" content**. A rendering decision (grouping, layout) is yours; a
  content decision is not.
- Numbers, versions, and statuses appear exactly as in the sources.
- A source doc that is missing or unparseable is shown as such in the
  header — not silently skipped.
- The markdown files are not modified by rendering, ever.

## Before delivering — verify

- [ ] One HTML file, inline CSS, opens standalone (fully offline with
      pre-rendered SVG; with the CDN fallback, standalone except diagrams —
      and the footer says so); every Mermaid block from the sources appears
      as a rendered diagram.
- [ ] Gate summary lists every pending decision/open question from every
      rendered doc, with source + decider.
- [ ] Every table and section present in the sources is present in the
      page; spot-check numbers and versions unchanged.
- [ ] Status badges match each doc's own status line.
- [ ] TOC present; every stable id resolves as an anchor.
- [ ] Source markdown files untouched; generated-for-humans notice present.

## Common mistakes

- Summarizing or paraphrasing source content instead of rendering it.
- Burying open questions per-doc with no aggregated action panel.
- External CSS/font/JS dependencies beyond the single diagram script.
- Editing the HTML by hand to fix a content issue that belongs in the
  markdown.
- Rendering a stale page at a gate — regenerate after every doc change.
