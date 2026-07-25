# SmartTriage System Bible — build pipeline

The deliverable is `docs/SmartTriage-System-Bible.pdf` (Edition 1.0, 77 pages).

## Rebuilding after changes
1. Edit the chapter part-files in `src/` (plain HTML using the design system in `00_head.html`).
2. Regenerate Appendices A/B from code: `python3 src/gen_appendix.py`
3. Render pass 1 (concatenate all parts with the placeholder TOC `02_toc.html`, print with headless Chrome).
4. `python3 src/toc_pass.py` — reads chapter page numbers from the pass-1 PDF, writes `02_toc_final.html`.
5. Render the final HTML (same concatenation but with `02_toc_final.html`).
6. `python3 src/stamp_footer.py` — stamps "Page N of T" footers and PDF metadata.

Chrome invocation:
`"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless --disable-gpu --no-pdf-header-footer --print-to-pdf=out.pdf in.html`

Keep Appendix C (open questions) current: resolve items as the team decides them.
