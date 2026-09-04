#!/usr/bin/env python3
"""Mirror lint: manifests resolve, every skill has a well-formed SKILL.md,
descriptions start with "Use when" and stay under the cap. Stdlib only."""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DESC_CAP = 500
errors = []
err = errors.append

mp = ROOT / ".claude-plugin" / "marketplace.json"
if not mp.exists():
    err("missing .claude-plugin/marketplace.json")
    plugins = []
else:
    plugins = json.loads(mp.read_text()).get("plugins", [])

for entry in plugins:
    pdir = (ROOT / entry["source"]).resolve()
    manifest = pdir / ".claude-plugin" / "plugin.json"
    if not manifest.exists():
        err(f"{entry['name']}: missing {manifest.relative_to(ROOT)}")
        continue
    spec = json.loads(manifest.read_text())
    if spec.get("name") != entry["name"]:
        err(f"{entry['name']}: plugin.json name {spec.get('name')!r} differs from marketplace entry")
    for rel in spec.get("skills", []):
        sdir = (pdir / rel).resolve()
        skill_md = sdir / "SKILL.md"
        if not skill_md.exists():
            err(f"{entry['name']}: skill {rel} has no SKILL.md")
            continue
        text = skill_md.read_text()
        m = re.match(r"---\n(.*?)\n---\n", text, re.S)
        if not m:
            err(f"{rel}: no frontmatter"); continue
        fields, key = {}, None
        for line in m.group(1).splitlines():
            km = re.match(r"^(\w[\w-]*):\s*(.*)$", line)
            if km:
                key, val = km.group(1), km.group(2).strip()
                fields[key] = "" if val in (">-", ">", "|", "|-") else val
            elif key and line.startswith("  "):
                fields[key] = (fields[key] + " " + line.strip()).strip()
        if set(fields) - {"name", "description"}:
            err(f"{rel}: frontmatter has extra keys {sorted(set(fields) - {'name', 'description'})}")
        if fields.get("name") != sdir.name:
            err(f"{rel}: name {fields.get('name')!r} != directory {sdir.name!r}")
        desc = fields.get("description", "")
        if not desc.startswith("Use when"):
            err(f"{rel}: description must start with 'Use when'")
        if len(desc) > DESC_CAP:
            err(f"{rel}: description is {len(desc)} chars (cap {DESC_CAP})")
        for ptr in re.findall(r"`example/([^`]+)`", text):
            if not (sdir / "example" / ptr).exists():
                err(f"{rel}: pointer example/{ptr} does not resolve")
        if (sdir / "eval.md").exists():
            err(f"{rel}: eval.md must not be published")

if errors:
    print("\n".join(f"LINT: {e}" for e in errors)); print(f"\n{len(errors)} problem(s).")
    sys.exit(1)
print(f"lint clean ({sum(len(json.loads((ROOT / e['source'] / '.claude-plugin' / 'plugin.json').read_text())['skills']) for e in plugins)} skills, {len(plugins)} plugins).")
