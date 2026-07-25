#!/usr/bin/env python3
"""Generate Appendix A (endpoint inventory) and B (enum inventory) from the codebase."""
import os, re, html

SRC = "/Users/yvan/development/SmartTriage_v1/SmartTriage-server/src/main/java/com/smartTriage/smartTriage_server"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "16_appendix_ab.html")

MAPPING = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\)|\(\s*\))?')
CLASSMAP = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')

def controllers():
    rows = []
    for root, _, files in os.walk(SRC):
        for f in sorted(files):
            if not f.endswith("Controller.java"):
                continue
            path = os.path.join(root, f)
            text = open(path, encoding="utf-8", errors="replace").read()
            base_m = CLASSMAP.search(text)
            base = base_m.group(1) if base_m else ""
            module = re.search(r'/module/([a-z]+)/', path.replace("\\", "/"))
            module = module.group(1) if module else "-"
            eps = []
            for m in MAPPING.finditer(text):
                verb = m.group(1).upper()
                sub = m.group(2) or ""
                full = (base + sub) if sub.startswith("/") or not sub else (base + "/" + sub)
                full = full or base
                eps.append((verb, full if full else base))
            if eps:
                rows.append((module, f.replace(".java", ""), eps))
    rows.sort()
    return rows

ENUM = re.compile(r'public\s+enum\s+(\w+)')
CONST = re.compile(r'^\s{4}([A-Z][A-Z0-9_]*)\s*[,;(]', re.M)

def enums():
    out = []
    for root, _, files in os.walk(SRC):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            text = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
            m = ENUM.search(text)
            if not m:
                continue
            body = text[m.end():]
            # cut at first method/field marker to avoid capturing constants beyond values
            consts = []
            for cm in CONST.finditer(body):
                consts.append(cm.group(1))
                after = body[cm.end()-1]
                if after == ";":
                    break
            if consts:
                out.append((m.group(1), consts))
    # dedupe by name, keep the longest value list
    best = {}
    for name, vals in out:
        if name not in best or len(vals) > len(best[name]):
            best[name] = vals
    return sorted(best.items())

def build():
    parts = []
    parts.append('<!-- APPENDIX A -->\n<div class="chapter">\n<span class="pgmark">PMK-APXA</span>')
    parts.append('<div class="ch-open"><div class="ch-num">A</div><h1>Appendix A — API Endpoint Inventory</h1>'
                 '<p class="ch-lede">Every REST endpoint, generated directly from the controllers. '
                 'Verbs and paths are exact; authorization rules are described in the relevant chapters.</p></div>')
    rows = controllers()
    total = sum(len(e) for _, _, e in rows)
    parts.append(f'<p class="small muted">{len(rows)} controllers · {total} endpoints.</p>')
    for module, ctrl, eps in rows:
        parts.append(f'<h4 style="margin-top:8pt;">{html.escape(ctrl)} <span class="muted small">({html.escape(module)})</span></h4>')
        parts.append('<table class="compact">')
        for verb, path in eps:
            parts.append(f'<tr><td style="width:16mm"><b>{verb}</b></td><td><code>{html.escape(path)}</code></td></tr>')
        parts.append('</table>')
    parts.append('</div>')

    parts.append('<!-- APPENDIX B -->\n<div class="chapter">\n<span class="pgmark">PMK-APXB</span>')
    parts.append('<div class="ch-open"><div class="ch-num">B</div><h1>Appendix B — Enum &amp; Lifecycle Inventory</h1>'
                 '<p class="ch-lede">Every enumerated vocabulary in the backend, generated from source. '
                 'These are the exact allowed values for statuses, types and categories.</p></div>')
    es = enums()
    parts.append(f'<p class="small muted">{len(es)} enums.</p>')
    parts.append('<table class="compact">')
    parts.append('<tr><th style="width:44mm">Enum</th><th>Values</th></tr>')
    for name, vals in es:
        parts.append(f'<tr><td><code>{html.escape(name)}</code></td><td class="small">{html.escape(" · ".join(vals))}</td></tr>')
    parts.append('</table></div>')
    open(OUT, "w", encoding="utf-8").write("\n".join(parts))
    print(f"wrote {OUT}: {len(rows)} controllers, {total} endpoints, {len(es)} enums")

build()
