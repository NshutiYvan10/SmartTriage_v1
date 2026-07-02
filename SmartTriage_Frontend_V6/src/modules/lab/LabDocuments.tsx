/* ═══════════════════════════════════════════════════════════════
   LabDocuments — attach / list / download / remove full lab report
   files (PDF / scan) on a lab order.

   The interim standard until the structured results pipeline exists:
   the lab tech enters the available structured data AND attaches the
   full report document here. Reused in the result-entry modal (tech
   uploads) and the test-detail modal (doctor/tech view + download).

   `canManage` gates upload + delete (lab tech); everyone with access
   can list + download.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  Paperclip, Upload, Download, Trash2, Loader2, FileText, AlertTriangle, FileCheck2,
} from 'lucide-react';
import { labApi, type LabReportDocument } from '@/api/lab';
import { investigationApi } from '@/api/investigations';
import { useTheme } from '@/hooks/useTheme';

const ACCEPT = '.pdf,.png,.jpg,.jpeg,.tif,.tiff,application/pdf,image/png,image/jpeg,image/tiff';
const MAX_BYTES = 15 * 1024 * 1024;

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Report-document attachments for EITHER a lab order (labOrderId) or an
 * imaging/ECG investigation (investigationId) — exactly one is provided. Same
 * UI + upload/download/delete for both; only the API namespace differs.
 */
export function LabDocuments({
  labOrderId, investigationId, canManage = false,
}: { labOrderId?: string; investigationId?: string; canManage?: boolean }) {
  const { glassInner, isDark, text } = useTheme();
  // Bind to the right endpoint set based on which owner id was passed. Memoised
  // on the ids so the load callback below has a stable dependency.
  const doc = useMemo(() => (investigationId
    ? {
        list: () => investigationApi.listDocuments(investigationId),
        upload: (f: File, d?: string) => investigationApi.uploadDocument(investigationId, f, d),
        download: (id: string, name: string) => investigationApi.downloadDocument(investigationId, id, name),
        remove: (id: string) => investigationApi.deleteDocument(investigationId, id),
      }
    : {
        list: () => labApi.listDocuments(labOrderId!),
        upload: (f: File, d?: string) => labApi.uploadDocument(labOrderId!, f, d),
        download: (id: string, name: string) => labApi.downloadDocument(labOrderId!, id, name),
        remove: (id: string) => labApi.deleteDocument(labOrderId!, id),
      }), [labOrderId, investigationId]);
  const [docs, setDocs] = useState<LabReportDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [description, setDescription] = useState('');
  const [uploading, setUploading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    if (!labOrderId && !investigationId) return;
    setLoading(true);
    try {
      const data = await doc.list();
      setDocs(Array.isArray(data) ? data : []);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load documents');
    } finally {
      setLoading(false);
    }
  }, [doc, labOrderId, investigationId]);

  useEffect(() => { void load(); }, [load]);

  const doUpload = async () => {
    if (!file) return;
    if (file.size > MAX_BYTES) { setErr('File is too large (max 15 MB).'); return; }
    setUploading(true);
    setErr(null);
    try {
      await doc.upload(file, description);
      setFile(null); setDescription('');
      if (fileInput.current) fileInput.current.value = '';
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  const doDownload = async (d: LabReportDocument) => {
    setBusyId(d.id);
    try { await doc.download(d.id, d.fileName); }
    catch (e) { setErr(e instanceof Error ? e.message : 'Download failed'); }
    finally { setBusyId(null); }
  };

  const doDelete = async (d: LabReportDocument) => {
    setBusyId(d.id);
    setErr(null);
    try { await doc.remove(d.id); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : 'Remove failed'); }
    finally { setBusyId(null); }
  };

  return (
    <div className="rounded-xl p-3 space-y-2" style={glassInner}>
      <div className="flex items-center gap-2">
        <Paperclip className="w-3.5 h-3.5 text-cyan-500" />
        <span className={`text-[11px] font-bold uppercase tracking-wider ${text.label}`}>Report documents</span>
        {docs.length > 0 && <span className={`text-[10px] ${text.muted}`}>{docs.length} attached</span>}
      </div>

      {err && (
        <div className="rounded-md border border-red-500/30 bg-red-500/15 px-2 py-1 text-[11px] text-red-400 flex items-start gap-1.5">
          <AlertTriangle className="w-3 h-3 mt-0.5" /> <span>{err}</span>
        </div>
      )}

      {loading ? (
        <p className={`text-[11px] ${text.muted} flex items-center gap-1.5`}><Loader2 className="w-3 h-3 animate-spin" /> Loading…</p>
      ) : docs.length === 0 ? (
        <p className={`text-[11px] ${text.muted}`}>No report documents attached yet.</p>
      ) : (
        <ul className="space-y-1">
          {docs.map((d) => (
            <li key={d.id} className={`flex items-center gap-2 rounded-lg px-2 py-1.5 ${isDark ? 'bg-white/5' : 'bg-slate-50'}`}>
              <FileText className="w-3.5 h-3.5 text-cyan-500 flex-shrink-0" />
              <div className="flex-1 min-w-0">
                <p className={`text-[12px] font-semibold truncate ${text.heading}`}>{d.fileName}</p>
                <p className={`text-[10px] ${text.muted} truncate`}>
                  {humanSize(d.sizeBytes)}{d.uploadedByName ? ` · ${d.uploadedByName}` : ''}{d.description ? ` · ${d.description}` : ''}
                </p>
              </div>
              <button type="button" onClick={() => doDownload(d)} disabled={busyId === d.id}
                className={`p-1.5 rounded-lg ${isDark ? 'hover:bg-white/10 text-white' : 'hover:bg-slate-200 text-slate-700'}`} title="Download">
                {busyId === d.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
              </button>
              {canManage && (
                <button type="button" onClick={() => doDelete(d)} disabled={busyId === d.id}
                  className="p-1.5 rounded-lg hover:bg-rose-500/15 text-rose-500" title="Remove">
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <div className="pt-1 space-y-1.5">
          <input ref={fileInput} type="file" accept={ACCEPT}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className={`block w-full text-[11px] ${text.body} file:mr-2 file:py-1 file:px-2 file:rounded-lg file:border-0 file:text-[11px] file:font-bold file:bg-cyan-500/15 file:text-cyan-600`} />
          {file && (
            <input value={description} onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional label (e.g. Histopathology report, page 1-3)"
              className={`w-full px-2 py-1.5 rounded-lg text-[11px] focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
              style={glassInner} />
          )}
          <button type="button" onClick={doUpload} disabled={!file || uploading}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-cyan-600 hover:bg-cyan-700 text-white transition-colors disabled:opacity-50">
            {uploading ? <Loader2 className="w-3 h-3 animate-spin" /> : <Upload className="w-3 h-3" />}
            Attach report
          </button>
          <p className={`text-[10px] ${text.muted} flex items-center gap-1`}>
            <FileCheck2 className="w-3 h-3" /> PDF or image, up to 15 MB. Interim standard: enter structured values above + attach the full report.
          </p>
        </div>
      )}
    </div>
  );
}

export default LabDocuments;
