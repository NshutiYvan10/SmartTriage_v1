/* ═══════════════════════════════════════════════════════════════
   EmsRunForm — multi-step run capture for paramedics.

   Steps:
     1. Patient, incident & destination hospital
     2. Field vitals + TEWS components (mobility / AVPU / trauma)
     3. Field triage — emergency / very-urgent / urgent discriminators,
        computed by the SAME Rwanda/KFH engine the ED uses (not a
        manual pick). Shows the engine's category + TEWS + decision path.
     4. Treatments (chip picker + structured dose/route)
     5. Send to ED — blue-light toggle + ETA + pre-arrival ping

   Glove-friendly: large tap targets, base-size text, high contrast —
   usable on a phone in a moving ambulance. Save-and-continue at every
   step so a partially filled run isn't lost.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import {
  X, ChevronLeft, ChevronRight, Loader2, Send, AlertOctagon,
  Check, Plus, Siren, Activity, Calculator, MapPin, Radio,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { emsApi } from '@/api/ems';
import { iotApi } from '@/api/iot';
import type { DeviceResponse } from '@/api/types';
import type {
  EmsRun, FieldTriageCategory, EmsService, EmsInterventionType,
  FieldTriageRequest, MobilityStatus, AvpuScore, TraumaStatus, DestinationHospital,
} from '@/api/ems';

/** Pediatric threshold mirrors the backend (PEDIATRIC_AGE_CEILING_YEARS). */
const PEDS_AGE_CEILING = 13;

const CATEGORY_STYLE: Record<FieldTriageCategory, { bg: string; ring: string; label: string }> = {
  RED:    { bg: 'bg-rose-500',    ring: 'ring-rose-500/40',    label: 'RED — Immediate' },
  ORANGE: { bg: 'bg-amber-500',   ring: 'ring-amber-500/40',   label: 'ORANGE — Very urgent (<10 min)' },
  YELLOW: { bg: 'bg-yellow-500',  ring: 'ring-yellow-500/40',  label: 'YELLOW — Urgent (<30 min)' },
  GREEN:  { bg: 'bg-emerald-500', ring: 'ring-emerald-500/40', label: 'GREEN — Routine (<1 hr)' },
  BLUE:   { bg: 'bg-blue-500',    ring: 'ring-blue-500/40',    label: 'BLUE — Non-urgent' },
};

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

// Discriminator groups for the field-triage step.
const EMERGENCY_SIGNS: { key: keyof FieldTriageRequest; label: string; peds?: boolean }[] = [
  { key: 'hasAirwayCompromise',        label: 'Airway compromise / not breathing' },
  { key: 'hasSevereRespiratoryDistress', label: 'Severe respiratory distress' },
  { key: 'hasCardiacArrest',           label: 'Cardiac arrest' },
  { key: 'hasUncontrolledHaemorrhage', label: 'Uncontrolled haemorrhage' },
  { key: 'hasStabGunWoundNeckChest',   label: 'Stab / gunshot to neck or chest' },
  { key: 'hasConvulsions',             label: 'Active seizure / post-ictal' },
  { key: 'hasComa',                    label: 'Unresponsive / responds only to pain' },
  { key: 'hasHypoglycaemia',           label: 'Hypoglycaemia (< 3 mmol/L)' },
  { key: 'hasBurnFaceInhalation',      label: 'Burn — face / inhalation' },
  { key: 'childCentralCyanosis',       label: 'Central cyanosis', peds: true },
  { key: 'childPulseLowOrAbsent',      label: 'Pulse low or absent', peds: true },
];
const VERY_URGENT_SIGNS: { key: keyof FieldTriageRequest; label: string }[] = [
  { key: 'vuAlteredMentalStatus',    label: 'Altered mental status (acute)' },
  { key: 'vuFocalNeurologicDeficit', label: 'Focal neuro deficit (acute)' },
  { key: 'vuChestPain',              label: 'Chest pain' },
  { key: 'vuShortnessOfBreath',      label: 'Shortness of breath (acute)' },
  { key: 'vuPoisoningOverdose',      label: 'Poisoning / overdose' },
  { key: 'vuCoughingVomitingBlood',  label: 'Coughing / vomiting blood' },
  { key: 'vuSevereMechanismOfInjury', label: 'Severe mechanism (fall > 1 m, RTA)' },
  { key: 'vuOpenFracture',           label: 'Open fracture' },
  { key: 'vuThreatenedLimb',         label: 'Threatened limb (no pulse / pale)' },
  { key: 'vuVerySeverePain',         label: 'Very severe pain (≥ 7)' },
  { key: 'vuBurnOver20Percent',      label: 'Burn > 20%' },
];
const URGENT_SIGNS: { key: keyof FieldTriageRequest; label: string }[] = [
  { key: 'urgAbdominalPain',       label: 'Abdominal pain' },
  { key: 'urgModeratePain',        label: 'Moderate pain (5–6)' },
  { key: 'urgClosedFracture',      label: 'Closed fracture' },
  { key: 'urgLacerationAbscess',   label: 'Laceration / abscess' },
  { key: 'urgVeryPale',            label: 'Very pale' },
  { key: 'urgUnableToDrinkVomits', label: 'Unable to drink / vomits everything' },
];

const ALL_SIGN_KEYS: string[] =
  [...EMERGENCY_SIGNS, ...VERY_URGENT_SIGNS, ...URGENT_SIGNS].map((s) => s.key as string);

/** Parse the persisted field-triage input JSON (null on absence/parse error). */
function parseFieldInput(run: EmsRun | null): any {
  try { return run?.fieldTriageInput ? JSON.parse(run.fieldTriageInput) : null; }
  catch { return null; }
}
/** Rebuild the discriminator-flag map from a saved input so a re-open is faithful. */
function flagsFromInput(input: any): Record<string, boolean> {
  const out: Record<string, boolean> = {};
  if (input) for (const k of ALL_SIGN_KEYS) if (input[k]) out[k] = true;
  return out;
}

