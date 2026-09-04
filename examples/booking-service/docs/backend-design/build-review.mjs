// Builds design-review.html from the markdown sources in this directory.
//
// The markdown files are the only source of truth.  This script never writes to
// them.  Regenerate after any documentation change:  node build-review.mjs
//
// Mermaid blocks are pre-rendered to inline SVG with @mermaid-js/mermaid-cli, so
// the resulting page works fully offline with no external dependency at all.

import { readFileSync, writeFileSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { marked } from 'marked';

const DOCS = [
  { file: 'requirements.md',        title: 'Requirements',        phase: 'Phase 0' },
  { file: 'data-design.md',         title: 'Data Design',         phase: 'Phase 1' },
  { file: 'architecture-design.md', title: 'Architecture Design', phase: 'Phase 2' },
  { file: 'stack-selection.md',     title: 'Stack Selection',     phase: 'Phase 3' },
];
const APPENDICES = [
  { file: 'data-dictionary.yaml',   title: 'data-dictionary.yaml', lang: 'yaml' },
  { file: 'ddl/01-create-tables.sql', title: 'ddl/01-create-tables.sql', lang: 'sql' },
  { file: 'ddl/02-create-indexes.sql', title: 'ddl/02-create-indexes.sql', lang: 'sql' },
  { file: 'ddl/03-functions.sql',   title: 'ddl/03-functions.sql', lang: 'sql' },
  { file: 'ddl/04-seed-lookups.sql', title: 'ddl/04-seed-lookups.sql', lang: 'sql' },
];
const ID_RE = /\b(?:UC|R|NF|PD|LD|OQ|AQ)\d{1,3}\b/g;

const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');

/* ---------- mermaid: render each block and namespace its ids ---------- */
let diagramCount = 0;
const tmp = mkdtempSync(join(tmpdir(), 'mmd-'));
writeFileSync(join(tmp,'pc.json'), JSON.stringify({args:['--no-sandbox','--disable-gpu','--disable-dev-shm-usage']}));

function renderMermaid(src) {
  const k = ++diagramCount;
  const inF = join(tmp, `d${k}.mmd`), outF = join(tmp, `d${k}.svg`);
  writeFileSync(inF, src);
  try {
    execFileSync('npx', ['--yes','@mermaid-js/mermaid-cli','-p',join(tmp,'pc.json'),'-i',inF,'-o',outF,'-b','transparent'],
                 { stdio: 'pipe' });
  } catch (e) {
    console.error(`  diagram ${k}: RENDER FAILED`);
    return `<pre class="code">${esc(src)}</pre>`;
  }
  let svg = readFileSync(outF,'utf8').replace(/<\?xml[^>]*\?>/,'');
  // Every mmdc SVG uses id="my-svg" with styles scoped to it, so inlining
  // several would collide.  Namespace every id and every reference to one.
  const pfx = `d${k}-`;
  const ids = [...new Set([...svg.matchAll(/\sid="([^"]+)"/g)].map(m=>m[1]))]
              .sort((a,b)=>b.length-a.length);
  for (const id of ids) {
    const e = id.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
    svg = svg.replace(new RegExp(`(\\sid=")${e}(")`,'g'), `$1${pfx}${id}$2`)
             .replace(new RegExp(`url\\(#${e}\\)`,'g'), `url(#${pfx}${id})`)
             .replace(new RegExp(`((?:xlink:)?href=")#${e}(")`,'g'), `$1#${pfx}${id}$2`);
  }
  svg = svg.replace(/#my-svg(?![\w-])/g, `#${pfx}my-svg`)
           .replace(/background-color:\s*white/g, 'background-color: transparent');
  return `<figure class="diagram">${svg}</figure>`;
}

/* ---------- tiny syntax styling ---------- */
function highlight(code, lang) {
  let h = esc(code);
  if (lang === 'sql') {
    h = h.replace(/(--[^\n]*)/g, '<span class="c">$1</span>')
         .replace(/('(?:[^']|'')*')/g, '<span class="s">$1</span>')
         .replace(/\b(CREATE|TABLE|INDEX|UNIQUE|PRIMARY|KEY|FOREIGN|REFERENCES|CONSTRAINT|CHECK|EXCLUDE|USING|GIST|WHERE|SELECT|INSERT|INTO|VALUES|UPDATE|SET|DELETE|FROM|JOIN|LEFT|LATERAL|ON|AND|OR|NOT|NULL|DEFAULT|BEGIN|COMMIT|COMMENT|FUNCTION|RETURNS|LANGUAGE|DECLARE|IF|THEN|ELSE|END|RAISE|EXCEPTION|LOOP|RETURN|CONFLICT|DO|NOTHING|ORDER|BY|LIMIT|GROUP|HAVING|EXISTS|CASE|WHEN|COALESCE|EXTENSION|TRIGGER|BEFORE|AFTER|FOR|EACH|ROW|EXECUTE|GENERATED|ALWAYS|AS|IDENTITY|WITH|DISTINCT|CROSS|UNION|ALL)\b/g,
                  '<span class="k">$1</span>');
  } else if (lang === 'yaml') {
    h = h.replace(/(^|\n)(\s*#[^\n]*)/g, '$1<span class="c">$2</span>')
         .replace(/(^|\n)(\s*)([\w.-]+)(:)/g, '$1$2<span class="k">$3</span>$4');
  } else if (lang === 'json') {
    h = h.replace(/(\/\/[^\n]*)/g, '<span class="c">$1</span>')
         .replace(/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="k">$1</span>$2');
  }
  return h;
}

/* ---------- gate summary: parsed from the markdown, not restated ---------- */
function tableRows(md, startMarker, endMarker) {
  const a = md.indexOf(startMarker);
  // A silently-empty result would drop real decisions out of the gate summary,
  // which is the one thing this page exists to show.  Fail instead.
  if (a < 0) throw new Error(`gate summary: table header not found: ${startMarker}`);
  const seg = md.slice(a, endMarker ? (md.indexOf(endMarker, a) < 0 ? md.length : md.indexOf(endMarker, a)) : md.length);
  return seg.split('\n')
    .filter(l => /^\|\s*(\*\*)?(?:PD|OQ|AQ)\d/.test(l))
    .map(l => l.replace(/^\||\|$/g,'').split('|').map(c => c.trim()));
}
const strip = s => s.replace(/\*\*/g,'').trim();

function buildGate(sources) {
  const items = [];
  const req = sources['requirements.md'];
  for (const r of tableRows(req, '### Still open')) {
    items.push({ id: strip(r[0]), src: 'requirements.md', q: r[1],
                 assumption: r[2], decider: r[3],
                 status: 'OPEN', rank: 1 });
  }
  for (const r of tableRows(sources['data-design.md'], '| id | Question | Resolution | Consequence if wrong |', '## 2. Entity model')) {
    const amends = /Amended|Backtracked|restates?\b/i.test(r[2]);
    items.push({ id: strip(r[0]), src: 'data-design.md', q: r[1], assumption: r[2],
                 decider: amends ? 'You — amends an approved doc' : 'Tech (assumed)',
                 status: amends ? 'AMENDS' : 'ASSUMED', rank: amends ? 0 : 2 });
  }
  for (const r of tableRows(sources['architecture-design.md'], '| id | Question | Resolution and reasoning |', '## 2. Components')) {
    const ruling = /User decision at this gate/i.test(r[2]);
    items.push({ id: strip(r[0]), src: 'architecture-design.md', q: r[1], assumption: r[2],
                 decider: ruling ? 'You — at this gate' : 'Tech (assumed)',
                 status: ruling ? 'RULING' : 'ASSUMED', rank: ruling ? 0 : 2 });
  }
  items.sort((a,b) => a.rank - b.rank || a.src.localeCompare(b.src));
  return items;
}

const BADGE = { RULING:'needs-ruling', AMENDS:'amends', OPEN:'open', ASSUMED:'assumed' };
const LABEL = { RULING:'AWAITING YOUR RULING', AMENDS:'AMENDS AN APPROVED DOC',
                OPEN:'OPEN', ASSUMED:'RESOLVED BY ASSUMPTION' };

/* ---------- render ---------- */
const sources = {};
const missing = [];
for (const d of [...DOCS, ...APPENDICES]) {
  if (existsSync(d.file)) sources[d.file] = readFileSync(d.file,'utf8');
  else missing.push(d.file);
}

marked.use({ gfm: true, breaks: false });

function docToHtml(md, slug) {
  const blocks = [];
  md = md.replace(/```mermaid\n([\s\S]*?)```/g, (_, src) => {
    blocks.push(renderMermaid(src)); return `\n@@DIAGRAM${blocks.length-1}@@\n`;
  });
  let html = marked.parse(md);
  html = html.replace(/<p>@@DIAGRAM(\d+)@@<\/p>/g, (_, i) => blocks[+i]);

  // headings get stable anchors; UC headings anchor on their id
  const headings = [];
  html = html.replace(/<h([23])>([\s\S]*?)<\/h\1>/g, (m, lvl, inner) => {
    const text = inner.replace(/<[^>]+>/g,'').trim();
    const ucm = text.match(/^(UC\d+)/);
    const id = ucm ? ucm[1] : `${slug}-${text.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'').slice(0,60)}`;
    if (lvl === '2') headings.push({ id, text });
    return `<h${lvl} id="${id}">${inner}</h${lvl}>`;
  });

  // a table cell whose entire content is a stable id becomes that id's anchor
  html = html.replace(/<td>(?:<strong>)?((?:UC|R|NF|PD|LD|OQ|AQ)\d{1,3})(?:<\/strong>)?<\/td>/g,
                      (m, id) => m.replace('<td>', `<td id="${id}">`));

  // cross-references become links, but never inside code or existing links
  html = html.split(/(<pre[\s\S]*?<\/pre>|<code[\s\S]*?<\/code>|<svg[\s\S]*?<\/svg>|<a[\s\S]*?<\/a>|<[^>]+>)/)
             .map((seg, i) => i % 2 ? seg : seg.replace(ID_RE, id => `<a class="ref" href="#${id}">${id}</a>`))
             .join('');
  return { html, headings };
}

let toc = '', body = '';
const gate = buildGate(sources);

for (const d of DOCS) {
  const slug = d.file.replace('.md','');
  if (!sources[d.file]) {
    body += `<section id="${slug}"><h1>${d.title}</h1><p class="miss">SOURCE MISSING: ${d.file}</p></section>`;
    toc += `<li><a href="#${slug}">${d.title} <span class="tag miss">missing</span></a></li>`;
    continue;
  }
  const md = sources[d.file];
  const statusLine = (md.match(/^Status:\s*(.+)$/m) || [,'(no status line)'])[1].replace(/\*\*/g,'');
  const cls = /approved/i.test(statusLine) ? 'approved' : 'draft';
  const badge = /approved/i.test(statusLine) ? 'APPROVED' : 'DRAFT';
  const { html, headings } = docToHtml(md, slug);
  body += `<section id="${slug}"><div class="dochead"><h1>${d.phase} — ${d.title}</h1>
    <span class="badge ${cls}">${badge}</span></div>
    <p class="statusline"><code>${esc(d.file)}</code> — ${esc(statusLine)}</p>${html}</section>`;
  toc += `<li><a href="#${slug}"><b>${d.phase} · ${d.title}</b> <span class="tag ${cls}">${badge}</span></a><ul>` +
         headings.map(h => `<li><a href="#${h.id}">${esc(h.text)}</a></li>`).join('') + `</ul></li>`;
}

let apx = '';
for (const a of APPENDICES) {
  if (!sources[a.file]) { apx += `<p class="miss">SOURCE MISSING: ${a.file}</p>`; continue; }
  const lines = sources[a.file].split('\n').length;
  apx += `<details><summary><code>${esc(a.file)}</code> <span class="tag">${lines} lines</span></summary>
          <pre class="code">${highlight(sources[a.file], a.lang)}</pre></details>`;
}

const gateRows = gate.map(g => `<tr class="row-${BADGE[g.status]}">
   <td><a class="ref" href="#${g.id}">${g.id}</a></td>
   <td><span class="badge ${BADGE[g.status]}">${LABEL[g.status]}</span></td>
   <td>${g.q}</td><td>${g.assumption}</td>
   <td>${esc(g.decider)}</td><td><code>${esc(g.src)}</code></td></tr>`).join('\n');

const counts = ['RULING','AMENDS','OPEN','ASSUMED'].map(s =>
  `<span class="chip ${BADGE[s]}">${gate.filter(g=>g.status===s).length} ${LABEL[s].toLowerCase()}</span>`).join(' ');

const now = new Date().toISOString().replace('T',' ').slice(0,16) + ' UTC';
const page = `<!--
  GENERATED FILE — built from the markdown sources in this directory on ${now}.
  Do not edit this file.  Edit the markdown and re-run:  node build-review.mjs
  Agents must consume the markdown, not this page.
-->
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Booking Service — Design Review</title>
<style>
:root{--bg:#fbfbfa;--fg:#1c1c1a;--mut:#5f5f58;--line:#e2e0da;--card:#fff;--accent:#8a5a2b;
      --ok:#1f6f4a;--okbg:#e6f3ec;--warn:#8a4b00;--warnbg:#fdf0dd;--dngr:#8f2d2d;--dngrbg:#fbe9e9;
      --info:#2b4d8a;--infobg:#e8eefb;--code:#f4f2ee;}
@media (prefers-color-scheme:dark){:root{--bg:#16161a;--fg:#e8e6e1;--mut:#a6a29a;--line:#2f2f36;
      --card:#1d1d22;--accent:#d0a068;--ok:#7fd0a6;--okbg:#12291f;--warn:#e8b466;--warnbg:#2e2312;
      --dngr:#ea9494;--dngrbg:#2d1717;--info:#9db9ee;--infobg:#151d2e;--code:#202027;}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
 font:16px/1.65 ui-sans-serif,-apple-system,"Segoe UI",Inter,system-ui,sans-serif;}
.wrap{display:grid;grid-template-columns:290px minmax(0,1fr);gap:0;max-width:1500px;margin:0 auto}
nav{position:sticky;top:0;align-self:start;max-height:100vh;overflow-y:auto;padding:24px 18px;
 border-right:1px solid var(--line);font-size:13.5px}
nav h2{font-size:12px;letter-spacing:.09em;text-transform:uppercase;color:var(--mut);margin:18px 0 8px}
nav ul{list-style:none;margin:0;padding:0} nav ul ul{margin:4px 0 10px 12px;border-left:1px solid var(--line);padding-left:10px}
nav li{margin:3px 0} nav a{color:var(--fg);text-decoration:none;display:block;padding:2px 4px;border-radius:5px}
nav a:hover{background:var(--code);color:var(--accent)}
main{padding:30px 40px 90px;min-width:0}
header.top{border-bottom:2px solid var(--line);padding-bottom:18px;margin-bottom:26px}
header.top h1{margin:0 0 4px;font-size:30px;letter-spacing:-.02em}
.sub{color:var(--mut);font-size:14px}
section{margin:0 0 56px;scroll-margin-top:16px}
.dochead{display:flex;align-items:center;gap:12px;flex-wrap:wrap;border-top:1px solid var(--line);padding-top:26px}
h1{font-size:26px;letter-spacing:-.015em} h2{font-size:20px;margin-top:34px;scroll-margin-top:16px}
h3{font-size:16.5px;margin-top:26px;scroll-margin-top:16px} h4{font-size:15px;color:var(--mut)}
.statusline{color:var(--mut);font-size:13.5px;margin-top:2px}
table{border-collapse:collapse;width:100%;margin:14px 0;font-size:14px;display:block;overflow-x:auto}
th,td{border:1px solid var(--line);padding:7px 10px;text-align:left;vertical-align:top}
th{background:var(--code);font-weight:600;white-space:nowrap}
tbody tr:nth-child(even){background:color-mix(in srgb,var(--code) 45%,transparent)}
code{background:var(--code);padding:1px 5px;border-radius:4px;font-size:.9em;
 font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace}
pre.code{background:var(--code);padding:14px 16px;border-radius:8px;overflow-x:auto;font-size:12.5px;
 line-height:1.55;border:1px solid var(--line);font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
 white-space:pre;tab-size:4}
pre code{background:none;padding:0}
pre{background:var(--code);padding:14px 16px;border-radius:8px;overflow-x:auto;font-size:12.5px;
 border:1px solid var(--line)}
.k{color:var(--info);font-weight:600}.s{color:var(--ok)}.c{color:var(--mut);font-style:italic}
.badge{font-size:10.5px;font-weight:700;letter-spacing:.07em;padding:3px 8px;border-radius:20px;white-space:nowrap}
.approved{background:var(--okbg);color:var(--ok)} .draft{background:var(--warnbg);color:var(--warn)}
.needs-ruling{background:var(--dngrbg);color:var(--dngr)} .amends{background:var(--warnbg);color:var(--warn)}
.open{background:var(--infobg);color:var(--info)} .assumed{background:var(--code);color:var(--mut)}
.miss{background:var(--dngrbg);color:var(--dngr);padding:8px 12px;border-radius:6px;font-weight:600}
.tag{font-size:10px;padding:1px 6px;border-radius:10px;background:var(--code);color:var(--mut);font-weight:600}
.chip{display:inline-block;font-size:12px;font-weight:600;padding:4px 11px;border-radius:20px;margin-right:6px}
.gate{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--accent);
 border-radius:10px;padding:20px 22px;margin-bottom:34px}
.gate h2{margin-top:0}
tr.row-needs-ruling td{background:var(--dngrbg)} tr.row-amends td{background:var(--warnbg)}
a.ref{color:var(--accent);text-decoration:none;border-bottom:1px dotted var(--accent);font-variant-numeric:tabular-nums}
a.ref:hover{background:var(--code)}
figure.diagram{margin:20px 0;padding:16px;background:var(--card);border:1px solid var(--line);
 border-radius:10px;overflow-x:auto;text-align:center}
figure.diagram svg{max-width:100%;height:auto}
@media (prefers-color-scheme:dark){figure.diagram{background:#f6f6f4}}
details{border:1px solid var(--line);border-radius:8px;padding:10px 14px;margin:10px 0;background:var(--card)}
summary{cursor:pointer;font-weight:600}
blockquote{border-left:3px solid var(--line);margin:12px 0;padding:2px 14px;color:var(--mut)}
footer{border-top:1px solid var(--line);margin-top:50px;padding-top:16px;color:var(--mut);font-size:12.5px}
@media (max-width:900px){.wrap{grid-template-columns:1fr}nav{position:static;max-height:none;border-right:0;
 border-bottom:1px solid var(--line)}main{padding:22px}}
</style></head><body>
<div class="wrap">
<nav>
  <h2>Review gate</h2>
  <ul><li><a href="#gate"><b>Decisions awaiting you</b></a></li></ul>
  <h2>Documents</h2>
  <ul>${toc}</ul>
  <h2>Artifacts</h2>
  <ul><li><a href="#appendix">Dictionary &amp; DDL</a></li></ul>
</nav>
<main>
<header class="top">
  <h1>Booking Service — Design Review</h1>
  <div class="sub">Phase 2 review gate · generated ${now} · sources: ${DOCS.length} documents, ${APPENDICES.length} artifacts${missing.length ? ` · <span class="miss">${missing.length} MISSING</span>` : ''}</div>
</header>

<section id="gate" class="gate">
  <h2>Decisions awaiting you</h2>
  <p>Every pending decision and open question across all rendered documents.
     Rows are ordered by what needs you most.</p>
  <p>${counts}</p>
  <table><thead><tr><th>id</th><th>status</th><th>question</th>
    <th>interim assumption / resolution</th><th>decider</th><th>source</th></tr></thead>
    <tbody>${gateRows}</tbody></table>
</section>

${body}

<section id="appendix"><h1>Artifacts</h1>
<p class="statusline">Generated and DDL artifacts, rendered verbatim. Expand to read.</p>
${apx}</section>

<footer>
  Generated from the markdown sources on ${now} — <b>do not edit this file</b>;
  regenerate instead with <code>node build-review.mjs</code>.
  Agents must consume the markdown, not this page.
  Diagrams are pre-rendered inline SVG, so this page is fully self-contained and works offline.
</footer>
</main></div></body></html>`;

writeFileSync('design-review.html', page);
rmSync(tmp, { recursive: true, force: true });
console.log(`design-review.html written: ${diagramCount} diagrams inlined, ${gate.length} gate items, ${missing.length} missing sources`);
