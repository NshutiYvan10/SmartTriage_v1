/**
 * PdfPreviewModal — in-app PDF preview before printing/saving.
 *
 * Every report/document generation used to trigger an immediate file download;
 * now the PDF opens INSIDE the app first (browser-native viewer in an iframe),
 * with explicit Print / Download actions. Pair with {@link usePdfPreview}:
 *
 *   const { showPdf, previewProps } = usePdfPreview();
 *   ...
 *   const { blob, filename } = await someApi.downloadPdf(id);
 *   showPdf(blob, filename);          // ← instead of saveBlob(...)
 *   ...
 *   <PdfPreviewModal {...previewProps} />
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Download, FileText, Loader2, Printer, X } from 'lucide-react';
import { saveBlob } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';

interface Props {
  open: boolean;
  blob: Blob | null;
  filename: string;
  onClose: () => void;
  /** Open the browser's print dialog as soon as the document renders (a "Print" action). */
  printOnLoad?: boolean;
}

export function PdfPreviewModal({ open, blob, filename, onClose, printOnLoad }: Props) {
  const { isDark, text } = useTheme();
  const frameRef = useRef<HTMLIFrameElement>(null);
  const [frameReady, setFrameReady] = useState(false);

  // One object URL per blob; revoked on change/unmount so previews never leak.
  const url = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  useEffect(() => { setFrameReady(false); }, [url]);

  // Escape closes — standard dialog behavior.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open || !blob || !url) return null;

  const handlePrint = () => {
    // Same-origin blob iframe → the browser prints the PDF, not the app shell.
    const w = frameRef.current?.contentWindow;
    if (w) { w.focus(); w.print(); }
  };

  const onFrameLoad = () => {
    setFrameReady(true);
    // Print-intent open: give the embedded PDF viewer a beat to paint, then print.
    if (printOnLoad) setTimeout(handlePrint, 400);
  };

  return (
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

        {/* The document */}
        <div className="relative flex-1 bg-slate-500/10">
          {!frameReady && (
            <div className="absolute inset-0 flex items-center justify-center">
              <Loader2 className="w-6 h-6 animate-spin text-cyan-600" />
            </div>
          )}
          <iframe
            ref={frameRef}
            title={filename}
            src={url}
            onLoad={onFrameLoad}
            className="w-full h-full border-0"
          />
        </div>
      </div>
    </div>
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
