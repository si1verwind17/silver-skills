#!/usr/bin/env node
// Renders a reviewing-code-by-aspect ledger (markdown) into a self-contained
// human-facing HTML page with a threshold-banded score chart.
// The markdown stays the only source of truth: regenerate, never hand-edit.
//   node <skill>/scripts/render_review.mjs <ledger>.md [<out>.html]
// Output defaults beside the ledger. No dependencies beyond Node itself.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

//   node <skill>/scripts/render_review.mjs <ledger>.md --json [<out>.json]
//     → machine-readable export (summary table, open findings, candidates,
//       history); no <out> writes to stdout.
const argv = process.argv.slice(2);
const jsonMode = argv.includes('--json');
const positional = argv.filter(a => a !== '--json');
const src = resolve(positional[0] ?? 'review.md');
const out = positional[1] ? resolve(positional[1]) : (jsonMode ? null : src.replace(/\.md$/, '.html'));
const md = readFileSync(src, 'utf8');
// Stamp every view with the ledger it was built from, so staleness is
// detectable without trusting file timestamps.
const sourceSha = createHash('sha256').update(md).digest('hex');

// Fixed aspect catalog order (stable chart axes across reviews).
const CATALOG = ['LOG','ERR','SEC','PAT','CON','CMP','LGG','MEM','CPU','STA','TST','TSQ','TYP','RDB','FMT'];

// ---------- parse the summary score table ----------
const aspects = [];
for (const line of md.split('\n')) {
  const m = line.match(/^\|\s*([^|]*?)\s*\((\w{3})\)\s*\|\s*\*\*([\d.]+)\*\*\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|$/);
  if (m) aspects.push({ name: m[1], abbr: m[2], score: parseFloat(m[3]), worst: m[4], findings: m[5] });
}
if (aspects.length === 0) { console.error('no score table found'); process.exit(1); }
// Catalog order; custom aspects (not in the catalog) keep their ledger order at the end.
const rank = (a, i) => { const k = CATALOG.indexOf(a.abbr); return k === -1 ? CATALOG.length + i : k; };
const origIdx = new Map(aspects.map((a, i) => [a.abbr, i]));
aspects.sort((a, b) => rank(a, origIdx.get(a.abbr)) - rank(b, origIdx.get(b.abbr)));

