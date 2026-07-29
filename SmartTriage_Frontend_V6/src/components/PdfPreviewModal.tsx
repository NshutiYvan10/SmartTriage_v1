/**
 * PdfPreviewModal — in-app PDF preview before saving.
 *
 * Report/document generation opens the PDF INSIDE the app first, with a single
 * explicit Download action. Pair with {@link usePdfPreview}:
 *
 *   const { showPdf, previewProps } = usePdfPreview();
 *   ...
 *   const { blob, filename } = await someApi.downloadPdf(id);
 *   showPdf(blob, filename);          // ← instead of saveBlob(...)
 *   ...
 *   <PdfPreviewModal {...previewProps} />
 *
 * Rendering: the page canvases are painted with PDF.js rather than an
 * `<iframe src="blob:…pdf">`. Native-plugin PDF embedding renders blank inside
 * many webviews / embedded Chromium builds (no PDFium), so we rasterise each
 * page ourselves — this works everywhere. Download saves the real PDF.
 */
import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Download, FileText, Loader2, X } from 'lucide-react';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { saveBlob } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { ModalPortal } from '@/components/ModalPortal';

interface Props {
  open: boolean;
  blob: Blob | null;
  filename: string;
  onClose: () => void;
}

/** Lazy-load PDF.js once (keeps it out of the main bundle) and wire its worker. */
let pdfjsPromise: Promise<typeof import('pdfjs-dist')> | null = null;
function loadPdfjs() {
  if (!pdfjsPromise) {
    pdfjsPromise = import('pdfjs-dist').then((pdfjs) => {
      pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;
      return pdfjs;
    });
  }
  return pdfjsPromise;
}

export function PdfPreviewModal({ open, blob, filename, onClose }: Props) {
  const { isDark, text } = useTheme();
  const scrollRef = useRef<HTMLDivElement>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');

  // Escape closes — standard dialog behaviour.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Rasterise every page to a canvas with PDF.js.
  useEffect(() => {
    if (!open || !blob) return;
    let cancelled = false;
    let doc: import('pdfjs-dist').PDFDocumentProxy | null = null;
    setStatus('loading');

    (async () => {
      try {
        const pdfjs = await loadPdfjs();
        const data = await blob.arrayBuffer();
        if (cancelled) return;
        doc = await pdfjs.getDocument({ data }).promise;
        if (cancelled) return;

        const host = hostRef.current;
        if (!host) return;
        host.replaceChildren();

        // Backing-store resolution: fit the scroll column width, capped DPR for crispness.
        const columnW = Math.max(320, (scrollRef.current?.clientWidth ?? 900) - 48);
        const dpr = Math.min(window.devicePixelRatio || 1, 2);

        for (let n = 1; n <= doc.numPages; n++) {
          if (cancelled) return;
          const page = await doc.getPage(n);
          const base = page.getViewport({ scale: 1 });
          const cssScale = columnW / base.width;          // fit to column
          const viewport = page.getViewport({ scale: cssScale * dpr });

          const canvas = document.createElement('canvas');
          const ctx = canvas.getContext('2d');
          if (!ctx) continue;
          canvas.width = Math.floor(viewport.width);
          canvas.height = Math.floor(viewport.height);
          canvas.style.width = '100%';
          canvas.style.height = 'auto';
          canvas.style.display = 'block';
          canvas.style.margin = n === 1 ? '0 auto' : '18px auto 0';
          canvas.style.borderRadius = '3px';
          canvas.style.boxShadow = '0 2px 14px rgba(0,0,0,0.28)';
          host.appendChild(canvas);

          await page.render({ canvasContext: ctx, viewport }).promise;
          if (cancelled) return;
        }

        if (!cancelled) setStatus('ready');
      } catch (err) {
        console.error('[PdfPreview] PDF.js render failed', err);
        if (!cancelled) setStatus('error');
      }
    })();

    return () => {
      cancelled = true;
      try { doc?.destroy(); } catch { /* ignore */ }
    };
  }, [open, blob]);

  if (!open || !blob) return null;

  return (
    <ModalPortal>
      <div
        className="fixed inset-0 z-[10000] flex items-center justify-center p-4 backdrop-blur-sm"
        style={{ background: 'var(--modal-backdrop, rgba(0,0,0,0.5))' }}
        role="dialog"
        aria-modal="true"
        aria-label={`Preview ${filename}`}
      >
        <div className="absolute inset-0" onClick={onClose} />
        <div
          className={`relative w-full max-w-5xl h-[88vh] rounded-2xl shadow-2xl animate-scale-in overflow-hidden flex flex-col ${
            isDark ? 'bg-slate-900 border border-white/10' : 'bg-white border border-slate-200'
          }`}
        >
          {/* Header: filename + Download */}
          <div className={`flex items-center justify-between gap-3 px-5 py-3.5 border-b ${
            isDark ? 'border-white/10' : 'border-slate-200'
          }`}>
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center bg-cyan-500/10 flex-shrink-0">
                <FileText className="w-4.5 h-4.5 text-cyan-600" />
              </div>
              <div className="min-w-0">
                <h3 className={`text-sm font-bold truncate ${text.heading}`}>{filename}</h3>
                <p className={`text-[11px] ${text.muted}`}>Review the document, then download</p>
              </div>
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <button
                onClick={() => saveBlob(blob, filename)}
                className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl bg-cyan-600 text-white hover:bg-cyan-700 transition-colors shadow-md"
              >
                <Download className="w-3.5 h-3.5" />
                Download
              </button>
              <button
                onClick={onClose}
                aria-label="Close preview"
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          </div>

          {/* The document — PDF.js-rendered page canvases on a neutral reading surface */}
          <div
            ref={scrollRef}
            className="relative flex-1 overflow-auto"
            style={{ background: isDark ? '#0b1120' : '#54585c' }}
          >
            {status === 'loading' && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
                <Loader2 className="w-6 h-6 animate-spin text-cyan-400" />
                <span className="text-xs font-medium text-white/70">Rendering document…</span>
              </div>
            )}
            {status === 'error' && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 px-6 text-center">
                <AlertTriangle className="w-7 h-7 text-amber-400" />
                <p className="text-sm font-semibold text-white/90">Preview couldn’t be rendered</p>
                <p className="text-xs text-white/60 max-w-sm">The document is still valid — you can download it directly.</p>
                <button
                  onClick={() => saveBlob(blob, filename)}
                  className="mt-1 inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl bg-cyan-600 text-white hover:bg-cyan-700"
                >
                  <Download className="w-3.5 h-3.5" />
                  Download PDF
                </button>
              </div>
            )}
            <div ref={hostRef} className="mx-auto max-w-4xl px-6 py-6" />
          </div>
        </div>
      </div>
    </ModalPortal>
  );
}

/** Local state + props wiring for PdfPreviewModal (one preview at a time per view). */
export function usePdfPreview() {
  const [preview, setPreview] = useState<{ blob: Blob; filename: string } | null>(null);
  return {
    /** Open the in-app preview for a fetched PDF (use instead of saveBlob). */
    showPdf: (blob: Blob, filename: string) => setPreview({ blob, filename }),
    previewProps: {
      open: preview !== null,
      blob: preview?.blob ?? null,
      filename: preview?.filename ?? '',
      onClose: () => setPreview(null),
    },
  };
}
