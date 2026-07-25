#!/usr/bin/env python3
"""Stamp running footers (title left, page x of N right) on every page except the cover."""
import io, os
from pypdf import PdfReader, PdfWriter
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4

D = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(D, "bible_final_raw.pdf")
OUT = os.path.join(D, "SmartTriage-System-Bible.pdf")

reader = PdfReader(SRC)
total = len(reader.pages)
w, h = A4

overlay_buf = io.BytesIO()
c = canvas.Canvas(overlay_buf, pagesize=A4)
for i in range(total):
    if i == 0:                      # cover stays clean
        c.showPage()
        continue
    c.setFont("Helvetica", 7)
    c.setFillColorRGB(0.36, 0.42, 0.46)
    c.drawString(43, 26, "SmartTriage — System Bible · Edition 1.1")
    c.drawRightString(w - 43, 26, f"Page {i + 1} of {total}")
    c.setStrokeColorRGB(0.84, 0.89, 0.91)
    c.setLineWidth(0.6)
    c.line(43, 36, w - 43, 36)
    c.showPage()
c.save()
overlay_buf.seek(0)
overlay = PdfReader(overlay_buf)

writer = PdfWriter()
for i, page in enumerate(reader.pages):
    if i > 0:
        page.merge_page(overlay.pages[i])
    writer.add_page(page)

writer.add_metadata({
    "/Title": "SmartTriage — System Bible (Master Functional Specification)",
    "/Author": "SmartTriage Engineering",
    "/Subject": "Definitive behavioral reference for the SmartTriage ED platform",
    "/Creator": "SmartTriage docs pipeline",
})
with open(OUT, "wb") as f:
    writer.write(f)
print(f"stamped {total} pages -> {OUT}")