const avgMatch = md.match(/Average[^:]*:\s*\*\*([\d.]+)\*\*/);
const headMatch = md.match(/^## Summary — (.+)$/m);

// ---------- parse the History table (timeline slicer frames) ----------
// One frame per review row. Scores only — the ledger tracks open findings just
// for the latest review, so historic frames degrade honestly (bars + bands).
let frames = null;
{
  const lines = md.split('\n');
  const hi = lines.findIndex(l => /^\|\s*Date\s*\|\s*Hash\s*\|/i.test(l));
  if (hi !== -1) {
    const header = lines[hi].replace(/^\||\|$/g, '').split('|').map(c => c.trim());
    const rows = [];
    for (let j = hi + 1; j < lines.length && lines[j].startsWith('|'); j++) {
      const cells = lines[j].replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      if (cells.every(c => /^:?-+:?$/.test(c))) continue;
      const row = { date: cells[0], hash: cells[1], avg: null, scores: {} };
      header.forEach((h, k) => {
        if (/^[A-Z]{3}$/.test(h)) { const v = parseFloat(cells[k]); if (!Number.isNaN(v)) row.scores[h] = v; }
        else if (/^Avg$/i.test(h)) row.avg = cells[k];
      });
      if (row.date && row.hash) rows.push(row);
    }
    if (rows.length >= 2) frames = rows;
  }
}
if (frames) {
  const last = frames[frames.length - 1];
  for (const a of aspects) {
    if (last.scores[a.abbr] !== undefined && last.scores[a.abbr] !== a.score)
      console.warn(`warning: history last row disagrees with summary for ${a.abbr} (${last.scores[a.abbr]} vs ${a.score}); latest frame uses the summary score`);
    last.scores[a.abbr] = a.score; // summary table is authoritative for the latest frame
  }
}
const knownIds = new Set();
for (const m of md.matchAll(/\b([A-Z]{3}-\d{3})\b/g)) knownIds.add(m[1]);

// ---------- JSON export ----------
// Parses the Open findings section: per-aspect headers, finding lines
// (**ABBR-NNN · C|M|m · isolated|widespread — title**) and their `file:line`
// occurrences; a "candidate" sub-block or a finding marked candidate is
// exported separately and never counted. Parsed from the same markdown the
// HTML uses, so the two views cannot disagree.
if (jsonMode) {
  const lines = md.split('\n');
  const start = lines.findIndex(l => /^## Open findings/i.test(l));
  const end = lines.findIndex((l, i) => i > start && /^## /.test(l));
  const findings = [];
  const candidates = [];
  let aspect = null, cur = null, inCandidates = false;
  const TIER = { C: 'critical', M: 'major', m: 'minor' };
  for (const raw of (start === -1 ? [] : lines.slice(start + 1, end === -1 ? undefined : end))) {
    const line = raw.trim();
    const ah = line.match(/^###\s+([^(]+)\((\w{3})\)/);
    if (ah) { aspect = ah[2]; cur = null; inCandidates = false; continue; }
    if (/^(\*\*|#{4,}\s*)?candidates?\b/i.test(line)) { inCandidates = true; cur = null; continue; }
    const fh = line.match(/^\*\*([A-Z]{3}-\d{3})\s*·\s*([CMm])\s*·\s*(isolated|widespread)\s*[—-]+\s*(.+?)\*\*\s*$/);
    const ch = !fh && inCandidates && line.match(/^[-*]\s+\*?\*?(.+?)\*?\*?\s*$/);
    if (fh) {
      cur = { id: fh[1], aspect: aspect ?? fh[1].slice(0, 3), tier: TIER[fh[2]], widespread: fh[3] === 'widespread', title: fh[4], occurrences: [], candidate: inCandidates };
      (inCandidates ? candidates : findings).push(cur);
      continue;
    }
    if (ch) {
      const c = ch[1].match(/^(?:([A-Z]{3})\s*·\s*)?(?:([CMm])\s*·\s*)?(.+?)(?:\s*·\s*`[^`]+`.*)?$/);
      cur = { aspect: (c && c[1]) || aspect, tier: c && c[2] ? TIER[c[2]] : null, title: c ? c[3] : ch[1], occurrences: [], candidate: true };
      for (const m of ch[1].matchAll(/`([^`\s]+:\d+(?:-\d+)?)`/g)) cur.occurrences.push(m[1]);
      candidates.push(cur); continue;
    }
    if (cur) for (const m of line.matchAll(/`([^`\s]+:\d+(?:-\d+)?)`/g)) cur.occurrences.push(m[1]);
  }
  const result = {
    source: basename(src),
    source_sha256: sourceSha,
    summary: headMatch ? headMatch[1] : null,
    average: avgMatch ? parseFloat(avgMatch[1]) : null,
    aspects: aspects.map(a => ({ name: a.name, abbr: a.abbr, score: a.score, worst: a.worst, open: a.findings })),
    findings,
    candidates,
    history: frames ?? [],
  };
  const text = JSON.stringify(result, null, 2) + '\n';
  if (out) { ensureGitignore(out); writeFileSync(out, text); console.error(`wrote ${out}`); } else process.stdout.write(text);
  process.exit(0);
}

// Derived views are not committed: drop a .gitignore beside the output when
// the directory has none. An existing .gitignore is never touched — a user
// who removed the ignore lines has chosen to commit a view.
function ensureGitignore(target) {
  const gi = join(dirname(target), '.gitignore');
  if (existsSync(gi)) return;
  const tpl = join(dirname(fileURLToPath(import.meta.url)), '..', 'template', 'gitignore');
  const body = existsSync(tpl) ? readFileSync(tpl, 'utf8') : '*.html\n*.json\n';
  writeFileSync(gi, body);
  console.error(`wrote ${gi} (derived views ignored; edit it to commit a view)`);
}

// ---------- markdown → HTML (subset, faithful) ----------
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function inline(s, { selfId = null } = {}) {
  s = esc(s);
  const codes = [];
  s = s.replace(/`([^`]+)`/g, (_, c) => { codes.push(c); return `${codes.length - 1}`; });
  s = s.replace(/\*\*([^*]+(?:\*(?!\*)[^*]*)*)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[\s(—>])\*([^*\n]+)\*(?=[\s.,;:)—]|$)/g, '$1<em>$2</em>');
  s = s.replace(/(^|\s)_([^_]+)_(?=\s|$|[.,;:])/g, '$1<em>$2</em>');
  let first = true;
  s = s.replace(/\b([A-Z]{3}-\d{3})\b/g, (id) => {
    if (!knownIds.has(id)) return id;
    if (selfId && id === selfId && first) { first = false; return id; }
    return `<a class="xref" href="#${id}">${id}</a>`;
  });
  s = s.replace(/(\d+)/g, (_, i) => `<code>${codes[+i]}</code>`);
  return s;
}

const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').split('-').slice(0, 3).join('-');
const FINDING_RE = /^\*\*([A-Z]{3}-\d{3}) · /;
// Two header forms exist: legacy "— score 3" and template "— 3/10 · 4 open".
const ASPECT_H_RE = /^([^(]+)\((\w{3})\)\s*—\s*(?:score\s+([\d.]+)|([\d.]+)\/10\b.*)$/;

function mdToHtml(text) {
  const lines = text.split('\n');
  const htm = [];
  const toc = [];
  let i = 0;
  const paraBuf = [];
  const flushPara = () => {
    if (!paraBuf.length) return;
    const joined = paraBuf.join(' ');
    paraBuf.length = 0;
    const f = joined.match(FINDING_RE);
    if (f) { htm.push(`<h4 class="finding" id="${f[1]}">${inline(joined.replace(/^\*\*|\*\*$/g, ''), { selfId: f[1] })}</h4>`); return; }
    htm.push(`<p>${inline(joined)}</p>`);
  };
  while (i < lines.length) {
    const line = lines[i];
    if (/^\s*$/.test(line)) { flushPara(); i++; continue; }
    if (/^(-{3,}|\*{3,})\s*$/.test(line)) { flushPara(); i++; continue; } // ledger's --- rules: the page draws its own aspect separators
    let h;
    if ((h = line.match(/^(#{1,3}) (.*)$/))) {
      flushPara();
      const level = h[1].length, txt = h[2];
      if (level === 1) { i++; continue; } // page header supplies the title
      const asp = level === 3 ? txt.match(ASPECT_H_RE) : null;
      const id = asp ? `aspect-${asp[2]}` : slug(txt);
      if (level === 2) toc.push({ id, title: txt.replace(/ — .*$/, '') });
      if (asp) htm.push('<hr class="aspect-rule">');
      htm.push(`<h${level + 1} id="${id}">${inline(txt)}</h${level + 1}>`);
      i++; continue;
    }
    if (line.startsWith('|')) {
      flushPara();
      const rows = [];
      while (i < lines.length && lines[i].startsWith('|')) rows.push(lines[i++]);
      const cells = rows.map(r => r.replace(/^\||\|$/g, '').split('|').map(c => c.trim()));
      const body = cells.filter(r => !r.every(c => /^:?-+:?$/.test(c)));
      const [head, ...rest] = body;
      htm.push('<div class="tablewrap"><table>');
      htm.push('<thead><tr>' + head.map(c => `<th>${inline(c)}</th>`).join('') + '</tr></thead>');
      htm.push('<tbody>' + rest.map(r => '<tr>' + r.map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('\n') + '</tbody>');
      htm.push('</table></div>');
      continue;
    }
    if (line.startsWith('- ')) {
      flushPara();
      const items = [];
      while (i < lines.length && lines[i].startsWith('- ')) {
        let item = lines[i].slice(2); i++;
        while (i < lines.length && /^\s+\S/.test(lines[i]) && !lines[i].trim().startsWith('- ')) { item += ' ' + lines[i].trim(); i++; }
        items.push(item);
      }
      htm.push('<ul>' + items.map(it => `<li>${inline(it)}</li>`).join('\n') + '</ul>');
      continue;
    }
    paraBuf.push(line.trim());
    i++;
  }
  flushPara();
  return { html: htm.join('\n'), toc };
}

// ---------- chart (static inline SVG) ----------
const band = (s) => (s <= 3 ? 'noship' : s >= 8 ? 'ship' : 'fix');
const BAND = {
  noship: { color: '#d03b3b', icon: '✕', label: 'do not ship' },
  fix:    { color: '#fab219', icon: '⚠', label: 'schedule fixes' },
  ship:   { color: '#0ca30c', icon: '✓', label: 'ship' },
};

const GEO = { labelW: 200, plotW: 520, barH: 12 };

function chart() {
  const { labelW, plotW, barH } = GEO, chipX = labelW + plotW + 56, W = chipX + 128;
  const top = 34, pitch = 28, H = top + aspects.length * pitch + 30;
  const x = (v) => labelW + (v / 10) * plotW;
  const p = [];
  p.push(`<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Per-aspect review scores, 0 to 10, with ship thresholds" font-family="system-ui,-apple-system,'Segoe UI',sans-serif">`);
  // band shading + boundary lines + band labels (position carries the band, not color alone)
  const zones = [[0, 3, 'noship'], [3, 8, 'fix'], [8, 10, 'ship']];
  for (const [a, b, k] of zones) {
    p.push(`<rect x="${x(a)}" y="${top - 4}" width="${x(b) - x(a)}" height="${H - top - 22}" fill="${BAND[k].color}" opacity="0.055"/>`);
    p.push(`<text x="${(x(a) + x(b)) / 2}" y="${top - 12}" text-anchor="middle" font-size="11" class="ink-muted">${BAND[k].icon} ${BAND[k].label}${k === 'noship' ? ' ≤3' : k === 'fix' ? ' 4–7' : ' ≥8'}</text>`);
  }
  for (const t of [3, 8]) p.push(`<line x1="${x(t)}" y1="${top - 6}" x2="${x(t)}" y2="${H - 24}" class="threshold"/>`);
  for (let t = 0; t <= 10; t += 2) {
    if (t !== 0) p.push(`<line x1="${x(t)}" y1="${top - 4}" x2="${x(t)}" y2="${H - 26}" class="grid"/>`);
    p.push(`<text x="${x(t)}" y="${H - 10}" text-anchor="middle" font-size="10.5" class="ink-muted">${t}</text>`);
  }
  p.push(`<line x1="${labelW}" y1="${top - 4}" x2="${labelW}" y2="${H - 26}" class="axisline"/>`);
  aspects.forEach((a, r) => {
    const y = top + r * pitch, cy = y + pitch / 2, k = band(a.score), w = (a.score / 10) * plotW;
    const rr = Math.min(4, w);
    p.push(`<g class="row" data-abbr="${a.abbr}" data-cy="${cy}" data-name="${esc(a.name)}" data-score="${a.score}" data-worst="${esc(a.worst)}" data-findings="${esc(a.findings)}" data-band="${BAND[k].icon} ${BAND[k].label}">`);
    p.push(`<rect x="0" y="${y}" width="${W}" height="${pitch}" fill="transparent" class="hit"/>`);
    p.push(`<text x="${labelW - 10}" y="${cy + 4}" text-anchor="end" font-size="12.5" class="ink-secondary">${esc(a.name)} <tspan class="ink-muted" font-size="10.5">${a.abbr}</tspan></text>`);
    p.push(`<path d="M${labelW},${cy - barH / 2} h${w - rr} a${rr},${rr} 0 0 1 ${rr},${rr} v${barH - 2 * rr} a${rr},${rr} 0 0 1 ${-rr},${rr} h${-(w - rr)} z" fill="${BAND[k].color}" class="bar"/>`);
    p.push(`<text x="${labelW + w + 7}" y="${cy + 4}" font-size="12" font-weight="600" class="ink-primary num score">${a.score}</text>`);
    p.push(`<text x="${chipX}" y="${cy + 4}" font-size="11" class="ink-secondary bandchip">${BAND[k].icon} ${BAND[k].label}</text>`);
    p.push('</g>');
  });
  p.push('</svg>');
  return p.join('\n');
}

// ---------- page ----------
const { html: bodyHtml, toc } = mdToHtml(md);
const criticals = aspects.filter(a => a.worst.includes('critical'));
const generated = new Date().toISOString().slice(0, 10);
// Repo name = nearest ancestor of the ledger containing .git; fallback: ledger's grandparent dir.
let repoName = null;
for (let d = dirname(src); d !== dirname(d); d = dirname(d)) {
  if (existsSync(join(d, '.git'))) { repoName = basename(d); break; }
}
if (!repoName) { // no .git ancestor: skip conventional doc dirs so "docs" never names the project
  let d = dirname(src);
  while (['docs', 'review'].includes(basename(d)) && d !== dirname(d)) d = dirname(d);
  repoName = basename(d);
}
const h1 = (md.match(/^# (.+)$/m) || [, 'Code Review Ledger'])[1];
const title = `${repoName} — ${h1.toLowerCase()}`;

const page = `<!doctype html>
<!-- generated from ${basename(src)} on ${generated} — do not edit; regenerate with render_review.mjs instead. Agents must consume the markdown, not this page. -->
<html lang="en"><head>
<meta name="source-sha256" content="${sourceSha}"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>
:root { color-scheme: light;
  --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink-2:#52514e; --ink-muted:#898781;
  --grid:#e1e0d9; --axis:#c3c2b7; --ring:rgba(11,11,11,.10); --code-bg:#f0efec; --accent:#2a78d6; }
@media (prefers-color-scheme: dark) { :root:where(:not([data-theme="light"])) { color-scheme: dark;
  --page:#0d0d0d; --surface:#1a1a19; --ink:#ffffff; --ink-2:#c3c2b7; --ink-muted:#898781;
  --grid:#2c2c2a; --axis:#383835; --ring:rgba(255,255,255,.10); --code-bg:#242423; --accent:#3987e5; } }
:root[data-theme="dark"] { color-scheme: dark;
  --page:#0d0d0d; --surface:#1a1a19; --ink:#ffffff; --ink-2:#c3c2b7; --ink-muted:#898781;
  --grid:#2c2c2a; --axis:#383835; --ring:rgba(255,255,255,.10); --code-bg:#242423; --accent:#3987e5; }
* { box-sizing: border-box; }
body { margin:0; background:var(--page); color:var(--ink);
  font:15px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif; }
.layout { max-width:1240px; margin:0 auto; padding:32px 20px; display:grid; grid-template-columns:230px minmax(0,1fr); gap:36px; }
@media (max-width:1000px){ .layout{ grid-template-columns:minmax(0,1fr);} nav.toc{ position:static; max-height:none;} }
nav.toc { position:sticky; top:24px; align-self:start; font-size:13.5px; max-height:calc(100vh - 48px); overflow:auto; }
nav.toc a { display:block; color:var(--ink-2); text-decoration:none; padding:3px 10px; border-left:2px solid var(--grid); }
nav.toc a:hover { color:var(--ink); border-left-color:var(--accent); }
nav.toc .toc-title { font-weight:600; color:var(--ink); margin:0 0 6px; }
header.page h1 { font-size:23px; margin:0 0 4px; }
header.page .meta { color:var(--ink-muted); font-size:13px; }
main section.card, .panel { background:var(--surface); border:1px solid var(--ring); border-radius:10px; padding:22px 26px; margin:18px 0; }
h2 { font-size:19px; margin:26px 0 10px; } h3 { font-size:16.5px; margin:22px 0 8px; }
h4.finding { font-size:14.5px; margin:20px 0 6px; font-weight:600; }
h4.finding::before { content:""; }
hr.aspect-rule { border:0; border-top:1px solid var(--grid); margin:26px 0 4px; }
code { background:var(--code-bg); border-radius:4px; padding:1px 5px; font-size:.86em;
  font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; overflow-wrap:anywhere; }
a.xref { color:var(--accent); text-decoration:none; } a.xref:hover { text-decoration:underline; }
.tablewrap { overflow-x:auto; margin:12px 0; }
table { border-collapse:collapse; font-size:13.5px; min-width:100%; }
th,td { text-align:left; padding:6px 12px; border-bottom:1px solid var(--grid); white-space:nowrap; }
td:last-child { white-space:normal; min-width:220px; }
th { color:var(--ink-2); font-weight:600; } tbody tr:hover { background:var(--code-bg); }
ul { padding-left:22px; } li { margin:5px 0; }
.gate { display:flex; flex-wrap:wrap; gap:10px; margin:10px 0 2px; }
.gate .chip { border:1px solid var(--ring); border-radius:999px; padding:4px 12px; font-size:13px; background:var(--code-bg); }
.gate .chip b { font-variant-numeric:tabular-nums; }
figure.viz { margin:0; } figure.viz svg { width:100%; height:auto; display:block; }
.ink-primary { fill:var(--ink); } .ink-secondary { fill:var(--ink-2); } .ink-muted { fill:var(--ink-muted); }
.num { font-variant-numeric:tabular-nums; }
svg .grid { stroke:var(--grid); stroke-width:1; } svg .axisline { stroke:var(--axis); stroke-width:1; }
svg .threshold { stroke:var(--axis); stroke-width:1.25; stroke-dasharray:3 3; }
svg .bar { stroke:var(--ring); stroke-width:1; }
svg .row:hover .hit { fill:var(--code-bg); } svg .row .hit { transition:fill .1s; }
figcaption { color:var(--ink-muted); font-size:12.5px; margin-top:8px; }
.timeline { margin:14px 0 2px; }
.timeline input[type=range] { width:100%; accent-color:var(--accent); margin:0; }
.timeline-meta { display:flex; justify-content:space-between; align-items:baseline; gap:12px;
  font-size:12px; color:var(--ink-muted); font-variant-numeric:tabular-nums; }
#hist-label { font-size:13px; font-weight:600; color:var(--ink-2); text-align:center; }
svg .row.nohist { opacity:.35; }
svg .row .bar, svg .row .score, svg .row .bandchip { transition:opacity .1s; }
#tooltip { position:fixed; display:none; z-index:10; background:var(--surface); color:var(--ink);
  border:1px solid var(--ring); border-radius:8px; box-shadow:0 4px 16px rgba(0,0,0,.18);
  padding:10px 12px; font-size:12.5px; max-width:340px; pointer-events:none; }
#tooltip .t-head { font-weight:600; margin-bottom:2px; }
#tooltip .t-sub { color:var(--ink-2); }
footer.gen { color:var(--ink-muted); font-size:12.5px; margin:28px 0 8px; border-top:1px solid var(--grid); padding-top:12px; }
</style></head><body>
<div class="layout">
<nav class="toc"><p class="toc-title">${title.split('—')[0].trim()}</p>
<a href="#scores">Score chart</a>
${toc.map(t => `<a href="#${t.id}">${esc(t.title)}</a>`).join('\n')}
${aspects.map(a => `<a style="padding-left:22px" href="#aspect-${a.abbr}">${esc(a.name)} — ${a.score}</a>`).join('\n')}
</nav>
<main>
<header class="page">
  <h1>${esc(h1)} — ${esc(repoName)}</h1>
  <div class="meta">Source: <code>${basename(src)}</code> · review: ${headMatch ? esc(headMatch[1]) : ''} · page generated ${generated}</div>
</header>

<section class="panel" id="gate">
  <h2 style="margin-top:0">Gate summary</h2>
  <div class="gate">
    <span class="chip">✕ do-not-ship aspects: <b>${aspects.filter(a => band(a.score) === 'noship').map(a => a.abbr).join(', ') || 'none'}</b></span>
    <span class="chip">open criticals: <b>${criticals.map(a => (a.findings.match(/[A-Z]{3}-\d{3}(?= C)/g) || []).join(', ')).filter(Boolean).join(', ') || 'none'}</b></span>
    <span class="chip">average (trend only): <b>${avgMatch ? avgMatch[1] : '—'}</b></span>
  </div>
  <p style="color:var(--ink-2);font-size:13.5px;margin-bottom:0">The verdict is per-aspect, never the average. Details in the summary and per-aspect findings below.</p>
</section>

<section class="card" id="scores">
  <h2 style="margin-top:0">Per-aspect scores @ ${headMatch ? esc(headMatch[1].split(' ')[0]) : ''}</h2>
  <figure class="viz">
  ${chart()}
  ${frames ? `<div class="timeline">
    <input type="range" id="histrange" min="0" max="${frames.length - 1}" step="1" value="${frames.length - 1}" aria-label="Review history position (${frames.length} reviews, latest selected)">
    <div class="timeline-meta"><span>${esc(frames[0].date)}</span><span id="hist-label"></span><span>${esc(frames[frames.length - 1].date)}</span></div>
  </div>` : ''}
  <figcaption>Aspects in fixed catalog order (comparable across reviews). Bands: ≥8 ship · 4–7 schedule fixes · ≤3 do not ship. Exact values and finding IDs in the summary table below; hover a row for its open findings.${frames ? ' Drag the timeline to replay earlier reviews — open-finding detail is tracked only for the latest.' : ''}</figcaption>
  </figure>
</section>

<section class="card">
${bodyHtml}
</section>

<footer class="gen">Generated from <code>${basename(src)}</code> on ${generated} — do not edit this page; regenerate with the <em>reviewing-code-by-aspect</em> skill's <code>scripts/render_review.mjs</code> instead. Agents must consume the markdown ledger, not this page. Fully self-contained; no external dependencies.</footer>
</main>
</div>
<div id="tooltip"></div>
<script>
const tip = document.getElementById('tooltip');
const FRAMES = ${frames ? JSON.stringify(frames) : 'null'};
const GEO = ${JSON.stringify(GEO)};
const BANDS = ${JSON.stringify(BAND)};
const bandOf = (s) => (s <= 3 ? 'noship' : s >= 8 ? 'ship' : 'fix');
const rows = [...document.querySelectorAll('svg .row')];
let atLatest = true;

function applyFrame(i) {
  const f = FRAMES[i];
  atLatest = i === FRAMES.length - 1;
  for (const row of rows) {
    const s = f.scores[row.dataset.abbr];
    row.classList.toggle('nohist', s === undefined);
    if (s === undefined) continue; // custom aspect with no history column: dim, keep latest bar
    const cy = +row.dataset.cy, w = (s / 10) * GEO.plotW, rr = Math.min(4, w), b = BANDS[bandOf(s)];
    const bar = row.querySelector('.bar');
    bar.setAttribute('d', 'M' + GEO.labelW + ',' + (cy - GEO.barH / 2) + ' h' + (w - rr) +
      ' a' + rr + ',' + rr + ' 0 0 1 ' + rr + ',' + rr + ' v' + (GEO.barH - 2 * rr) +
      ' a' + rr + ',' + rr + ' 0 0 1 ' + (-rr) + ',' + rr + ' h' + (-(w - rr)) + ' z');
    bar.setAttribute('fill', b.color);
    const num = row.querySelector('.score');
    num.setAttribute('x', GEO.labelW + w + 7);
    num.textContent = s;
    row.querySelector('.bandchip').textContent = b.icon + ' ' + b.label;
    row.dataset.curScore = s;
    row.dataset.curBand = b.icon + ' ' + b.label;
  }
  const label = document.getElementById('hist-label');
  if (label) label.textContent = f.date + ' @ ' + f.hash + ' · avg ' + (f.avg || '—') + (atLatest ? ' (latest)' : '');
}
const range = document.getElementById('histrange');
if (range && FRAMES) { range.addEventListener('input', () => applyFrame(+range.value)); applyFrame(FRAMES.length - 1); }

for (const row of rows) {
  row.addEventListener('mousemove', (e) => {
    const d = row.dataset;
    if (row.classList.contains('nohist')) {
      tip.innerHTML = '<div class="t-head">' + d.name + ' (' + d.abbr + ')</div>' +
        '<div class="t-sub">no history for this aspect at this review point</div>';
    } else if (atLatest) {
      tip.innerHTML = '<div class="t-head">' + d.name + ' (' + d.abbr + ') — ' + d.score + '/10</div>' +
        '<div class="t-sub">' + d.band + ' · worst open: ' + d.worst + '</div>' +
        '<div class="t-sub">open: ' + d.findings + '</div>';
    } else {
      tip.innerHTML = '<div class="t-head">' + d.name + ' (' + d.abbr + ') — ' + (d.curScore || d.score) + '/10</div>' +
        '<div class="t-sub">' + (d.curBand || d.band) + '</div>' +
        '<div class="t-sub">open findings are listed only for the latest review</div>';
    }
    tip.style.display = 'block';
    const pad = 14, vw = window.innerWidth;
    let x = e.clientX + pad; if (x + 340 > vw) x = e.clientX - 340 - pad;
    tip.style.left = Math.max(8, x) + 'px';
    tip.style.top = (e.clientY + pad) + 'px';
  });
  row.addEventListener('mouseleave', () => { tip.style.display = 'none'; });
}
</script>
</body></html>`;

ensureGitignore(out);
writeFileSync(out, page);
console.log(`wrote ${out} (${aspects.length} aspects, ${knownIds.size} finding ids)`);
