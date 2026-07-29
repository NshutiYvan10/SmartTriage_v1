/**
 * CsvPreviewModal — in-app table preview for CSV exports before saving.
 *
 * Mirrors {@link PdfPreviewModal}: the report opens INSIDE the app first as a
 * clean table, with a single explicit Download action. Pair with
 * {@link useCsvPreview}:
 *
 *   const { showCsv, previewProps } = useCsvPreview();
 *   ...
 *   const { blob, filename } = await someApi.downloadCsv(...);
 *   showCsv(blob, filename);          // ← instead of saveBlob(...)
 *   ...
 *   <CsvPreviewModal {...previewProps} />
 *
 * The CSV is parsed client-side (RFC-4180-ish: quoted fields, "" escapes,
 * newlines inside quotes) purely for display; Download saves the original blob
 * untouched, so the file is always byte-for-byte what the server produced.
 */
import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Download, Loader2, Table2, X } from 'lucide-react';
import { saveBlob } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { ModalPortal } from '@/components/ModalPortal';

interface Props {
  open: boolean;
  blob: Blob | null;
  filename: string;
  onClose: () => void;
}

/** Rows rendered in the preview; beyond this we note the truncation (full file still downloads). */
const MAX_PREVIEW_ROWS = 500;

/** Parse CSV text into a header row + data rows (handles quotes, "" escapes, embedded newlines). */
function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;
  // Strip a leading UTF-8 BOM if present.
  const s = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;

  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inQuotes) {
      if (c === '"') {
        if (s[i + 1] === '"') { field += '"'; i++; }
        else inQuotes = false;
      } else {
        field += c;
      }
    } else if (c === '"') {
      inQuotes = true;
    } else if (c === ',') {
      row.push(field); field = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && s[i + 1] === '\n') i++;
      row.push(field); field = '';
      rows.push(row); row = [];
    } else {
      field += c;
    }
  }
  // Flush the trailing field/row unless the file ended on a clean newline.
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row); }
  return rows;
}

/** True when a column's non-empty cells all look numeric (for right-alignment + tabular figures). */
function isNumericColumn(rows: string[][], col: number): boolean {
  let seen = 0;
  for (let r = 0; r < rows.length; r++) {
    const v = (rows[r][col] ?? '').trim();
    if (!v) continue;
    seen++;
    if (!/^-?\d{1,3}(,\d{3})*(\.\d+)?%?$|^-?\d+(\.\d+)?%?$/.test(v)) return false;
  }
  return seen > 0;
}