interface Props {
  run: EmsRun | null;        // null = create new
  hospitalId: string;        // paramedic's default destination
  onClose: () => void;
  onSaved: () => void;
}

export function EmsRunForm({ run, hospitalId, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();

  const [step, setStep] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [current, setCurrent] = useState<EmsRun | null>(run);
  const [destinations, setDestinations] = useState<DestinationHospital[]>([]);
  // A failed destinations fetch must NOT masquerade as "only my hospital" — that
  // silently strips reroute. Track the failure so the select can offer a Retry.
  const [destinationsError, setDestinationsError] = useState(false);
  const [loadingDestinations, setLoadingDestinations] = useState(true);

  const [draft, setDraft] = useState({
    destinationHospitalId: run?.hospitalId ?? hospitalId,
    service: (run?.service ?? 'OTHER') as EmsService,
    unitCallsign: run?.unitCallsign ?? '',
    patientAgeYears: run?.patientAgeYears != null ? String(run.patientAgeYears) : '',
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
    etaMinutes: run?.etaMinutes ?? '',
    notes: run?.notes ?? '',
  });

  // Field-triage inputs (engine components + discriminators) — rehydrated
  // from the persisted input so re-opening an en-route run shows the exact
  // prior assessment (prevents a blank-form re-compute silently downgrading).
  const savedInput = parseFieldInput(run);
  const [mobility, setMobility] = useState<MobilityStatus | ''>(savedInput?.mobility ?? '');
  const [avpu, setAvpu] = useState<AvpuScore | ''>(savedInput?.avpu ?? '');
  const [trauma, setTrauma] = useState<TraumaStatus | ''>(savedInput?.traumaStatus ?? '');
  const [isChild, setIsChild] = useState<boolean>(
    run?.fieldTriageIsChild ?? savedInput?.isChild
      ?? (run?.patientAgeYears != null && run.patientAgeYears < PEDS_AGE_CEILING),
  );
  const [reason, setReason] = useState(run?.fieldTriageReason ?? savedInput?.reason ?? '');
  const [flags, setFlags] = useState<Record<string, boolean>>(flagsFromInput(savedInput));
  const [computing, setComputing] = useState(false);
  const [pendingDowngrade, setPendingDowngrade] = useState(false);

  useEffect(() => { setCurrent(run); }, [run]);

  // Destination list (paramedic-accessible).
  const loadDestinations = useCallback(() => {
    setLoadingDestinations(true);
    setDestinationsError(false);
    emsApi.destinations()
      .then((d) => { setDestinations(d || []); })
      .catch(() => { setDestinationsError(true); })
      .finally(() => { setLoadingDestinations(false); });
  }, []);
  useEffect(() => { loadDestinations(); }, [loadDestinations]);

  // Keep the child-form default in step with the entered age (until the
  // paramedic overrides it manually on the triage step).
  useEffect(() => {
    const age = draft.patientAgeYears === '' ? null : Number(draft.patientAgeYears);
    if (age != null && !Number.isNaN(age) && run?.fieldTriageIsChild == null) {
      setIsChild(age < PEDS_AGE_CEILING);
    }
  }, [draft.patientAgeYears, run?.fieldTriageIsChild]);

  const num = (v: any) => {
    if (v === '' || v === null || v === undefined) return undefined;
    const n = Number(v);
    // Guard NaN (e.g. a stray char) — JSON.stringify turns NaN into null, which
    // would silently DROP a vital the crew actually typed (and a dropped SpO2/HR
    // can suppress the RED override). Treat unparseable as "not entered".
    return Number.isNaN(n) ? undefined : n;
  };

  async function saveStep1() {
    if (!current) {
      const created = await emsApi.create({
        hospitalId: draft.destinationHospitalId || hospitalId,
        service: draft.service || undefined,
        unitCallsign: draft.unitCallsign || undefined,
        patientAgeYears: num(draft.patientAgeYears),
        patientSex: draft.patientSex || undefined,
        incidentLocation: draft.incidentLocation || undefined,
        mechanism: draft.mechanism || undefined,
        historySummary: draft.historySummary || undefined,
      });
      setCurrent(created);
      return;
    }
    // Persist the field edits FIRST and unconditionally — a reroute that the
    // backend rejects (e.g. an already-identified, pre-registered patient) must
    // never silently discard the unit/age/sex/location/mechanism/history the
    // crew just typed.
    const updated = await emsApi.update(current.id, {
      unitCallsign: draft.unitCallsign || undefined,
      patientAgeYears: num(draft.patientAgeYears),
      patientSex: draft.patientSex || undefined,
      incidentLocation: draft.incidentLocation || undefined,
      mechanism: draft.mechanism || undefined,
      historySummary: draft.historySummary || undefined,
    });
    setCurrent(updated);

    // Destination changed on an existing run → attempt reroute in its OWN guard.
    // On failure, revert the destination select to the true server state (the
    // field edits above are already saved) and surface why, instead of leaving
    // the UI showing a destination the server never accepted.
    if (draft.destinationHospitalId && draft.destinationHospitalId !== updated.hospitalId) {
      try {
        const rerouted = await emsApi.reroute(current.id, { hospitalId: draft.destinationHospitalId });
        setCurrent(rerouted);
      } catch (e: any) {
        setDraft((d) => ({ ...d, destinationHospitalId: updated.hospitalId }));
        throw new Error(
          (e?.message || 'This run could not be rerouted to the selected hospital.')
          + ' Destination reverted — your other changes were saved.',
        );
      }
    }
  }

  async function saveStep2() {
    if (!current) return;
    const updated = await emsApi.update(current.id, {
      injuriesObserved: draft.injuriesObserved || undefined,
      fieldGcs: num(draft.fieldGcs),
      fieldRespRate: num(draft.fieldRespRate),
      fieldHr: num(draft.fieldHr),
      fieldSbp: num(draft.fieldSbp),
      fieldDbp: num(draft.fieldDbp),
      fieldSpo2: num(draft.fieldSpo2),
      fieldTemp: num(draft.fieldTemp),
      fieldGlucose: num(draft.fieldGlucose),
      notes: draft.notes || undefined,
    });
    setCurrent(updated);
  }

  async function next() {
    setSubmitting(true);
    setError(null);
    try {
      if (step === 1) await saveStep1();
      if (step === 2) await saveStep2();
      setStep((s) => Math.min(5, s + 1));
    } catch (err: any) {
      setError(err?.message || 'Failed to save');
    } finally {
      setSubmitting(false);
    }
  }

  function prev() {
    setError(null);
    setStep((s) => Math.max(1, s - 1));
  }

  /** Step 3 — run the shared triage engine. `ack` re-confirms a downgrade. */
  async function computeTriage(ack = false) {
    if (!current) return;
    // Min-data guard — without any assessment input the engine returns an
    // all-null GREEN, which would be persisted and shown to the ED as the field
    // call. Require at least one vital, discriminator, or AVPU/mobility/trauma.
    const hasVital = [
      draft.fieldRespRate, draft.fieldHr, draft.fieldSbp, draft.fieldDbp,
      draft.fieldSpo2, draft.fieldTemp, draft.fieldGlucose, draft.fieldGcs,
    ].some((v) => v !== '' && v !== null && v !== undefined);
    const hasDiscriminator = Object.values(flags).some(Boolean) || !!avpu || !!mobility || !!trauma;
    if (!hasVital && !hasDiscriminator) {
      setError('No assessment data entered — record at least one vital, a discriminator, '
        + 'or an AVPU / mobility before computing field triage.');
      return;
    }
    setComputing(true);
    setError(null);
    try {
      const body: FieldTriageRequest = {
        respiratoryRate: num(draft.fieldRespRate),
        heartRate: num(draft.fieldHr),
        systolicBp: num(draft.fieldSbp),
        diastolicBp: num(draft.fieldDbp),
        spo2: num(draft.fieldSpo2),
        temperature: num(draft.fieldTemp),
        bloodGlucose: num(draft.fieldGlucose),
        gcs: num(draft.fieldGcs),
        mobility: mobility || undefined,
        avpu: avpu || undefined,
        traumaStatus: trauma || undefined,
        isChild,
        reason: reason || undefined,
        acknowledgeDowngrade: ack,
        ...flags,
      };
      const updated = await emsApi.fieldTriage(current.id, body);
      setCurrent(updated);
      setPendingDowngrade(false);
    } catch (err: any) {
      const msg = err?.message || 'Failed to compute triage';
      // Backend guard: re-compute would LOWER acuity — require explicit confirm.
      if (/lower acuity|re-confirm to record a downgrade/i.test(msg)) {
        setPendingDowngrade(true);
      } else {
        setError(msg);
      }
    } finally {
      setComputing(false);
    }
  }

  async function sendToEd() {
    if (!current) return;
    setSubmitting(true);
    setError(null);
    try {
      // Persist the handover notes (and any late ETA edit) before the ping so
      // the receiving team sees them.
      await emsApi.update(current.id, {
        notes: draft.notes || undefined,
        etaMinutes: num(draft.etaMinutes),
      });
      await emsApi.preregister(current.id, { etaMinutes: num(draft.etaMinutes) as number | undefined });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to send pre-arrival');
    } finally {
      setSubmitting(false);
    }
  }

  async function toggleLights() {
    if (!current) return;
    try {
      const updated = await emsApi.setLights(current.id, !current.lightsActive);
      setCurrent(updated);
    } catch (err: any) {
      setError(err?.message || 'Failed to toggle lights');
    }
  }

  // Closing the modal (X) must not silently discard Step-5 ETA + handover notes
  // — those were only persisted inside sendToEd(). Best-effort save them first.
  async function handleClose() {
    if (current && ((draft.notes && draft.notes.trim()) || (draft.etaMinutes !== '' && draft.etaMinutes != null))) {
      try {
        await emsApi.update(current.id, { notes: draft.notes || undefined, etaMinutes: num(draft.etaMinutes) });
      } catch { /* best-effort — close regardless */ }
    }
    onClose();
  }

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
      <div className="rounded-2xl p-4 sm:p-6 max-w-2xl w-full max-h-[85vh] overflow-y-auto shadow-2xl animate-scale-in" style={glassCard}>
        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-xl bg-rose-500/15 flex items-center justify-center">
              <Siren className="w-6 h-6 text-rose-500" />
            </div>
            <div>
              <h3 className={`text-lg font-bold ${text.heading}`}>
                {current ? `Run ${current.id.slice(0, 8)}` : 'New ambulance run'}
              </h3>
              <p className={`text-sm ${text.muted}`}>Step {step} of 5</p>
            </div>
          </div>
          <button onClick={handleClose} className={`w-10 h-10 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-1.5 mb-5">
          {[1, 2, 3, 4, 5].map((n) => (
            <div key={n} className={`h-2 flex-1 rounded-full ${n <= step ? 'bg-rose-500' : isDark ? 'bg-white/10' : 'bg-slate-200'}`} />
          ))}
        </div>

        {step === 1 && (
          <Step1Patient draft={draft} setDraft={setDraft} destinations={destinations} destinationsError={destinationsError} loadingDestinations={loadingDestinations} reloadDestinations={loadDestinations} text={text} glassInner={glassInner} isDark={isDark} />
        )}
        {step === 2 && (
          <Step2Vitals draft={draft} setDraft={setDraft} mobility={mobility} setMobility={setMobility}
            avpu={avpu} setAvpu={setAvpu} trauma={trauma} setTrauma={setTrauma} text={text} glassInner={glassInner} isDark={isDark} />
        )}
        {step === 3 && (
          <Step3Triage
            run={current} flags={flags} setFlags={setFlags} isChild={isChild} setIsChild={setIsChild}
            reason={reason} setReason={setReason} computing={computing}
            onCompute={() => computeTriage(false)}
            pendingDowngrade={pendingDowngrade} onConfirmDowngrade={() => computeTriage(true)}
            text={text} glassInner={glassInner} isDark={isDark} />
        )}
        {step === 4 && current && (
          <Step4Treatments run={current} text={text} glassInner={glassInner} isDark={isDark} onChanged={() => emsApi.getById(current.id).then(setCurrent)} />
        )}
        {step === 5 && (
          <Step5Send draft={draft} setDraft={setDraft} text={text} glassInner={glassInner} isDark={isDark} run={current} onToggleLights={toggleLights} />
        )}

        {error && (
          <div className="rounded-xl px-3 py-2.5 my-3 text-sm font-semibold bg-rose-500/10 text-rose-500">
            {error}
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-between gap-2 mt-5 pt-4 border-t border-slate-500/10">
          <button onClick={prev} disabled={step === 1 || submitting}
            className={`inline-flex items-center gap-1 px-4 py-3 rounded-xl text-sm font-bold ${text.muted} hover:bg-white/5 disabled:opacity-30`}>
            <ChevronLeft className="w-4 h-4" /> Back
          </button>
          {step < 5 ? (
            <button onClick={next} disabled={submitting}
              className="inline-flex items-center gap-2 px-6 py-3 rounded-xl text-sm font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50">
              {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Check className="w-4 h-4" />}
              Save &amp; continue <ChevronRight className="w-4 h-4" />
            </button>
          ) : (
            <button onClick={sendToEd} disabled={submitting || !current}
              className="inline-flex items-center gap-2 px-6 py-3 rounded-xl text-sm font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50">
              {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
              Send pre-arrival to ED
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 1 — patient, incident & destination
// ─────────────────────────────────────────────────────────────────

function Step1Patient({ draft, setDraft, destinations, destinationsError, loadingDestinations, reloadDestinations, text, glassInner, isDark }: any) {
  const inputClass = `w-full px-3 py-3 rounded-xl text-base focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  return (
    <div className="space-y-4">
      <h4 className={`text-base font-bold ${text.heading}`}>Patient, incident &amp; destination</h4>

      <div>
        <Label text={text}><MapPin className="w-3.5 h-3.5 inline mr-1" /> Destination hospital</Label>
        <select value={draft.destinationHospitalId} onChange={(e) => setDraft({ ...draft, destinationHospitalId: e.target.value })} className={inputClass} style={glassInner}>
          {destinations.length === 0 && (
            <option value={draft.destinationHospitalId}>
              {loadingDestinations ? 'Loading hospitals…' : destinationsError ? 'My hospital (list unavailable)' : 'My hospital'}
            </option>
          )}
          {destinations.map((h: DestinationHospital) => (
            <option key={h.id} value={h.id}>{h.name ?? h.hospitalCode ?? h.id.slice(0, 8)}{h.city ? ` — ${h.city}` : ''}</option>
          ))}
        </select>
        {/* A failed fetch must not silently disable reroute — say so + offer Retry. */}
        {destinationsError && (
          <div className="mt-1.5 flex items-center gap-2 text-[11px] text-amber-600">
            <span className="flex-1">Could not load the hospital list — reroute is limited to your hospital until this loads.</span>
            <button
              type="button"
              onClick={() => reloadDestinations?.()}
              className="px-2.5 py-1 rounded-lg bg-amber-500 text-white font-bold hover:bg-amber-600"
            >
              Retry
            </button>
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label text={text}>Age (years)</Label>
          <input type="number" inputMode="numeric" value={draft.patientAgeYears} onChange={(e) => setDraft({ ...draft, patientAgeYears: e.target.value })} className={inputClass} style={glassInner} />
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
// Step 2 — vitals + TEWS components
// ─────────────────────────────────────────────────────────────────

function Step2Vitals({ draft, setDraft, mobility, setMobility, avpu, setAvpu, trauma, setTrauma, text, glassInner, isDark }: any) {
  const inputClass = `w-full px-3 py-3 rounded-xl text-base focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  const numField = (key: string, label: string, ph?: string) => (
    <div>
      <Label text={text}>{label}</Label>
      <input type="number" inputMode="decimal" step="any" value={(draft as any)[key]} placeholder={ph}
        onChange={(e) => setDraft((d: any) => ({ ...d, [key]: e.target.value }))} className={inputClass} style={glassInner} />
    </div>
  );
  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <h4 className={`text-base font-bold ${text.heading}`}>Field vitals</h4>
        <PullFromMonitor setDraft={setDraft} text={text} glassInner={glassInner} isDark={isDark} />
      </div>
      <p className={`text-sm ${text.muted}`}>Snapshot at scene. These drive the computed TEWS on the next step.</p>
      <div className="grid grid-cols-2 gap-3">
        {numField('fieldRespRate', 'Resp rate', '16')}
        {numField('fieldHr', 'Heart rate', '80')}
        {numField('fieldSbp', 'SBP', '120')}
        {numField('fieldDbp', 'DBP', '80')}
        {numField('fieldSpo2', 'SpO₂ %', '98')}
        {numField('fieldTemp', 'Temp °C', '37.0')}
        {numField('fieldGcs', 'GCS (3–15)', '15')}
        {numField('fieldGlucose', 'Glucose mmol/L', '5.5')}
      </div>

      <h4 className={`text-base font-bold ${text.heading} pt-1`}>TEWS components</h4>
      <div className="grid grid-cols-1 gap-3">
        <div>
          <Label text={text}>Mobility</Label>
          <select value={mobility} onChange={(e) => setMobility(e.target.value)} className={inputClass} style={glassInner}>
            <option value="">— select —</option>
            <option value="WALKING">Walking</option>
            <option value="WITH_HELP">With help / wheelchair</option>
            <option value="STRETCHER">Stretcher / immobile</option>
          </select>
        </div>
        <div>
          <Label text={text}>AVPU (consciousness)</Label>
          <select value={avpu} onChange={(e) => setAvpu(e.target.value)} className={inputClass} style={glassInner}>
            <option value="">— select —</option>
            <option value="ALERT">Alert</option>
            <option value="CONFUSED">Confused</option>
            <option value="VERBAL">Reacts to voice</option>
            <option value="PAIN">Reacts to pain</option>
            <option value="UNRESPONSIVE">Unresponsive</option>
          </select>
        </div>
        <div>
          <Label text={text}>Trauma</Label>
          <select value={trauma} onChange={(e) => setTrauma(e.target.value)} className={inputClass} style={glassInner}>
            <option value="">— select —</option>
            <option value="NO_TRAUMA">No trauma</option>
            <option value="TRAUMA">Trauma</option>
          </select>
        </div>
      </div>

      <div>
        <Label text={text}>Injuries observed</Label>
        <textarea rows={2} value={draft.injuriesObserved} onChange={(e) => setDraft({ ...draft, injuriesObserved: e.target.value })} placeholder="Open femur fracture, head laceration, etc." className={`${inputClass} resize-none`} style={glassInner} />
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Pull-from-monitor — one-shot snapshot from the paramedic's OWN
// registered field monitor into the Step-2 vitals. Filled fields stay
// EDITABLE (field readings in a moving ambulance are noisy — the crew
// must be able to correct them). Manual entry is always available.
// ─────────────────────────────────────────────────────────────────

function PullFromMonitor({ setDraft, text, glassInner, isDark }: any) {
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [pickedId, setPickedId] = useState<string>('');
  const [loadingDevices, setLoadingDevices] = useState(true);
  const [pulling, setPulling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // "source: monitor" indicator (mirrors the triage form's per-pull badge).
  const [pulledFrom, setPulledFrom] = useState<{ deviceName: string; ageSeconds: number | null; hasReading: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    iotApi.myDevices()
      .then((all) => {
        if (cancelled) return;
        const monitors = (all || []).filter((d) => d.deviceType === 'PARAMEDIC_MONITOR');
        setDevices(monitors);
        if (monitors.length === 1) setPickedId(monitors[0].id);
      })
      .catch(() => { /* silent — feature degrades to manual entry */ })
      .finally(() => { if (!cancelled) setLoadingDevices(false); });
    return () => { cancelled = true; };
  }, []);

  const pull = async (deviceId: string) => {
    if (!deviceId) return;
    setPulling(true);
    setError(null);
    try {
      const snap = await iotApi.latestVitals(deviceId);
      if (!snap.hasReading) {
        setPulledFrom({ deviceName: snap.deviceName, ageSeconds: snap.ageSeconds, hasReading: false });
        return;
      }
      // Fill only the vitals the monitor actually reported. Leave everything
      // else — and every filled field stays editable (no lock).
      setDraft((d: any) => ({
        ...d,
        ...(snap.heartRate != null       ? { fieldHr: String(snap.heartRate) } : {}),
        ...(snap.respiratoryRate != null ? { fieldRespRate: String(snap.respiratoryRate) } : {}),
        ...(snap.systolicBp != null      ? { fieldSbp: String(snap.systolicBp) } : {}),
        ...(snap.diastolicBp != null     ? { fieldDbp: String(snap.diastolicBp) } : {}),
        ...(snap.spo2 != null            ? { fieldSpo2: String(snap.spo2) } : {}),
        ...(snap.temperature != null     ? { fieldTemp: String(snap.temperature) } : {}),
        ...(snap.glucose != null         ? { fieldGlucose: String(snap.glucose) } : {}),
      }));
      setPulledFrom({ deviceName: snap.deviceName, ageSeconds: snap.ageSeconds, hasReading: true });
    } catch (e: any) {
      setError(e?.message || 'Could not pull from your monitor.');
    } finally {
      setPulling(false);
    }
  };

  // No monitor registered → subtle hint, no button.
  if (!loadingDevices && devices.length === 0) {
    return (
      <span className={`text-xs ${text.muted}`}>Register your monitor on the dashboard to pull vitals</span>
    );
  }

  const btnClass = `inline-flex items-center gap-1.5 px-3 py-2 rounded-xl text-sm font-bold disabled:opacity-50 ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`;

  return (
    <div className="flex flex-col items-end gap-1.5">
      <div className="flex items-center gap-2">
        {devices.length > 1 && (
          <select value={pickedId} onChange={(e) => setPickedId(e.target.value)}
            className={`px-2.5 py-2 rounded-xl text-sm max-w-[10rem] focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white' : 'text-slate-800'}`}
            style={glassInner}>
            <option value="">— pick monitor —</option>
            {devices.map((d) => <option key={d.id} value={d.id}>{d.deviceName}</option>)}
          </select>
        )}
        <button type="button" onClick={() => pull(pickedId)} disabled={pulling || loadingDevices || !pickedId} className={btnClass}>
          {pulling ? <Loader2 className="w-4 h-4 animate-spin" /> : <Radio className="w-4 h-4" />} Pull from my monitor
        </button>
      </div>
      {error && <span className="text-xs font-semibold text-rose-500">{error}</span>}
      {pulledFrom && !pulledFrom.hasReading && (
        <span className="text-xs font-semibold text-amber-600">No recent reading from {pulledFrom.deviceName}</span>
      )}
      {pulledFrom && pulledFrom.hasReading && (
        <span className={`text-xs ${pulledFrom.ageSeconds != null && pulledFrom.ageSeconds > 120 ? 'font-semibold text-amber-600' : text.muted}`}>
          Pulled from {pulledFrom.deviceName}{pulledFrom.ageSeconds != null ? ` · ${formatAge(pulledFrom.ageSeconds)}` : ''}
          {pulledFrom.ageSeconds != null && pulledFrom.ageSeconds > 120 ? ' — verify, reading may be stale' : ''}
          {' · editable'}
        </span>
      )}
    </div>
  );
}

/** Compact "12s ago" / "3m ago" for a snapshot's ageSeconds. */
function formatAge(sec: number): string {
  if (sec < 60) return `${Math.round(sec)}s ago`;
  if (sec < 3600) return `${Math.round(sec / 60)}m ago`;
  return `${Math.round(sec / 3600)}h ago`;
}

// ─────────────────────────────────────────────────────────────────
// Step 3 — engine-computed field triage
// ─────────────────────────────────────────────────────────────────

function Step3Triage({ run, flags, setFlags, isChild, setIsChild, reason, setReason, computing, onCompute, pendingDowngrade, onConfirmDowngrade, text, glassInner, isDark }: any) {
  const inputClass = `w-full px-3 py-3 rounded-xl text-base focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  const toggle = (k: string) => setFlags({ ...flags, [k]: !flags[k] });
  const emergency = EMERGENCY_SIGNS.filter((s) => !s.peds || isChild);

  const computed: FieldTriageCategory | null = run?.fieldTriageCategory ?? null;
  const style = computed ? CATEGORY_STYLE[computed] : null;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <h4 className={`text-base font-bold ${text.heading}`}>Field triage</h4>
        <label className={`flex items-center gap-2 text-sm font-semibold ${text.body} cursor-pointer`}>
          <input type="checkbox" checked={isChild} onChange={() => {
            const next = !isChild;
            setIsChild(next);
            // Turning Pediatric OFF must drop any peds-only signs already ticked,
            // otherwise a stale `true` is hidden from view but still persisted —
            // a contradictory record (peds sign on a non-pediatric assessment).
            if (!next) {
              setFlags((f: Record<string, boolean>) => {
                const cleaned = { ...f };
                for (const s of EMERGENCY_SIGNS) if (s.peds) delete cleaned[s.key as string];
                return cleaned;
              });
            }
          }} className="w-5 h-5 accent-rose-500" />
          Pediatric (KFH form, &lt; 13y)
        </label>
      </div>
      <p className={`text-sm ${text.muted}`}>
        Tick what's present, then compute. The same Rwanda{isChild ? '/KFH peds' : ''} engine the ED uses returns the
        category &amp; TEWS — your call drives bay prep. The ED re-triages on arrival.
      </p>

      <SignGroup title="Emergency signs (→ RED)" tone="rose" items={emergency} flags={flags} toggle={toggle} text={text} glassInner={glassInner} />
      <SignGroup title="Very urgent signs" tone="amber" items={VERY_URGENT_SIGNS} flags={flags} toggle={toggle} text={text} glassInner={glassInner} />
      <SignGroup title="Urgent signs" tone="yellow" items={URGENT_SIGNS} flags={flags} toggle={toggle} text={text} glassInner={glassInner} />

      <div>
        <Label text={text}>Reason / one-line rationale</Label>
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="GCS 9, hypotensive, suspected internal bleeding" className={inputClass} style={glassInner} />
      </div>

      <button onClick={onCompute} disabled={computing}
        className="w-full inline-flex items-center justify-center gap-2 px-4 py-3.5 rounded-xl text-base font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50">
        {computing ? <Loader2 className="w-5 h-5 animate-spin" /> : <Calculator className="w-5 h-5" />}
        Compute field triage
      </button>

      {pendingDowngrade && (
        <div className="rounded-2xl p-4 ring-2 ring-amber-500/50 bg-amber-500/10">
          <div className="flex items-start gap-2">
            <AlertOctagon className="w-5 h-5 text-amber-500 mt-0.5 shrink-0" />
            <div className="space-y-2">
              <p className={`text-sm font-bold ${text.heading}`}>
                This re-computes to a LOWER acuity than the current {run?.fieldTriageCategory}.
              </p>
              <p className={`text-sm ${text.muted}`}>
                The ED relies on the field call. Only confirm if the patient genuinely improved and you have
                re-assessed every sign. Otherwise go back and re-check what's ticked.
              </p>
              <button onClick={onConfirmDowngrade} disabled={computing}
                className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-bold text-white bg-amber-600 hover:bg-amber-700 disabled:opacity-50">
                {computing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Check className="w-4 h-4" />}
                Confirm downgrade to lower acuity
              </button>
            </div>
          </div>
        </div>
      )}

      {style && (
        <div className={`rounded-2xl p-4 ring-2 ${style.ring}`} style={glassInner}>
          <div className="flex items-center gap-3">
            <span className={`w-4 h-4 rounded-full ${style.bg}`} />
            <span className={`text-lg font-extrabold ${text.heading}`}>{style.label}</span>
            {run?.fieldTewsScore != null && (
              <span className="ml-auto inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-slate-600"
                style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                TEWS {run.fieldTewsScore}
              </span>
            )}
          </div>
          {run?.fieldTriageDecisionPath && (
            <p className={`text-sm mt-2 ${text.muted}`}>
              <Activity className="w-3.5 h-3.5 inline mr-1" />{run.fieldTriageDecisionPath}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function SignGroup({ title, tone, items, flags, toggle, text, glassInner }: any) {
  const dot = tone === 'rose' ? 'bg-rose-500' : tone === 'amber' ? 'bg-amber-500' : 'bg-yellow-500';
  return (
    <div>
      <div className={`flex items-center gap-2 mb-1.5 text-sm font-bold ${text.heading}`}>
        <span className={`w-2.5 h-2.5 rounded-full ${dot}`} /> {title}
      </div>
      <div className="grid grid-cols-1 gap-1.5">
        {items.map((s: any) => (
          <label key={s.key} className={`flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer ${flags[s.key] ? 'ring-2 ring-rose-500/30' : ''}`} style={glassInner}>
            <input type="checkbox" checked={!!flags[s.key]} onChange={() => toggle(s.key)} className="w-5 h-5 accent-rose-500" />
            <span className={`text-sm font-medium ${text.body}`}>{s.label}</span>
          </label>
        ))}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 4 — treatments
// ─────────────────────────────────────────────────────────────────

function Step4Treatments({ run, text, glassInner, isDark, onChanged }: any) {
  const [staged, setStaged] = useState<InterventionPreset & { givenByName?: string; outcome?: string; notes?: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputClass = `w-full px-3 py-2.5 rounded-xl text-base focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;

  async function save() {
    if (!staged || !staged.detail.trim()) { setError('Description is required'); return; }
    setBusy(true);
    setError(null);
    try {
      await emsApi.addIntervention(run.id, {
        type: staged.type, detail: staged.detail, dose: staged.dose || undefined,
        route: staged.route || undefined, givenByName: staged.givenByName || undefined,
        outcome: staged.outcome || undefined, notes: staged.notes || undefined,
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
      <h4 className={`text-base font-bold ${text.heading}`}>Treatments given</h4>
      <p className={`text-sm ${text.muted}`}>Tap a preset, then confirm dose / route / given-by before saving.</p>

      {!staged && (
        <div className="flex flex-wrap gap-2">
          {COMMON_INTERVENTIONS.map((it, i) => (
            <button key={i} onClick={() => { setError(null); setStaged({ ...it }); }} disabled={busy}
              className={`inline-flex items-center gap-1 px-3 py-2 rounded-lg text-sm font-semibold ${isDark ? 'bg-white/5 hover:bg-white/10 text-white/80' : 'bg-slate-100 hover:bg-slate-200 text-slate-700'} disabled:opacity-50`}>
              <Plus className="w-3.5 h-3.5" /> {it.detail}{it.dose && <span className="opacity-60">— {it.dose}</span>}
            </button>
          ))}
          <button onClick={() => { setError(null); setStaged({ type: 'OTHER', detail: '' }); }}
            className={`inline-flex items-center gap-1 px-3 py-2 rounded-lg text-sm font-semibold border-2 border-dashed ${isDark ? 'border-white/20 text-white/70 hover:bg-white/5' : 'border-slate-300 text-slate-600 hover:bg-slate-50'}`}>
            <Plus className="w-3.5 h-3.5" /> Custom
          </button>
        </div>
      )}

      {staged && (
        <div className="rounded-xl p-3 space-y-2 ring-1 ring-rose-500/20" style={glassInner}>
          <div className="flex items-center justify-between">
            <div className={`text-sm uppercase font-bold ${text.label}`}>Confirm details</div>
            <button onClick={() => setStaged(null)} className={`text-sm ${text.muted} hover:underline`}>Cancel</button>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <Label text={text}>Type</Label>
              <select value={staged.type} onChange={(e) => setStaged({ ...staged, type: e.target.value as EmsInterventionType })} className={inputClass} style={glassInner}>
                {(['OXYGEN','IV_ACCESS','FLUID','MEDICATION','DEFIBRILLATION','AIRWAY','IMMOBILISATION','SPLINTING','TOURNIQUET','CPR','OTHER'] as EmsInterventionType[]).map(t => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </div>
            <div>
              <Label text={text}>Description <span className="text-rose-500">*</span></Label>
              <input value={staged.detail} onChange={(e) => setStaged({ ...staged, detail: e.target.value })} placeholder="Adrenaline / O₂ NRB / IV cannula" className={inputClass} style={glassInner} autoFocus />
            </div>
            <div>
              <Label text={text}>Dose</Label>
              <input value={staged.dose ?? ''} onChange={(e) => setStaged({ ...staged, dose: e.target.value })} placeholder="1 mg / 500 ml / 6 L/min" className={inputClass} style={glassInner} />
            </div>
            <div>
              <Label text={text}>Route</Label>
              <select value={staged.route ?? ''} onChange={(e) => setStaged({ ...staged, route: e.target.value })} className={inputClass} style={glassInner}>
                <option value=""></option>
                {ROUTE_OPTIONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div>
              <Label text={text}>Given by</Label>
              <input value={staged.givenByName ?? ''} onChange={(e) => setStaged({ ...staged, givenByName: e.target.value })} placeholder="Crew member" className={inputClass} style={glassInner} />
            </div>
            <div>
              <Label text={text}>Outcome</Label>
              <input value={staged.outcome ?? ''} onChange={(e) => setStaged({ ...staged, outcome: e.target.value })} placeholder="ROSC at 14:08 / tolerated" className={inputClass} style={glassInner} />
            </div>
          </div>
          <button onClick={save} disabled={busy || !staged.detail.trim()}
            className="w-full inline-flex items-center justify-center gap-2 px-3 py-3 rounded-xl bg-cyan-600 text-white text-sm font-bold hover:bg-cyan-700 disabled:opacity-50">
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />} Save intervention
          </button>
        </div>
      )}

      {run.interventions && run.interventions.length > 0 && (
        <div className="space-y-1.5">
          <div className={`text-sm uppercase font-bold ${text.label}`}>Logged ({run.interventions.length})</div>
          {run.interventions.map((iv: any) => (
            <div key={iv.id} className="rounded-xl px-3 py-2 text-sm" style={glassInner}>
              <div className="flex items-center justify-between gap-2">
                <span className={text.body}>
                  <span className={`text-xs font-bold mr-1 ${text.label}`}>{iv.type}</span>
                  {iv.detail || ''}
                  {iv.dose && <span className={`ml-1 ${text.muted}`}>• {iv.dose}</span>}
                  {iv.route && <span className={`ml-1 ${text.muted}`}>• {iv.route}</span>}
                </span>
                <span className={`text-xs ${text.muted} shrink-0`}>
                  {new Date(iv.givenAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
              </div>
            </div>
          ))}
        </div>
      )}

      {error && <div className="rounded-xl px-3 py-2 text-sm font-semibold bg-rose-500/10 text-rose-500">{error}</div>}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Step 5 — lights + send pre-arrival
// ─────────────────────────────────────────────────────────────────

function Step5Send({ draft, setDraft, text, glassInner, isDark, run, onToggleLights }: any) {
  const inputClass = `w-full px-3 py-3 rounded-xl text-base focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;
  const lights = !!run?.lightsActive;
  return (
    <div className="space-y-4">
      <h4 className={`text-base font-bold ${text.heading}`}>Send pre-arrival to ED</h4>
      <p className={`text-sm ${text.muted}`}>
        The receiving hospital's charge nurse is alerted in real time with your field triage, vitals and treatments.
        Bay prep starts the moment you send.
      </p>

      {/* Lights toggle */}
      <button onClick={onToggleLights}
        className={`w-full flex items-center justify-between gap-3 px-4 py-3.5 rounded-xl border-2 transition-colors ${
          lights ? 'bg-rose-500/15 border-rose-500/50' : isDark ? 'border-white/10 hover:border-white/20' : 'border-slate-200 hover:border-slate-300'}`}>
        <span className="flex items-center gap-2">
          <Siren className={`w-5 h-5 ${lights ? 'text-rose-500 animate-pulse' : text.muted}`} />
          <span className={`text-base font-bold ${lights ? 'text-rose-500' : text.heading}`}>
            {lights ? 'Lights ON — priority transport' : 'Activate lights (priority transport)'}
          </span>
        </span>
        <span className={`text-sm font-bold px-3 py-1 rounded-full ${lights ? 'bg-rose-500 text-white' : isDark ? 'bg-white/10 text-white/60' : 'bg-slate-200 text-slate-500'}`}>
          {lights ? 'ON' : 'OFF'}
        </span>
      </button>

      <div className="rounded-xl p-3" style={glassInner}>
        <div className={`text-sm uppercase font-bold mb-2 ${text.label}`}>Summary</div>
        <div className={`text-sm ${text.body} space-y-1`}>
          <div><b>Patient:</b> {run?.patientAgeYears ?? '—'}y {run?.patientSex ?? ''}</div>
          <div><b>Mechanism:</b> {run?.mechanism ?? '—'}</div>
          <div><b>Field triage:</b> {run?.fieldTriageCategory ?? '— (compute on step 3)'} {run?.fieldTewsScore != null ? `· TEWS ${run.fieldTewsScore}` : ''}</div>
          <div><b>Vitals:</b> GCS {run?.fieldGcs ?? '—'} • HR {run?.fieldHr ?? '—'} • BP {run?.fieldSbp ?? '—'}/{run?.fieldDbp ?? '—'} • SpO₂ {run?.fieldSpo2 ?? '—'}%</div>
          <div><b>Interventions:</b> {run?.interventions?.length ?? 0}</div>
        </div>
      </div>

      <div>
        <Label text={text}>ETA (minutes)</Label>
        <input type="number" inputMode="numeric" value={draft.etaMinutes} onChange={(e) => setDraft({ ...draft, etaMinutes: e.target.value })} placeholder="8" className={inputClass} style={glassInner} />
      </div>

      <div>
        <Label text={text}>Handover notes for ED (MIST / SBAR)</Label>
        <textarea rows={3} value={draft.notes} onChange={(e) => setDraft({ ...draft, notes: e.target.value })}
          placeholder="Free-text context for the receiving team: pre-arrival situation, response to treatment, family/next-of-kin, anything the MIST fields don't capture."
          className={`${inputClass} resize-none`} style={glassInner} />
        <p className={`text-xs mt-1 ${text.muted}`}>Sent with the pre-arrival and shown to the charge nurse + at handover.</p>
      </div>

      <div className="rounded-xl p-3 bg-amber-500/10 ring-1 ring-amber-500/20 flex items-start gap-2">
        <AlertOctagon className="w-5 h-5 text-amber-500 mt-0.5 shrink-0" />
        <p className={`text-sm ${text.body}`}>
          For RED / cardiac-arrest cases — or when your blue lights are on — the hospital's charge nurse and Resus
          zone get a top-priority CRITICAL alert the moment you're flagged: a full-screen flash and an audible
          alarm on their station. The ED nurse confirms your arrival when you roll in and acknowledges handover
          after your MIST.
        </p>
      </div>
    </div>
  );
}

function Label({ text, children }: any) {
  return <label className={`text-sm font-bold uppercase tracking-wide mb-1 block ${text.label}`}>{children}</label>;
}
