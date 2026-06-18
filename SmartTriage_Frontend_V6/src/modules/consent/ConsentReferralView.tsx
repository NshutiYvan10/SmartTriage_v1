/* ═══════════════════════════════════════════════════════════════
   Consent & Referrals — per-visit informed consent + consultation/referral
   Both record the acting clinician from the authenticated session (server-side).
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  ShieldCheck, Search, Plus, Loader2, RefreshCw, X, Stethoscope, FileSignature,
  CheckCircle, XCircle, Clock, Send,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
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
const REFERRAL_TYPES: ReferralType[] = ['INTERNAL_CONSULT', 'EXTERNAL_REFERRAL', 'ICU_ADMISSION_REQUEST'];
const URGENCIES: ReferralUrgency[] = ['ROUTINE', 'URGENT', 'EMERGENT'];

const label = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

const STATUS_COLOR: Record<string, string> = {
  GIVEN: 'text-emerald-600', REFUSED: 'text-rose-600', WITHDRAWN: 'text-slate-500',
  REQUESTED: 'text-amber-600', ACCEPTED: 'text-emerald-600', DECLINED: 'text-rose-600',
  COMPLETED: 'text-blue-600', CANCELLED: 'text-slate-500',
};

export function ConsentReferralView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
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

  // ── consent form ──
  const [showConsentForm, setShowConsentForm] = useState(false);
  const [cf, setCf] = useState<{ consentType: ConsentType; procedureName: string; risksExplained: string;
    benefitsExplained: string; alternativesExplained: string; questionsAnswered: boolean;
    consentGrantor: ConsentGrantor; grantorName: string; grantorRelationship: string;
    status: ConsentStatus; }>({
    consentType: 'PROCEDURE', procedureName: '', risksExplained: '', benefitsExplained: '',
    alternativesExplained: '', questionsAnswered: false, consentGrantor: 'PATIENT', grantorName: '',
    grantorRelationship: '', status: 'GIVEN',
  });

  const submitConsent = async () => {
    if (!activeVisitId || !cf.procedureName.trim()) return;
    setBusy(true); setError(null);
    try {
      await consentApi.record(activeVisitId, { visitId: activeVisitId, ...cf });
      setShowConsentForm(false);
      setCf({ ...cf, procedureName: '', risksExplained: '', benefitsExplained: '', alternativesExplained: '', grantorName: '', grantorRelationship: '' });
      load();
    } catch (e) { setError('Failed to record consent. Check you are authorised for this visit.'); }
    finally { setBusy(false); }
  };

  const withdrawConsent = async (id: string) => {
    const reason = window.prompt('Reason for withdrawing consent?');
    if (!reason) return;
    setBusy(true);
    try { await consentApi.withdraw(id, reason); load(); }
    catch (e) { setError('Failed to withdraw consent.'); }
    finally { setBusy(false); }
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
    } catch (e) { setError('Failed to raise referral. Check you are authorised for this visit.'); }
    finally { setBusy(false); }
  };

  const respondReferral = async (id: string, outcome: 'ACCEPTED' | 'DECLINED' | 'COMPLETED') => {
    const notes = window.prompt(outcome === 'DECLINED' ? 'Reason for declining:' : 'Response notes (assessment / recommendation):') || '';
    if (outcome === 'DECLINED' && !notes) return;
    setBusy(true); setError(null);
    try {
      await referralApi.respond(id, outcome === 'DECLINED'
        ? { outcome, declineReason: notes }
        : { outcome, responseNotes: notes });
      load();
    } catch (e) { setError('Failed to record response (a supervising clinician at this hospital is required).'); }
    finally { setBusy(false); }
  };

  const cancelReferral = async (id: string) => {
    if (!window.confirm('Cancel this referral?')) return;
    setBusy(true);
    try { await referralApi.cancel(id); load(); }
    catch (e) { setError('Failed to cancel referral.'); }
    finally { setBusy(false); }
  };

  const input = `w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-5xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-4">
            <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center"><ShieldCheck className="w-6 h-6 text-white" /></div>
            <div>
              <h1 className="text-lg font-bold text-white tracking-wide">Consent &amp; Referrals</h1>
              <p className="text-white/70 text-xs font-medium">Informed consent and specialty consultation / referral, per visit</p>
            </div>
          </div>
        </div>

        {/* Visit search */}
        <div className="rounded-2xl p-4" style={glassCard}>
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input value={visitIdInput} onChange={(e) => setVisitIdInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && setActiveVisitId(visitIdInput.trim())}
                placeholder="Enter Visit ID..." className={`pl-10 ${input}`} style={glassInner} />
            </div>
            <button onClick={() => setActiveVisitId(visitIdInput.trim())}
              className="px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white text-xs font-bold rounded-xl">Load</button>
            {activeVisitId && (
              <button onClick={load} className="w-10 h-10 rounded-xl flex items-center justify-center" style={glassInner}>
                <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} /></button>
            )}
          </div>
        </div>

        {error && (
          <div className="rounded-xl p-3 text-xs font-medium text-rose-600" style={{ ...glassInner, border: '1px solid rgba(244,63,94,0.3)' }}>{error}</div>
        )}

        {!activeVisitId ? (
          <div className="rounded-2xl p-12 text-center" style={glassCard}>
            <Search className="w-8 h-8 text-cyan-400 mx-auto mb-3" />
            <p className={`text-sm font-bold ${text.heading}`}>Enter a Visit ID to begin</p>
          </div>
        ) : (
          <>
            {/* Tabs */}
            <div className="flex gap-2">
              {([['consent', 'Consent', FileSignature], ['referral', 'Referrals', Stethoscope]] as const).map(([id, lbl, Icon]) => (
                <button key={id} onClick={() => setTab(id)}
                  className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl transition-all ${tab === id ? 'bg-gradient-to-r from-cyan-600 to-cyan-500 text-white' : isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-white/60'}`}>
                  <Icon className="w-3.5 h-3.5" /> {lbl}
                  <span className="ml-1 text-[10px] px-1.5 py-0.5 rounded-md bg-black/10">{id === 'consent' ? consents.length : referrals.length}</span>
                </button>
              ))}
            </div>

            {tab === 'consent' && (
              <div className="space-y-3">
                <button onClick={() => setShowConsentForm(!showConsentForm)}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl">
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
                    <label className={`flex items-center gap-2 text-xs ${text.body}`}>
                      <input type="checkbox" checked={cf.questionsAnswered} onChange={(e) => setCf({ ...cf, questionsAnswered: e.target.checked })} />
                      Patient's questions were answered
                    </label>
                    <div className="flex justify-end gap-2">
                      <button onClick={() => setShowConsentForm(false)} className={`px-4 py-2 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
                      <button onClick={submitConsent} disabled={busy || !cf.procedureName.trim()} className="inline-flex items-center gap-1.5 px-5 py-2 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl disabled:opacity-50">
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
                          <span className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-md" style={glassInner}>{label(c.consentType)}</span>
                          <span className={`text-[10px] font-bold uppercase ${STATUS_COLOR[c.status]}`}>{label(c.status)}</span>
                        </div>
                        <p className={`text-sm font-bold mt-1 ${text.heading}`}>{c.procedureName}</p>
                        <p className={`text-[11px] ${text.muted}`}>{label(c.consentGrantor)}{c.grantorName ? ` — ${c.grantorName}` : ''} · obtained by {c.obtainedByName}{c.obtainedByRole ? ` (${label(c.obtainedByRole)})` : ''} · {c.obtainedAt ? format(new Date(c.obtainedAt), 'MMM d HH:mm') : ''}</p>
                        {c.status === 'WITHDRAWN' && c.withdrawalReason && <p className="text-[11px] text-slate-500 mt-1">Withdrawn: {c.withdrawalReason}</p>}
                      </div>
                      {c.status === 'GIVEN' && (
                        <button onClick={() => withdrawConsent(c.id)} disabled={busy} className="text-[11px] font-bold text-rose-600 px-3 py-1.5 rounded-lg hover:bg-rose-500/10">Withdraw</button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {tab === 'referral' && (
              <div className="space-y-3">
                <button onClick={() => setShowReferralForm(!showReferralForm)}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl">
                  <Plus className="w-3.5 h-3.5" /> Raise Referral / Consult</button>

                {showReferralForm && (
                  <div className="rounded-2xl p-5 space-y-3" style={glassCard}>
                    <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                      <div>
                        <label className={`text-[10px] font-bold uppercase ${text.label}`}>Type</label>
                        <select value={rf.referralType} onChange={(e) => setRf({ ...rf, referralType: e.target.value as ReferralType })} className={input} style={glassInner}>
                          {REFERRAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
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
                    <textarea value={rf.reasonForReferral} onChange={(e) => setRf({ ...rf, reasonForReferral: e.target.value })} placeholder="Reason for referral *" rows={2} className={input} style={glassInner} />
                    <textarea value={rf.clinicalQuestion} onChange={(e) => setRf({ ...rf, clinicalQuestion: e.target.value })} placeholder="Specific clinical question for the consultant" rows={2} className={input} style={glassInner} />
                    {rf.referralType === 'EXTERNAL_REFERRAL' && (
                      <input value={rf.targetFacility} onChange={(e) => setRf({ ...rf, targetFacility: e.target.value })} placeholder="Destination facility" className={input} style={glassInner} />
                    )}
                    <div className="flex justify-end gap-2">
                      <button onClick={() => setShowReferralForm(false)} className={`px-4 py-2 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
                      <button onClick={submitReferral} disabled={busy || !rf.specialty.trim() || !rf.reasonForReferral.trim()} className="inline-flex items-center gap-1.5 px-5 py-2 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl disabled:opacity-50">
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Send</button>
                    </div>
                  </div>
                )}

                {referrals.length === 0 ? (
                  <div className="rounded-2xl p-8 text-center" style={glassCard}><p className={`text-xs ${text.muted}`}>No referrals for this visit.</p></div>
                ) : referrals.map((r) => (
                  <div key={r.id} className="rounded-2xl p-4" style={glassCard}>
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-md" style={glassInner}>{label(r.referralType)}</span>
                          <span className={`text-[10px] font-bold uppercase ${STATUS_COLOR[r.status]}`}>{label(r.status)}</span>
                          <span className="text-[10px] font-bold uppercase text-amber-600">{label(r.urgency)}</span>
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
                          <button onClick={() => respondReferral(r.id, 'ACCEPTED')} disabled={busy} className="inline-flex items-center gap-1 text-[11px] font-bold text-emerald-600 px-2.5 py-1 rounded-lg hover:bg-emerald-500/10"><CheckCircle className="w-3 h-3" /> Accept/Reply</button>
                          <button onClick={() => respondReferral(r.id, 'DECLINED')} disabled={busy} className="inline-flex items-center gap-1 text-[11px] font-bold text-rose-600 px-2.5 py-1 rounded-lg hover:bg-rose-500/10"><XCircle className="w-3 h-3" /> Decline</button>
                          <button onClick={() => respondReferral(r.id, 'COMPLETED')} disabled={busy} className="inline-flex items-center gap-1 text-[11px] font-bold text-blue-600 px-2.5 py-1 rounded-lg hover:bg-blue-500/10"><Clock className="w-3 h-3" /> Complete</button>
                          <button onClick={() => cancelReferral(r.id)} disabled={busy} className={`inline-flex items-center gap-1 text-[11px] font-bold px-2.5 py-1 rounded-lg ${text.muted} hover:bg-white/10`}><X className="w-3 h-3" /> Cancel</button>
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
