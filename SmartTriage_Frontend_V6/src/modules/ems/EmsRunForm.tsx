/* ═══════════════════════════════════════════════════════════════
   EmsRunForm — multi-step run capture for paramedics.

   Steps:
     1. Patient & incident
     2. Field vitals
     3. Field triage (RED/ORANGE/YELLOW/GREEN/BLUE + reason)
     4. Treatments (chip picker + free text)
     5. Send to ED (preregister)

   Save-and-continue at every step so a partially filled run isn't
   lost. The first save (step 1) creates the run; subsequent steps
   patch it.
   ═══════════════════════════════════════════════════════════════ */

import { useEffect, useState } from 'react';
import {
  X, ChevronLeft, ChevronRight, Loader2, Send, AlertOctagon,
  Check, Plus, Trash2, Siren,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { emsApi } from '@/api/ems';
import type {
  EmsRun, FieldTriageCategory, EmsService, EmsInterventionType,
} from '@/api/ems';

const TRIAGE_OPTIONS: { v: FieldTriageCategory; label: string; color: string }[] = [
  { v: 'RED',    label: 'RED — life-threatening',     color: 'bg-rose-500' },
  { v: 'ORANGE', label: 'ORANGE — very urgent',       color: 'bg-amber-500' },
  { v: 'YELLOW', label: 'YELLOW — urgent',            color: 'bg-yellow-500' },
  { v: 'GREEN',  label: 'GREEN — standard',           color: 'bg-emerald-500' },
  { v: 'BLUE',   label: 'BLUE — non-urgent',          color: 'bg-blue-500' },
];

/**
 * Preset interventions. Structured `dose` / `route` fields are filled
 * at preset-pick time so the paramedic can save directly without
 * typing — but the "Refine details" form still appears with the
 * preset values populated, so they can amend (e.g. choose a
 * different IV gauge or change the dose).
 */
interface InterventionPreset {
  type: EmsInterventionType;
  detail: string;
  dose?: string;
  route?: string;
}
const COMMON_INTERVENTIONS: InterventionPreset[] = [
  { type: 'OXYGEN',          detail: 'O₂ via NRB mask',           dose: '6 L/min',  route: 'NRB' },
  { type: 'OXYGEN',          detail: 'O₂ via nasal cannula',      dose: '2 L/min',  route: 'NC' },
  { type: 'IV_ACCESS',       detail: 'IV cannula L antecubital',                     route: '18G' },
  { type: 'IV_ACCESS',       detail: 'IV cannula R hand',                            route: '20G' },
  { type: 'FLUID',           detail: 'NaCl 0.9%',                 dose: '500 ml',   route: 'IV' },
  { type: 'FLUID',           detail: "Ringer's lactate",          dose: '1 L',      route: 'IV' },
  { type: 'MEDICATION',      detail: 'Adrenaline',                dose: '1 mg',     route: 'IV' },
  { type: 'MEDICATION',      detail: 'Atropine',                  dose: '0.5 mg',   route: 'IV' },
  { type: 'MEDICATION',      detail: 'Dextrose 50%',              dose: '25 ml',    route: 'IV' },
  { type: 'MEDICATION',      detail: 'Naloxone',                  dose: '0.4 mg',   route: 'IM' },
  { type: 'MEDICATION',      detail: 'Salbutamol',                dose: '5 mg',     route: 'NEB' },
  { type: 'MEDICATION',      detail: 'Tranexamic acid',           dose: '1 g',      route: 'IV' },
  { type: 'IMMOBILISATION',  detail: 'C-spine collar' },
  { type: 'IMMOBILISATION',  detail: 'Pelvic binder' },
  { type: 'TOURNIQUET',      detail: 'Tourniquet — extremity' },
  { type: 'AIRWAY',          detail: 'OPA inserted' },
  { type: 'AIRWAY',          detail: 'BVM ventilation' },
  { type: 'CPR',             detail: 'CPR / chest compressions' },
  { type: 'DEFIBRILLATION',  detail: 'AED shock',                 dose: '200 J' },
  { type: 'SPLINTING',       detail: 'Splint applied' },
];

const ROUTE_OPTIONS = ['IV', 'IM', 'IO', 'PO', 'SC', 'NEB', 'NRB', 'NC', 'BVM', 'TOPICAL', 'OTHER'];

interface Props {
  run: EmsRun | null;        // null = create new
  hospitalId: string;
  onClose: () => void;
  onSaved: () => void;
}

export function EmsRunForm({ run, hospitalId, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();

  const [step, setStep] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [current, setCurrent] = useState<EmsRun | null>(run);

  // Working draft (only the editable fields)
  const [draft, setDraft] = useState({
    service: (run?.service ?? 'OTHER') as EmsService,
    unitCallsign: run?.unitCallsign ?? '',
    patientAgeYears: run?.patientAgeYears ?? '',
    patientSex: run?.patientSex ?? '',
    incidentLocation: run?.incidentLocation ?? '',
    mechanism: run?.mechanism ?? '',
    historySummary: run?.historySummary ?? '',
    injuriesObserved: run?.injuriesObserved ?? '',
    fieldGcs: run?.fieldGcs ?? '',
    fieldRespRate: run?.fieldRespRate ?? '',
    fieldHr: run?.fieldHr ?? '',
    fieldSbp: run?.fieldSbp ?? '',
    fieldDbp: run?.fieldDbp ?? '',
    fieldSpo2: run?.fieldSpo2 ?? '',
    fieldTemp: run?.fieldTemp ?? '',
    fieldGlucose: run?.fieldGlucose ?? '',
    fieldTriageCategory: (run?.fieldTriageCategory ?? '') as FieldTriageCategory | '',
    fieldTriageReason: run?.fieldTriageReason ?? '',
    etaMinutes: run?.etaMinutes ?? '',
    notes: run?.notes ?? '',
  });

  useEffect(() => {
    setCurrent(run);
  }, [run]);

  const num = (v: any) => (v === '' || v === null || v === undefined ? undefined : Number(v));

  async function saveStep() {
    setSubmitting(true);
    setError(null);
    try {
      if (!current) {
        // Step 1 first save → create the run
        const created = await emsApi.create({
          hospitalId,
          service: draft.service || undefined,
          unitCallsign: draft.unitCallsign || undefined,
          patientAgeYears: num(draft.patientAgeYears),
          patientSex: draft.patientSex || undefined,
          incidentLocation: draft.incidentLocation || undefined,
          mechanism: draft.mechanism || undefined,
          historySummary: draft.historySummary || undefined,
        });
        setCurrent(created);
      } else {
        const updated = await emsApi.update(current.id, {
          unitCallsign: draft.unitCallsign || undefined,
          patientAgeYears: num(draft.patientAgeYears),
          patientSex: draft.patientSex || undefined,
          incidentLocation: draft.incidentLocation || undefined,
          mechanism: draft.mechanism || undefined,
          historySummary: draft.historySummary || undefined,
          injuriesObserved: draft.injuriesObserved || undefined,
          fieldGcs: num(draft.fieldGcs),
          fieldRespRate: num(draft.fieldRespRate),
          fieldHr: num(draft.fieldHr),
          fieldSbp: num(draft.fieldSbp),
          fieldDbp: num(draft.fieldDbp),
          fieldSpo2: num(draft.fieldSpo2),
          fieldTemp: num(draft.fieldTemp),
          fieldGlucose: num(draft.fieldGlucose),
          fieldTriageCategory: (draft.fieldTriageCategory || undefined) as FieldTriageCategory | undefined,
          fieldTriageReason: draft.fieldTriageReason || undefined,
          etaMinutes: num(draft.etaMinutes),
          notes: draft.notes || undefined,
        });
        setCurrent(updated);
      }
      return true;
    } catch (err: any) {
      setError(err?.message || 'Failed to save');
      return false;
    } finally {
      setSubmitting(false);
    }
  }

  async function next() {
    const ok = await saveStep();
    if (ok) setStep((s) => Math.min(5, s + 1));
  }

  function prev() {
    setError(null);
    setStep((s) => Math.max(1, s - 1));
  }

  async function sendToEd() {
    if (!current) return;
    setSubmitting(true);
    setError(null);
    try {
      await emsApi.preregister(current.id, {
        etaMinutes: num(draft.etaMinutes) as number | undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to send pre-arrival');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4 bg-black/60 backdrop-blur-sm">
      <div className="rounded-2xl p-4 sm:p-6 max-w-2xl w-full max-h-[95vh] overflow-y-auto animate-fade-up" style={glassCard}>
        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-rose-500/15 flex items-center justify-center">
              <Siren className="w-5 h-5 text-rose-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>
                {current ? `Run ${current.id.slice(0, 8)}` : 'New ambulance run'}
              </h3>
              <p className={`text-xs ${text.muted}`}>Step {step} of 5</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-1 mb-5">
          {[1, 2, 3, 4, 5].map((n) => (
            <div key={n}
              className={`h-1.5 flex-1 rounded-full ${n <= step ? 'bg-rose-500' : isDark ? 'bg-white/10' : 'bg-slate-200'}`}
            />
          ))}
        </div>

        {step === 1 && (
          <Step1Patient draft={draft} setDraft={setDraft} text={text} glassInner={glassInner} isDark={isDark} />
        )}
        {step === 2 && (
          <Step2Vitals draft={draft} setDraft={setDraft} text={text} glassInner={glassInner} isDark={isDark} />
        )}
        {step === 3 && (
          <Step3Triage draft={draft} setDraft={setDraft} text={text} glassInner={glassInner} isDark={isDark} />
        )}
        {step === 4 && current && (
          <Step4Treatments run={current} text={text} glassInner={glassInner} isDark={isDark} onChanged={() => emsApi.getById(current.id).then(setCurrent)} />
        )}
        {step === 5 && (
          <Step5Send draft={draft} setDraft={setDraft} text={text} glassInner={glassInner} isDark={isDark} run={current} />
        )}

        {error && (
          <div className="rounded-xl px-3 py-2 my-3 text-xs font-semibold bg-rose-500/10 text-rose-500">
            {error}
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-between gap-2 mt-5 pt-4 border-t border-slate-500/10">
          <button
            onClick={prev}
            disabled={step === 1 || submitting}
            className={`inline-flex items-center gap-1 px-3 py-2 rounded-xl text-xs font-bold ${text.muted} hover:bg-white/5 disabled:opacity-30`}
          >
            <ChevronLeft className="w-3.5 h-3.5" /> Back
          </button>
          {step < 5 ? (
            <button
              onClick={next}
              disabled={submitting}
              className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-rose-600 to-rose-500 disabled:opacity-50"
            >
              {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
              Save & continue <ChevronRight className="w-3.5 h-3.5" />
            </button>
          ) : (
            <button
              onClick={sendToEd}
              disabled={submitting || !current}
              className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-amber-600 to-amber-500 disabled:opacity-50"
            >
              {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
              Send pre-arrival to ED
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 1 — patient & incident
// ─────────────────────────────────────────────────────────────────

function Step1Patient({ draft, setDraft, text, glassInner, isDark }: any) {
  const inputClass = `w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  return (
    <div className="space-y-3">
      <h4 className={`text-sm font-bold ${text.heading}`}>Patient & incident</h4>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label text={text}>Age (years)</Label>
          <input type="number" value={draft.patientAgeYears} onChange={(e) => setDraft({ ...draft, patientAgeYears: e.target.value })} className={inputClass} style={glassInner} />
        </div>
        <div>
          <Label text={text}>Sex</Label>
          <select value={draft.patientSex} onChange={(e) => setDraft({ ...draft, patientSex: e.target.value })} className={inputClass} style={glassInner}>
            <option value="">Unknown</option>
            <option value="MALE">Male</option>
            <option value="FEMALE">Female</option>
          </select>
        </div>
        <div>
          <Label text={text}>Service</Label>
          <select value={draft.service} onChange={(e) => setDraft({ ...draft, service: e.target.value })} className={inputClass} style={glassInner}>
            <option value="SAMU">SAMU</option>
            <option value="HOSPITAL">Hospital</option>
            <option value="PRIVATE">Private</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
        <div>
          <Label text={text}>Unit callsign</Label>
          <input value={draft.unitCallsign} onChange={(e) => setDraft({ ...draft, unitCallsign: e.target.value })} placeholder="SAMU-K7" className={inputClass} style={glassInner} />
        </div>
        <div className="col-span-2">
          <Label text={text}>Incident location</Label>
          <input value={draft.incidentLocation} onChange={(e) => setDraft({ ...draft, incidentLocation: e.target.value })} placeholder="Village / road / coordinates" className={inputClass} style={glassInner} />
        </div>
        <div className="col-span-2">
          <Label text={text}>Mechanism / chief complaint</Label>
          <input value={draft.mechanism} onChange={(e) => setDraft({ ...draft, mechanism: e.target.value })} placeholder="RTA — motorcycle vs. car / chest pain / seizure" className={inputClass} style={glassInner} />
        </div>
        <div className="col-span-2">
          <Label text={text}>History on scene</Label>
          <textarea rows={3} value={draft.historySummary} onChange={(e) => setDraft({ ...draft, historySummary: e.target.value })} placeholder="Witnessed event? Time of incident? Allergies? Meds?" className={`${inputClass} resize-none`} style={glassInner} />
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 2 — vitals
// ─────────────────────────────────────────────────────────────────

function Step2Vitals({ draft, setDraft, text, glassInner, isDark }: any) {
  const inputClass = `w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  const numField = (key: string, label: string, ph?: string) => (
    <div>
      <Label text={text}>{label}</Label>
      <input type="number" step="any" value={(draft as any)[key]} placeholder={ph}
        onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
        className={inputClass} style={glassInner} />
    </div>
  );
  return (
    <div className="space-y-3">
      <h4 className={`text-sm font-bold ${text.heading}`}>Field vitals</h4>
      <p className={`text-xs ${text.muted}`}>Snapshot at scene. Serial readings on the way are still useful — log them as you go.</p>
      <div className="grid grid-cols-2 gap-3">
        {numField('fieldGcs', 'GCS (3–15)', '15')}
        {numField('fieldRespRate', 'Resp rate', '16')}
        {numField('fieldHr', 'Heart rate', '80')}
        {numField('fieldSpo2', 'SpO₂ %', '98')}
        {numField('fieldSbp', 'SBP', '120')}
        {numField('fieldDbp', 'DBP', '80')}
        {numField('fieldTemp', 'Temp °C', '37.0')}
        {numField('fieldGlucose', 'Glucose mmol/L', '5.5')}
      </div>
      <div>
        <Label text={text}>Injuries observed</Label>
        <textarea rows={2} value={draft.injuriesObserved} onChange={(e) => setDraft({ ...draft, injuriesObserved: e.target.value })} placeholder="Open femur fracture, head laceration, etc." className={`${inputClass} resize-none`} style={glassInner} />
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 3 — field triage
// ─────────────────────────────────────────────────────────────────

function Step3Triage({ draft, setDraft, text, glassInner, isDark }: any) {
  const inputClass = `w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  return (
    <div className="space-y-3">
      <h4 className={`text-sm font-bold ${text.heading}`}>Field triage</h4>
      <p className={`text-xs ${text.muted}`}>The ED will re-triage on arrival. Your call drives bay prep and pre-arrival severity.</p>
      <div className="space-y-2">
        {TRIAGE_OPTIONS.map((opt) => (
          <label
            key={opt.v}
            className={`flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer ${draft.fieldTriageCategory === opt.v ? 'ring-2 ring-rose-500/40' : ''}`}
            style={glassInner}
          >
            <input
              type="radio"
              name="triage"
              checked={draft.fieldTriageCategory === opt.v}
              onChange={() => setDraft({ ...draft, fieldTriageCategory: opt.v })}
              className="w-4 h-4 accent-rose-500"
            />
            <span className={`w-3 h-3 rounded-full ${opt.color}`} />
            <span className={`text-xs font-semibold ${text.body}`}>{opt.label}</span>
          </label>
        ))}
      </div>
      <div>
        <Label text={text}>Reason / one-line rationale</Label>
        <input value={draft.fieldTriageReason} onChange={(e) => setDraft({ ...draft, fieldTriageReason: e.target.value })}
          placeholder="GCS 9, hypotensive, suspected internal bleeding"
          className={inputClass} style={glassInner} />
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 4 — treatments
// ─────────────────────────────────────────────────────────────────

/**
 * Step 4 — interventions. Each save records structured dose/route/
 * given-by/outcome on the row, not just free-text detail. Tapping a
 * preset pre-fills the staging panel with the preset's values; the
 * paramedic confirms or amends before saving.
 */
function Step4Treatments({ run, text, glassInner, isDark, onChanged }: any) {
  const [staged, setStaged] = useState<InterventionPreset & { givenByName?: string; outcome?: string; notes?: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputClass = `w-full px-3 py-2 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;

  function pickPreset(p: InterventionPreset) {
    setError(null);
    setStaged({ ...p });
  }

  function pickCustom() {
    setError(null);
    setStaged({ type: 'OTHER', detail: '' });
  }

  async function save() {
    if (!staged || !staged.detail.trim()) {
      setError('Description is required');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await emsApi.addIntervention(run.id, {
        type: staged.type,
        detail: staged.detail,
        dose: staged.dose || undefined,
        route: staged.route || undefined,
        givenByName: staged.givenByName || undefined,
        outcome: staged.outcome || undefined,
        notes: staged.notes || undefined,
      });
      setStaged(null);
      onChanged();
    } catch (e: any) {
      setError(e?.message || 'Failed to save intervention');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <h4 className={`text-sm font-bold ${text.heading}`}>Treatments given</h4>
      <p className={`text-xs ${text.muted}`}>
        Tap a preset to start, then confirm dose / route / given-by before saving. Structured fields make pre-arrival drugs queryable by the doctor at the door.
      </p>

      {/* Preset chips */}
      {!staged && (
        <div className="flex flex-wrap gap-1.5">
          {COMMON_INTERVENTIONS.map((it, i) => (
            <button
              key={i}
              onClick={() => pickPreset(it)}
              disabled={busy}
              className={`inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold ${isDark ? 'bg-white/5 hover:bg-white/10 text-white/80' : 'bg-slate-100 hover:bg-slate-200 text-slate-700'} disabled:opacity-50`}
            >
              <Plus className="w-3 h-3" /> {it.detail}
              {it.dose && <span className="opacity-60">— {it.dose}</span>}
            </button>
          ))}
          <button
            onClick={pickCustom}
            className={`inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold border-2 border-dashed ${isDark ? 'border-white/20 text-white/70 hover:bg-white/5' : 'border-slate-300 text-slate-600 hover:bg-slate-50'}`}
          >
            <Plus className="w-3 h-3" /> Custom
          </button>
        </div>
      )}

      {/* Staging panel — confirm structured fields */}
      {staged && (
        <div className="rounded-xl p-3 space-y-2 ring-1 ring-rose-500/20" style={glassInner}>
          <div className="flex items-center justify-between">
            <div className={`text-[10px] uppercase font-bold ${text.label}`}>Confirm details</div>
            <button onClick={() => setStaged(null)} className={`text-[10px] ${text.muted} hover:underline`}>Cancel</button>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <Label text={text}>Type</Label>
              <select
                value={staged.type}
                onChange={(e) => setStaged({ ...staged, type: e.target.value as EmsInterventionType })}
                className={inputClass} style={glassInner}
              >
                {(['OXYGEN','IV_ACCESS','FLUID','MEDICATION','DEFIBRILLATION','AIRWAY','IMMOBILISATION','SPLINTING','TOURNIQUET','CPR','OTHER'] as EmsInterventionType[]).map(t => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </div>
            <div>
              <Label text={text}>Description <span className="text-rose-500">*</span></Label>
              <input
                value={staged.detail}
                onChange={(e) => setStaged({ ...staged, detail: e.target.value })}
                placeholder="Adrenaline / O₂ NRB / IV cannula"
                className={inputClass} style={glassInner}
                autoFocus
              />
            </div>
            <div>
              <Label text={text}>Dose</Label>
              <input
                value={staged.dose ?? ''}
                onChange={(e) => setStaged({ ...staged, dose: e.target.value })}
                placeholder="1 mg / 500 ml / 6 L/min"
                className={inputClass} style={glassInner}
              />
            </div>
            <div>
              <Label text={text}>Route</Label>
              <select
                value={staged.route ?? ''}
                onChange={(e) => setStaged({ ...staged, route: e.target.value })}
                className={inputClass} style={glassInner}
              >
                <option value=""></option>
                {ROUTE_OPTIONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div>
              <Label text={text}>Given by</Label>
              <input
                value={staged.givenByName ?? ''}
                onChange={(e) => setStaged({ ...staged, givenByName: e.target.value })}
                placeholder="Crew member"
                className={inputClass} style={glassInner}
              />
            </div>
            <div>
              <Label text={text}>Outcome</Label>
              <input
                value={staged.outcome ?? ''}
                onChange={(e) => setStaged({ ...staged, outcome: e.target.value })}
                placeholder="ROSC at 14:08 / tolerated"
                className={inputClass} style={glassInner}
              />
            </div>
            <div className="col-span-2">
              <Label text={text}>Notes (optional)</Label>
              <input
                value={staged.notes ?? ''}
                onChange={(e) => setStaged({ ...staged, notes: e.target.value })}
                className={inputClass} style={glassInner}
              />
            </div>
          </div>
          <button
            onClick={save}
            disabled={busy || !staged.detail.trim()}
            className="w-full inline-flex items-center justify-center gap-2 px-3 py-2 rounded-xl bg-rose-500 text-white text-xs font-bold hover:bg-rose-600 disabled:opacity-50"
          >
            {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
            Save intervention
          </button>
        </div>
      )}

      {/* Logged interventions — surface structured fields */}
      {run.interventions && run.interventions.length > 0 && (
        <div className="space-y-1.5">
          <div className={`text-[10px] uppercase font-bold ${text.label}`}>Logged ({run.interventions.length})</div>
          {run.interventions.map((iv: any) => (
            <div key={iv.id} className="rounded-xl px-3 py-2 text-xs" style={glassInner}>
              <div className="flex items-center justify-between gap-2">
                <span className={text.body}>
                  <span className={`text-[10px] font-bold mr-1 ${text.label}`}>{iv.type}</span>
                  {iv.detail || ''}
                  {iv.dose && <span className={`ml-1 ${text.muted}`}>• {iv.dose}</span>}
                  {iv.route && <span className={`ml-1 ${text.muted}`}>• {iv.route}</span>}
                </span>
                <span className={`text-[10px] ${text.muted} shrink-0`}>
                  {new Date(iv.givenAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
              </div>
              {(iv.givenByName || iv.outcome) && (
                <div className={`text-[10px] mt-0.5 ${text.muted}`}>
                  {iv.givenByName && <>by {iv.givenByName}</>}
                  {iv.givenByName && iv.outcome && ' • '}
                  {iv.outcome && <>outcome: {iv.outcome}</>}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {error && <div className="rounded-xl px-3 py-2 text-xs font-semibold bg-rose-500/10 text-rose-500">{error}</div>}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 5 — send pre-arrival ping
// ─────────────────────────────────────────────────────────────────

function Step5Send({ draft, setDraft, text, glassInner, isDark, run }: any) {
  const inputClass = `w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  return (
    <div className="space-y-3">
      <h4 className={`text-sm font-bold ${text.heading}`}>Send pre-arrival to ED</h4>
      <p className={`text-xs ${text.muted}`}>
        The receiving hospital's charge nurse will see your run on their inbound board with the field triage,
        vitals and treatments. Bay prep starts as soon as you tap send.
      </p>

      <div className="rounded-xl p-3" style={glassInner}>
        <div className={`text-[10px] uppercase font-bold mb-2 ${text.label}`}>Summary</div>
        <div className={`text-xs ${text.body} space-y-1`}>
          <div><b>Patient:</b> {run?.patientAgeYears ?? '—'}y {run?.patientSex ?? ''}</div>
          <div><b>Mechanism:</b> {run?.mechanism ?? '—'}</div>
          <div><b>Field triage:</b> {run?.fieldTriageCategory ?? '—'}</div>
          <div><b>Vitals:</b> GCS {run?.fieldGcs ?? '—'} • HR {run?.fieldHr ?? '—'} • BP {run?.fieldSbp ?? '—'}/{run?.fieldDbp ?? '—'} • SpO₂ {run?.fieldSpo2 ?? '—'}%</div>
          <div><b>Interventions:</b> {run?.interventions?.length ?? 0}</div>
        </div>
      </div>

      <div>
        <Label text={text}>ETA (minutes)</Label>
        <input type="number" value={draft.etaMinutes} onChange={(e) => setDraft({ ...draft, etaMinutes: e.target.value })}
          placeholder="8" className={inputClass} style={glassInner} />
      </div>

      <div className="rounded-xl p-3 bg-amber-500/10 ring-1 ring-amber-500/20 flex items-start gap-2">
        <AlertOctagon className="w-4 h-4 text-amber-500 mt-0.5 shrink-0" />
        <p className={`text-[11px] ${text.body}`}>
          For RED / cardiac-arrest cases also use the resus call channel. The ED nurse will confirm arrival
          when you roll in and acknowledge handover after MIST.
        </p>
      </div>
    </div>
  );
}

function Label({ text, children }: any) {
  return <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>{children}</label>;
}
