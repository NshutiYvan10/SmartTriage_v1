#!/usr/bin/env python3
"""Two-pass TOC: read marker pages from pass-1 PDF, inject into the TOC, rebuild HTML."""
import re, subprocess, os
from pypdf import PdfReader

D = os.path.dirname(os.path.abspath(__file__))
reader = PdfReader(os.path.join(D, "bible_pass1.pdf"))
pages = {}
for i, page in enumerate(reader.pages, start=1):
    text = page.extract_text() or ""
    # bare markers only — the TOC page's @PMK-…@ placeholders must not match
    for m in re.finditer(r"(?<!@)PMK-[A-Z0-9]+(?!@)", text):
        pages.setdefault(m.group(0), i)

print("markers found:", pages)

toc = open(os.path.join(D, "02_toc.html"), encoding="utf-8").read()
missing = []
def repl(m):
    key = m.group(1)
    if key in pages:
        return str(pages[key])
    missing.append(key)
    return "?"
toc2 = re.sub(r"@(PMK-[A-Z0-9]+)@", repl, toc)
open(os.path.join(D, "02_toc_final.html"), "w", encoding="utf-8").write(toc2)
if missing:
    print("MISSING MARKERS:", missing)
else:
    print("all TOC entries resolved")