export function CsvPreviewModal({ open, blob, filename, onClose }: Props) {
  const { isDark, text } = useTheme();
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [table, setTable] = useState<{ headers: string[]; rows: string[][]; total: number } | null>(null);

  // Escape closes — standard dialog behaviour.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Parse the CSV into a table for display.
  useEffect(() => {
    if (!open || !blob) return;
    let cancelled = false;
    setStatus('loading');
    setTable(null);
    (async () => {
      try {
        const parsed = parseCsv(await blob.text());
        if (cancelled) return;
        if (!parsed.length) { setStatus('ready'); setTable({ headers: [], rows: [], total: 0 }); return; }
        const [headers, ...body] = parsed;
        setTable({ headers, rows: body.slice(0, MAX_PREVIEW_ROWS), total: body.length });
        setStatus('ready');
      } catch (err) {
        console.error('[CsvPreview] parse failed', err);
        if (!cancelled) setStatus('error');
      }
    })();
    return () => { cancelled = true; };
  }, [open, blob]);

  // Which columns are numeric (memoised off the rendered rows).
  const numericCols = useMemo(() => {
    if (!table) return new Set<number>();
    const set = new Set<number>();
    for (let c = 0; c < table.headers.length; c++) if (isNumericColumn(table.rows, c)) set.add(c);
    return set;
  }, [table]);

  if (!open || !blob) return null;

  const truncated = table ? table.total > table.rows.length : false;

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
          className={`relative w-full max-w-6xl h-[88vh] rounded-2xl shadow-2xl animate-scale-in overflow-hidden flex flex-col ${
            isDark ? 'bg-slate-900 border border-white/10' : 'bg-white border border-slate-200'
          }`}
        >
          {/* Header: filename + row count + Download */}
          <div className={`flex items-center justify-between gap-3 px-5 py-3.5 border-b ${
            isDark ? 'border-white/10' : 'border-slate-200'
          }`}>
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center bg-emerald-500/10 flex-shrink-0">
                <Table2 className="w-4.5 h-4.5 text-emerald-600" />
              </div>
              <div className="min-w-0">
                <h3 className={`text-sm font-bold truncate ${text.heading}`}>{filename}</h3>
                <p className={`text-[11px] ${text.muted}`}>
                  {table
                    ? `${table.total.toLocaleString()} row${table.total === 1 ? '' : 's'} · ${table.headers.length} columns${truncated ? ` · previewing first ${table.rows.length}` : ''}`
                    : 'Review the data, then download'}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <button
                onClick={() => saveBlob(blob, filename)}
                className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl bg-emerald-600 text-white hover:bg-emerald-700 transition-colors shadow-md"
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

          {/* The table */}
          <div className={`relative flex-1 overflow-auto ${isDark ? 'bg-slate-950/40' : 'bg-slate-50'}`}>
            {status === 'loading' && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
                <Loader2 className="w-6 h-6 animate-spin text-emerald-500" />
                <span className={`text-xs font-medium ${text.muted}`}>Preparing table…</span>
              </div>
            )}
            {status === 'error' && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 px-6 text-center">
                <AlertTriangle className="w-7 h-7 text-amber-500" />
                <p className={`text-sm font-semibold ${text.heading}`}>Couldn’t render the table</p>
                <p className={`text-xs ${text.muted} max-w-sm`}>The export is still valid — you can download the .csv directly.</p>
                <button
                  onClick={() => saveBlob(blob, filename)}
                  className="mt-1 inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl bg-emerald-600 text-white hover:bg-emerald-700"
                >
                  <Download className="w-3.5 h-3.5" />
                  Download CSV
                </button>
              </div>
            )}
            {status === 'ready' && table && table.headers.length > 0 && (
              <table className="w-full border-collapse text-xs" style={{ fontVariantNumeric: 'tabular-nums' }}>
                <thead className="sticky top-0 z-10">
                  <tr>
                    {table.headers.map((h, i) => (
                      <th
                        key={i}
                        className={`text-left font-bold uppercase tracking-wide px-3 py-2.5 border-b-2 whitespace-nowrap ${
                          isDark ? 'bg-slate-800 text-slate-200 border-slate-600' : 'bg-slate-100 text-slate-700 border-slate-300'
                        } ${numericCols.has(i) ? 'text-right' : ''}`}
                      >
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {table.rows.map((r, ri) => (
                    <tr key={ri} className={ri % 2 === 1 ? (isDark ? 'bg-white/[0.03]' : 'bg-white') : ''}>
                      {table.headers.map((_, ci) => (
                        <td
                          key={ci}
                          className={`px-3 py-2 border-b ${
                            isDark ? 'text-slate-300 border-white/5' : 'text-slate-700 border-slate-200'
                          }`}
                        >
                          {/* Single-line + ellipsis keeps rows compact (spreadsheet-style); full value on hover. */}
                          <div
                            className={`truncate ${numericCols.has(ci) ? 'text-right' : ''}`}
                            style={{ maxWidth: 340 }}
                            title={r[ci] ?? ''}
                          >
                            {r[ci] ?? ''}
                          </div>
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {status === 'ready' && table && table.headers.length === 0 && (
              <div className="absolute inset-0 flex items-center justify-center">
                <p className={`text-sm ${text.muted}`}>This export has no rows for the selected range.</p>
              </div>
            )}
            {truncated && (
              <div className={`sticky bottom-0 px-4 py-2 text-[11px] font-medium text-center border-t ${
                isDark ? 'bg-slate-900/95 text-slate-400 border-white/10' : 'bg-white/95 text-slate-500 border-slate-200'
              }`}>
                Previewing the first {table!.rows.length.toLocaleString()} of {table!.total.toLocaleString()} rows — Download for the complete file.
              </div>
            )}
          </div>
        </div>
      </div>
    </ModalPortal>
  );
}

/** Local state + props wiring for CsvPreviewModal (one preview at a time per view). */
export function useCsvPreview() {
  const [preview, setPreview] = useState<{ blob: Blob; filename: string } | null>(null);
  return {
    /** Open the in-app table preview for a fetched CSV (use instead of saveBlob). */
    showCsv: (blob: Blob, filename: string) => setPreview({ blob, filename }),
    previewProps: {
      open: preview !== null,
      blob: preview?.blob ?? null,
      filename: preview?.filename ?? '',
      onClose: () => setPreview(null),
    },
  };
}
