/**
 * PdfPreviewModal — in-app PDF preview before printing/saving.
 *
 * Every report/document generation used to trigger an immediate file download;
 * now the PDF opens INSIDE the app first, with explicit Print / Download actions.
 * Pair with {@link usePdfPreview}:
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
 * page ourselves — this works everywhere. A hidden iframe is kept purely for the
 * browser's native Print (vector-perfect) and Download always saves the real PDF.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Download, FileText, Loader2, Printer, X } from 'lucide-react';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { saveBlob } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { ModalPortal } from '@/components/ModalPortal';

interface Props {
  open: boolean;
  blob: Blob | null;
  filename: string;
  onClose: () => void;
  /** Open the browser's print dialog as soon as the document renders (a "Print" action). */
  printOnLoad?: boolean;
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

export function PdfPreviewModal({ open, blob, filename, onClose, printOnLoad }: Props) {
  const { isDark, text } = useTheme();
  const scrollRef = useRef<HTMLDivElement>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const printFrameRef = useRef<HTMLIFrameElement>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');

  // One object URL per blob (used only by the hidden print iframe); revoked on change/unmount.
  const url = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);

  const handlePrint = () => {
    const w = printFrameRef.current?.contentWindow;
    if (w) { w.focus(); w.print(); }
  };

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

        if (!cancelled) {
          setStatus('ready');
          if (printOnLoad) setTimeout(handlePrint, 350);
        }
      } catch (err) {
        console.error('[PdfPreview] PDF.js render failed', err);
        if (!cancelled) setStatus('error');
      }
    })();

    return () => {
      cancelled = true;
      try { doc?.destroy(); } catch { /* ignore */ }
    };
  }, [open, blob, printOnLoad]);

  if (!open || !blob || !url) return null;

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
          {/* Header: filename + actions */}
          <div className={`flex items-center justify-between gap-3 px-5 py-3.5 border-b ${
            isDark ? 'border-white/10' : 'border-slate-200'
          }`}>
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center bg-cyan-500/10 flex-shrink-0">
                <FileText className="w-4.5 h-4.5 text-cyan-600" />
              </div>
              <div className="min-w-0">
                <h3 className={`text-sm font-bold truncate ${text.heading}`}>{filename}</h3>
                <p className={`text-[11px] ${text.muted}`}>Review the report, then print or download</p>
              </div>
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <button
                onClick={handlePrint}
                className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-bold rounded-xl transition-colors ${
                  isDark ? 'text-slate-200 bg-white/10 hover:bg-white/15' : 'text-slate-700 bg-slate-100 hover:bg-slate-200'
                }`}
              >
                <Printer className="w-3.5 h-3.5" />
                Print
              </button>
              <button
                onClick={() => saveBlob(blob, filename)}
                className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-bold rounded-xl bg-cyan-600 text-white hover:bg-cyan-700 transition-colors shadow-md"
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
                <p className="text-xs text-white/60 max-w-sm">The document is still valid — you can download or print it directly.</p>
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

          {/* Hidden iframe kept only for the browser's native (vector) Print. */}
          <iframe
            ref={printFrameRef}
            title={`print ${filename}`}
            src={url}
            aria-hidden="true"
            tabIndex={-1}
            style={{ position: 'absolute', width: 0, height: 0, border: 0, opacity: 0, pointerEvents: 'none' }}
          />
        </div>
      </div>
    </ModalPortal>
  );
}

/** Local state + props wiring for PdfPreviewModal (one preview at a time per view). */
export function usePdfPreview() {
  const [preview, setPreview] = useState<{ blob: Blob; filename: string; print?: boolean } | null>(null);
  return {
    /** Open the in-app preview for a fetched PDF (use instead of saveBlob).
     *  Pass { print: true } to also pop the print dialog once it renders. */
    showPdf: (blob: Blob, filename: string, opts?: { print?: boolean }) =>
      setPreview({ blob, filename, print: opts?.print }),
    previewProps: {
      open: preview !== null,
      blob: preview?.blob ?? null,
      filename: preview?.filename ?? '',
      printOnLoad: preview?.print ?? false,
      onClose: () => setPreview(null),
    },
  };
}
