/* ═══════════════════════════════════════════════════════════════
   Consent & Consultations — per-visit informed consent + specialty consultation
   requests (internal consult / ICU admission / external referral record).
   Both record the acting clinician from the authenticated session (server-side).
   NOTE: "referral" survives in API paths and enum values (data compatibility);
   all USER-FACING wording says consultation. Inter-hospital transfer logistics
   (SAMU, transport, stabilisation checklists) are intentionally out of scope.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  ShieldCheck, Search, Plus, Loader2, RefreshCw, X, Stethoscope, FileSignature,
  CheckCircle, XCircle, Clock, Send,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { visitApi } from '@/api/visits';
import type { VisitResponse } from '@/api/types';
import { useCanSeeAllZones } from '@/hooks/useCanSeeAllZones';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { consentApi } from '@/api/consent';
import type { ConsentRecord, ConsentType, ConsentGrantor, ConsentStatus } from '@/api/consent';
import { referralApi } from '@/api/referral';
import type { ReferralRecord, ReferralType, ReferralUrgency } from '@/api/referral';
import { format } from 'date-fns';

const CONSENT_TYPES: ConsentType[] = [
  'PROCEDURE', 'SURGERY', 'ANAESTHESIA', 'BLOOD_TRANSFUSION', 'HIV_TEST',
  'SEDATION', 'IMAGING_CONTRAST', 'RESEARCH_PARTICIPATION', 'PHOTOGRAPHY', 'OTHER',
];
const GRANTORS: ConsentGrantor[] = [
  'PATIENT', 'PARENT_OR_GUARDIAN', 'NEXT_OF_KIN', 'LEGAL_SURROGATE', 'COURT_ORDER',
  'EMERGENCY_NO_CONSENT_REQUIRED',
];
const REFERRAL_TYPES: ReferralType[] = ['INTERNAL_CONSULT', 'ICU_ADMISSION_REQUEST', 'EXTERNAL_REFERRAL'];
// Display names — the enum values stay untouched for data compatibility.
const CONSULT_TYPE_LABEL: Record<ReferralType, string> = {
  INTERNAL_CONSULT: 'Internal consult',
  ICU_ADMISSION_REQUEST: 'ICU admission request',
  EXTERNAL_REFERRAL: 'External referral (record only)',
};
const URGENCIES: ReferralUrgency[] = ['ROUTINE', 'URGENT', 'EMERGENT'];

const label = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

const STATUS_COLOR: Record<string, string> = {
  GIVEN: 'text-emerald-400', REFUSED: 'text-rose-400', WITHDRAWN: 'text-slate-400',
  REQUESTED: 'text-amber-400', ACCEPTED: 'text-emerald-400', DECLINED: 'text-rose-400',
  COMPLETED: 'text-blue-400', CANCELLED: 'text-slate-400',
};

export function ConsentReferralView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((st) => st.user);
  const hospitalId = user?.hospitalId || '';
  const access = useCanSeeAllZones();
  // Responding to a consultation is a doctor's decision (backend enforces it);
  // hide the buttons from everyone else instead of letting them hit a 403.
  const canRespond = user?.role === 'DOCTOR' || user?.role === 'SUPER_ADMIN';
  const [pickerVisits, setPickerVisits] = useState<VisitResponse[]>([]);
  const [pickerLoading, setPickerLoading] = useState(false);
  const [visitIdInput, setVisitIdInput] = useState('');
  const [activeVisitId, setActiveVisitId] = useState('');
  const [tab, setTab] = useState<'consent' | 'referral'>('consent');
  const [loading, setLoading] = useState(false);
  const [consents, setConsents] = useState<ConsentRecord[]>([]);
  const [referrals, setReferrals] = useState<ReferralRecord[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!activeVisitId) return;
    setLoading(true);
    try {
      const [c, r] = await Promise.all([
        consentApi.getForVisit(activeVisitId).catch(() => []),
        referralApi.getForVisit(activeVisitId).catch(() => []),
      ]);
      setConsents(c || []);
      setReferrals(r || []);
    } finally {
      setLoading(false);
    }
  }, [activeVisitId]);

  useEffect(() => { load(); }, [load]);

  // Current-patients dropdown (same caller-aware pattern as Clinical
  // Documentation): zone staff see their zone's active visits, oversight
  // roles see the whole hospital.
  useEffect(() => {
    if (!hospitalId || access.isLoading) return;
    let alive = true;
    setPickerLoading(true);
    const fetchVisits = access.canSeeAllZones
      ? visitApi.getActiveByHospital(hospitalId, 0, 200)
      : visitApi.getActiveForCallerByHospital(hospitalId, 0, 200);
    fetchVisits
      .then((pageRes) => { if (alive) setPickerVisits(pageRes.content || []); })
      .catch(() => { if (alive) setPickerVisits([]); })
      .finally(() => { if (alive) setPickerLoading(false); });
    return () => { alive = false; };
  }, [hospitalId, access.isLoading, access.canSeeAllZones]);

  const visitLabel = (v: VisitResponse) => {
    const where = [v.currentEdZone?.replace(/_/g, ' '), v.currentBedLabel ? `Bed ${v.currentBedLabel}` : null]
      .filter(Boolean).join(' · ');
    return `${v.patientName} — ${v.visitNumber}${where ? ` · ${where}` : ''}`;
  };

  // ── consent form ──
  const [showConsentForm, setShowConsentForm] = useState(false);
  const [cf, setCf] = useState<{ consentType: ConsentType; procedureName: string; risksExplained: string;
    benefitsExplained: string; alternativesExplained: string; questionsAnswered: boolean;
    consentGrantor: ConsentGrantor; grantorName: string; grantorRelationship: string;
    witnessName: string; interpreterUsed: boolean; interpreterName: string; language: string;
    notes: string; status: ConsentStatus; }>({
    consentType: 'PROCEDURE', procedureName: '', risksExplained: '', benefitsExplained: '',
    alternativesExplained: '', questionsAnswered: false, consentGrantor: 'PATIENT', grantorName: '',
    grantorRelationship: '', witnessName: '', interpreterUsed: false, interpreterName: '',
    language: '', notes: '', status: 'GIVEN',
  });

  const submitConsent = async () => {
    if (!activeVisitId || !cf.procedureName.trim()) return;
    setBusy(true); setError(null);
    try {
      const trimmed = Object.fromEntries(
        Object.entries(cf).map(([k, v]) => [k, typeof v === 'string' && v.trim() === '' ? undefined : v]),
      );
      await consentApi.record(activeVisitId, { ...trimmed, visitId: activeVisitId, consentType: cf.consentType, procedureName: cf.procedureName.trim(), consentGrantor: cf.consentGrantor, status: cf.status });
      setShowConsentForm(false);
      setCf({ ...cf, procedureName: '', risksExplained: '', benefitsExplained: '', alternativesExplained: '',
        grantorName: '', grantorRelationship: '', witnessName: '', interpreterUsed: false,
        interpreterName: '', language: '', notes: '' });
      load();
    } catch (e) { setError('Failed to record consent. Check you are authorised for this visit.'); }
    finally { setBusy(false); }
  };

  const [withdrawTarget, setWithdrawTarget] = useState<ConsentRecord | null>(null);
  const withdrawConsent = async (id: string, reason: string) => {
    setBusy(true);
    try { await consentApi.withdraw(id, reason); load(); }
    catch (e) { setError('Failed to withdraw consent.'); }
    finally { setBusy(false); setWithdrawTarget(null); }
  };

  // ── referral form ──
  const [showReferralForm, setShowReferralForm] = useState(false);
  const [rf, setRf] = useState<{ referralType: ReferralType; specialty: string; urgency: ReferralUrgency;
    reasonForReferral: string; clinicalQuestion: string; targetFacility: string; }>({
    referralType: 'INTERNAL_CONSULT', specialty: '', urgency: 'ROUTINE', reasonForReferral: '',
    clinicalQuestion: '', targetFacility: '',
  });

  const submitReferral = async () => {
    if (!activeVisitId || !rf.specialty.trim() || !rf.reasonForReferral.trim()) return;
    setBusy(true); setError(null);
    try {
      await referralApi.request(activeVisitId, { visitId: activeVisitId, ...rf });
      setShowReferralForm(false);
      setRf({ ...rf, specialty: '', reasonForReferral: '', clinicalQuestion: '', targetFacility: '' });
      load();
    } catch (e) { setError('Failed to send the consultation request. Check you are authorised for this visit.'); }
    finally { setBusy(false); }
  };

  const [respondTarget, setRespondTarget] =
    useState<{ id: string; specialty: string; outcome: 'ACCEPTED' | 'DECLINED' | 'COMPLETED' } | null>(null);
  const respondReferral = async (id: string, outcome: 'ACCEPTED' | 'DECLINED' | 'COMPLETED', notes?: string) => {
    if (outcome === 'DECLINED' && !notes) return;
    setBusy(true); setError(null);
    try {
      await referralApi.respond(id, outcome === 'DECLINED'
        ? { outcome, declineReason: notes }
        : { outcome, responseNotes: notes || '' });
      load();
    } catch (e) { setError('Failed to record response (a doctor at this hospital is required).'); }
    finally { setBusy(false); setRespondTarget(null); }
  };

  const [cancelTarget, setCancelTarget] = useState<string | null>(null);
  const cancelReferral = async (id: string) => {
    setBusy(true);
    try { await referralApi.cancel(id); load(); }
    catch (e) { setError('Failed to cancel the consultation request.'); }
    finally { setBusy(false); setCancelTarget(null); }
  };

  const input = `w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-5xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center"><ShieldCheck className="w-5 h-5 text-cyan-300" /></div>
            <div>
              <h1 className="text-lg font-bold text-white tracking-wide">Consent &amp; Consultations</h1>
              <p className="text-sm text-white/50 font-medium">Informed consent and specialty consultations, per visit</p>
            </div>
          </div>
        </div>

        {/* Patient picker (current active visits) + paste-an-ID fallback */}
        <div className="rounded-2xl p-4" style={glassCard}>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-2 ${text.label}`}>
            Patient — load their consent &amp; consultation records
          </label>
          <div className="flex flex-col sm:flex-row gap-3">
            <select
              value={pickerVisits.some((v) => v.id === activeVisitId) ? activeVisitId : ''}
              onChange={(e) => { if (e.target.value) { setActiveVisitId(e.target.value); setVisitIdInput(''); } }}
              className={`flex-1 ${input}`} style={glassInner}
            >
              <option value="">
                {pickerLoading ? 'Loading current patients…'
                  : pickerVisits.length === 0 ? 'No active patients found — paste a visit ID below'
                  : 'Select a current patient…'}
              </option>
              {pickerVisits.map((v) => <option key={v.id} value={v.id}>{visitLabel(v)}</option>)}
            </select>
            <div className="relative sm:w-64">
              <Search className={`absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted}`} />
              <input value={visitIdInput} onChange={(e) => setVisitIdInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && visitIdInput.trim() && setActiveVisitId(visitIdInput.trim())}
                placeholder="…or paste a visit ID" className={`pl-10 ${input}`} style={glassInner} />
            </div>
            <button onClick={() => visitIdInput.trim() && setActiveVisitId(visitIdInput.trim())}
              className="px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white text-xs font-bold rounded-xl">Load</button>
            {activeVisitId && (
              <button onClick={load} className="w-10 h-10 rounded-xl flex items-center justify-center" style={glassInner}>
                <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} /></button>
            )}
          </div>
        </div>

        {error && (
          <div className="rounded-xl p-3 text-xs font-medium text-rose-400" style={{ ...glassInner, border: '1px solid rgba(244,63,94,0.3)' }}>{error}</div>
        )}

        {!activeVisitId ? (
          <div className="rounded-2xl p-12 text-center" style={glassCard}>
            <Search className="w-8 h-8 text-cyan-400 mx-auto mb-3" />
            <p className={`text-sm font-bold ${text.heading}`}>Select a patient to begin</p>
          </div>
        ) : (
          <>
            {/* Tabs */}
            <div className="flex gap-2">
              {([['consent', 'Consent', FileSignature], ['referral', 'Consultations', Stethoscope]] as const).map(([id, lbl, Icon]) => (
                <button key={id} onClick={() => setTab(id)}
                  className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl border transition-all ${tab === id ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent' : `${text.body} hover:bg-white/5 border-transparent`}`}>
                  <Icon className="w-3.5 h-3.5" /> {lbl}
                  <span className="ml-1 inline-flex items-center text-[10px] px-1.5 py-0.5 rounded-lg bg-white/10">{id === 'consent' ? consents.length : referrals.length}</span>
                </button>
              ))}
            </div>

            {tab === 'consent' && (
              <div className="space-y-3">
                <button onClick={() => setShowConsentForm(!showConsentForm)}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl">
                  <Plus className="w-3.5 h-3.5" /> Record Consent</button>

                {showConsentForm && (
                  <div className="rounded-2xl p-5 space-y-3" style={glassCard}>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <div>
                        <label className={`text-[10px] font-bold uppercase ${text.label}`}>Consent Type</label>
                        <select value={cf.consentType} onChange={(e) => setCf({ ...cf, consentType: e.target.value as ConsentType })} className={input} style={glassInner}>
                          {CONSENT_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
                        </select>
                      </div>
                      <div>
                        <label className={`text-[10px] font-bold uppercase ${text.label}`}>Outcome</label>
                        <select value={cf.status} onChange={(e) => setCf({ ...cf, status: e.target.value as ConsentStatus })} className={input} style={glassInner}>
                          <option value="GIVEN">Given</option><option value="REFUSED">Refused</option>
                        </select>
                      </div>
                    </div>
                    <input value={cf.procedureName} onChange={(e) => setCf({ ...cf, procedureName: e.target.value })} placeholder="Procedure / intervention name *" className={input} style={glassInner} />
                    <textarea value={cf.risksExplained} onChange={(e) => setCf({ ...cf, risksExplained: e.target.value })} placeholder="Risks explained" rows={2} className={input} style={glassInner} />
                    <textarea value={cf.benefitsExplained} onChange={(e) => setCf({ ...cf, benefitsExplained: e.target.value })} placeholder="Benefits explained" rows={2} className={input} style={glassInner} />
                    <textarea value={cf.alternativesExplained} onChange={(e) => setCf({ ...cf, alternativesExplained: e.target.value })} placeholder="Alternatives explained" rows={2} className={input} style={glassInner} />
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <div>
                        <label className={`text-[10px] font-bold uppercase ${text.label}`}>Consent given by</label>
                        <select value={cf.consentGrantor} onChange={(e) => setCf({ ...cf, consentGrantor: e.target.value as ConsentGrantor })} className={input} style={glassInner}>
                          {GRANTORS.map((g) => <option key={g} value={g}>{label(g)}</option>)}
                        </select>
                      </div>
                      <input value={cf.grantorName} onChange={(e) => setCf({ ...cf, grantorName: e.target.value })} placeholder="Grantor name" className={`mt-4 ${input}`} style={glassInner} />
                    </div>
                    {cf.consentGrantor !== 'PATIENT' && cf.consentGrantor !== 'EMERGENCY_NO_CONSENT_REQUIRED' && (
                      <input value={cf.grantorRelationship} onChange={(e) => setCf({ ...cf, grantorRelationship: e.target.value })}
                        placeholder="Relationship to patient (e.g. Father, Sister)" className={input} style={glassInner} />
                    )}
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <input value={cf.witnessName} onChange={(e) => setCf({ ...cf, witnessName: e.target.value })}
                        placeholder="Witness name (if witnessed)" className={input} style={glassInner} />
                      <input value={cf.language} onChange={(e) => setCf({ ...cf, language: e.target.value })}
                        placeholder="Language explained in (e.g. Kinyarwanda)" className={input} style={glassInner} />
                    </div>
                    <div className="flex flex-col sm:flex-row gap-3 sm:items-center">
                      <label className={`flex items-center gap-2 text-xs whitespace-nowrap ${text.body}`}>
                        <input type="checkbox" checked={cf.interpreterUsed}
                          onChange={(e) => setCf({ ...cf, interpreterUsed: e.target.checked, interpreterName: e.target.checked ? cf.interpreterName : '' })} />
                        Interpreter used
                      </label>
                      {cf.interpreterUsed && (
                        <input value={cf.interpreterName} onChange={(e) => setCf({ ...cf, interpreterName: e.target.value })}
                          placeholder="Interpreter name" className={`flex-1 ${input}`} style={glassInner} />
                      )}
                    </div>
                    <textarea value={cf.notes} onChange={(e) => setCf({ ...cf, notes: e.target.value })}
                      placeholder="Additional notes (optional)" rows={2} className={input} style={glassInner} />
                    <label className={`flex items-center gap-2 text-xs ${text.body}`}>
                      <input type="checkbox" checked={cf.questionsAnswered} onChange={(e) => setCf({ ...cf, questionsAnswered: e.target.checked })} />
                      Patient's questions were answered
                    </label>
                    <div className="flex justify-end gap-2">
                      <button onClick={() => setShowConsentForm(false)} className={`px-4 py-2 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
                      <button onClick={submitConsent} disabled={busy || !cf.procedureName.trim()} className="inline-flex items-center gap-1.5 px-5 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl disabled:opacity-50">
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />} Save</button>
                    </div>
                  </div>
                )}

                {consents.length === 0 ? (
                  <div className="rounded-2xl p-8 text-center" style={glassCard}><p className={`text-xs ${text.muted}`}>No consent records for this visit.</p></div>
                ) : consents.map((c) => (
                  <div key={c.id} className="rounded-2xl p-4" style={glassCard}>
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-slate-600" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>{label(c.consentType)}</span>
                          <span className={`text-[10px] font-bold uppercase ${STATUS_COLOR[c.status]}`}>{label(c.status)}</span>
                        </div>
                        <p className={`text-sm font-bold mt-1 ${text.heading}`}>{c.procedureName}</p>
                        <p className={`text-[11px] ${text.muted}`}>{label(c.consentGrantor)}{c.grantorName ? ` — ${c.grantorName}` : ''} · obtained by {c.obtainedByName}{c.obtainedByRole ? ` (${label(c.obtainedByRole)})` : ''} · {c.obtainedAt ? format(new Date(c.obtainedAt), 'MMM d HH:mm') : ''}</p>
                        {c.status === 'WITHDRAWN' && c.withdrawalReason && <p className={`text-[11px] mt-1 ${text.muted}`}>Withdrawn: {c.withdrawalReason}</p>}
                      </div>
                      {c.status === 'GIVEN' && (
                        <button onClick={() => setWithdrawTarget(c)} disabled={busy} className="text-[11px] font-bold text-rose-400 px-3 py-1.5 rounded-lg hover:bg-rose-500/10">Withdraw</button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {tab === 'referral' && (
              <div className="space-y-3">
                {/* Nurse-scope RBAC — requesting a consultation/referral is a
                    doctor act (the referring clinician signs it); the server
                    now 403s NURSE, so don't show a form that can only fail. */}
                {canRespond && (
                  <button onClick={() => setShowReferralForm(!showReferralForm)}
                    className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl">
                    <Plus className="w-3.5 h-3.5" /> New Consultation Request</button>
                )}

                {showReferralForm && canRespond && (
                  <div className="rounded-2xl p-5 space-y-3" style={glassCard}>
                    <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                      <div>
                        <label className={`text-[10px] font-bold uppercase ${text.label}`}>Type</label>
                        <select value={rf.referralType} onChange={(e) => setRf({ ...rf, referralType: e.target.value as ReferralType })} className={input} style={glassInner}>
                          {REFERRAL_TYPES.map((t) => <option key={t} value={t}>{CONSULT_TYPE_LABEL[t]}</option>)}
                        </select>
                      </div>
                      <input value={rf.specialty} onChange={(e) => setRf({ ...rf, specialty: e.target.value })} placeholder="Specialty / service *" className={`mt-4 ${input}`} style={glassInner} />
                      <div>
                        <label className={`text-[10px] font-bold uppercase ${text.label}`}>Urgency</label>
                        <select value={rf.urgency} onChange={(e) => setRf({ ...rf, urgency: e.target.value as ReferralUrgency })} className={input} style={glassInner}>
                          {URGENCIES.map((u) => <option key={u} value={u}>{label(u)}</option>)}
                        </select>
                      </div>
                    </div>
                    <textarea value={rf.reasonForReferral} onChange={(e) => setRf({ ...rf, reasonForReferral: e.target.value })} placeholder="Reason for consultation *" rows={2} className={input} style={glassInner} />
                    <textarea value={rf.clinicalQuestion} onChange={(e) => setRf({ ...rf, clinicalQuestion: e.target.value })} placeholder="Specific clinical question for the consultant" rows={2} className={input} style={glassInner} />
                    {rf.referralType === 'EXTERNAL_REFERRAL' && (
                      <input value={rf.targetFacility} onChange={(e) => setRf({ ...rf, targetFacility: e.target.value })} placeholder="Destination facility *" className={input} style={glassInner} />
                    )}
                    <div className="flex justify-end gap-2">
                      <button onClick={() => setShowReferralForm(false)} className={`px-4 py-2 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
                      <button onClick={submitReferral} disabled={busy || !rf.specialty.trim() || !rf.reasonForReferral.trim() || (rf.referralType === 'EXTERNAL_REFERRAL' && !rf.targetFacility.trim())} className="inline-flex items-center gap-1.5 px-5 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl disabled:opacity-50">
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Send</button>
                    </div>
                  </div>
                )}

                {referrals.length === 0 ? (
                  <div className="rounded-2xl p-8 text-center" style={glassCard}><p className={`text-xs ${text.muted}`}>No consultation requests for this visit.</p></div>
                ) : referrals.map((r) => (
                  <div key={r.id} className="rounded-2xl p-4" style={glassCard}>
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-slate-600" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>{CONSULT_TYPE_LABEL[r.referralType] ?? label(r.referralType)}</span>
                          <span className={`text-[10px] font-bold uppercase ${STATUS_COLOR[r.status]}`}>{label(r.status)}</span>
                          <span className="text-[10px] font-bold uppercase text-amber-400">{label(r.urgency)}</span>
                        </div>
                        <p className={`text-sm font-bold mt-1 ${text.heading}`}>{r.specialty}</p>
                        <p className={`text-[11px] ${text.body}`}>{r.reasonForReferral}</p>
                        {r.clinicalQuestion && <p className={`text-[11px] ${text.muted}`}>Q: {r.clinicalQuestion}</p>}
                        <p className={`text-[11px] ${text.muted}`}>requested by {r.requestedByName} · {r.requestedAt ? format(new Date(r.requestedAt), 'MMM d HH:mm') : ''}</p>
                        {r.respondedByName && (
                          <p className={`text-[11px] mt-1 ${text.body}`}>Response ({r.respondedByName}): {r.responseNotes || r.declineReason}</p>
                        )}
                      </div>
                      {(r.status === 'REQUESTED' || r.status === 'ACCEPTED') && (
                        <div className="flex flex-col gap-1 flex-shrink-0">
                          {canRespond ? (
                            <>
                              <button onClick={() => setRespondTarget({ id: r.id, specialty: r.specialty, outcome: 'ACCEPTED' })} disabled={busy} className="inline-flex items-center gap-1 text-[11px] font-bold text-emerald-400 px-2.5 py-1 rounded-lg hover:bg-emerald-500/10"><CheckCircle className="w-3 h-3" /> Accept/Reply</button>
                              <button onClick={() => setRespondTarget({ id: r.id, specialty: r.specialty, outcome: 'DECLINED' })} disabled={busy} className="inline-flex items-center gap-1 text-[11px] font-bold text-rose-400 px-2.5 py-1 rounded-lg hover:bg-rose-500/10"><XCircle className="w-3 h-3" /> Decline</button>
                              <button onClick={() => setRespondTarget({ id: r.id, specialty: r.specialty, outcome: 'COMPLETED' })} disabled={busy} className="inline-flex items-center gap-1 text-[11px] font-bold text-blue-400 px-2.5 py-1 rounded-lg hover:bg-blue-500/10"><Clock className="w-3 h-3" /> Complete</button>
                            </>
                          ) : (
                            <span className={`inline-flex items-center gap-1 text-[10px] italic px-2.5 py-1 ${text.muted}`}>
                              <Clock className="w-3 h-3" /> Awaiting doctor response
                            </span>
                          )}
                          {/* Cancelling a referral is the referring doctor's act (server 403s NURSE). */}
                          {canRespond && (
                            <button onClick={() => setCancelTarget(r.id)} disabled={busy} className={`inline-flex items-center gap-1 text-[11px] font-bold px-2.5 py-1 rounded-lg ${text.muted} hover:bg-white/10`}><X className="w-3 h-3" /> Cancel</button>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}

        <ConfirmDialog
          open={withdrawTarget !== null}
          title="Withdraw consent"
          message={`Withdraw the ${withdrawTarget ? label(withdrawTarget.consentType) : ''} consent for "${withdrawTarget?.procedureName ?? ''}"? This is recorded permanently on the legal record.`}
          confirmLabel="Withdraw consent"
          withReason reasonRequired
          reasonLabel="Reason for withdrawal"
          reasonPlaceholder="e.g. Patient changed their mind after further discussion"
          busy={busy}
          onConfirm={(reason) => withdrawTarget && reason && withdrawConsent(withdrawTarget.id, reason)}
          onClose={() => setWithdrawTarget(null)}
        />
        <ConfirmDialog
          open={respondTarget !== null}
          title={
            respondTarget?.outcome === 'DECLINED' ? 'Decline consultation'
              : respondTarget?.outcome === 'COMPLETED' ? 'Complete consultation'
              : 'Accept consultation'
          }
          message={
            respondTarget?.outcome === 'DECLINED'
              ? `Decline the ${respondTarget?.specialty ?? ''} consultation request? The reason below is shown to the requesting clinician.`
              : `Record your ${respondTarget?.outcome === 'COMPLETED' ? 'completion' : 'acceptance'} of the ${respondTarget?.specialty ?? ''} consultation. Your reply is stamped with your name and role.`
          }
          confirmLabel={
            respondTarget?.outcome === 'DECLINED' ? 'Decline request'
              : respondTarget?.outcome === 'COMPLETED' ? 'Mark completed'
              : 'Accept & reply'
          }
          tone={respondTarget?.outcome === 'DECLINED' ? 'danger' : 'primary'}
          withReason
          reasonRequired={respondTarget?.outcome === 'DECLINED'}
          reasonLabel={respondTarget?.outcome === 'DECLINED' ? 'Reason for declining' : 'Assessment / recommendation'}
          reasonPlaceholder={respondTarget?.outcome === 'DECLINED' ? 'e.g. Not a surgical candidate — manage medically' : 'e.g. Reviewed — continue current plan, repeat ECG in 4h'}
          busy={busy}
          onConfirm={(notes) => respondTarget && respondReferral(respondTarget.id, respondTarget.outcome, notes)}
          onClose={() => setRespondTarget(null)}
        />
        <ConfirmDialog
          open={cancelTarget !== null}
          title="Cancel consultation request"
          message="Are you sure you want to cancel this consultation request? The consultant will no longer see it."
          confirmLabel="Cancel request"
          busy={busy}
          onConfirm={() => cancelTarget && cancelReferral(cancelTarget)}
          onClose={() => setCancelTarget(null)}
        />
      </div>
    </div>
  );
}
