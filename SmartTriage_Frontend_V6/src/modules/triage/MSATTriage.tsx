import { useState, useMemo, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AlertCircle, ArrowRight, ArrowLeft, CheckCircle, Shield } from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { useAuditStore } from '@/store/auditStore';
import { useTEWSHistoryStore } from '@/store/tewsHistoryStore';
import { useTheme } from '@/hooks/useTheme';
import { EmergencySigns, TEWSInput, AVPU, Mobility, TriageCategory } from '@/types';
import { Stepper } from '@/components/ui/Stepper';
import { ScoreDisplay } from '@/components/ui/ScoreDisplay';
import { Badge } from '@/components/ui/Badge';
import { useTEWSCalculator } from '@/hooks/useTEWSCalculator';
import { EMERGENCY_SIGNS_CHECKLIST, hasEmergencySigns } from '@/utils/emergencySigns';
import {
  VERY_URGENT_DISCRIMINATORS,
  URGENT_DISCRIMINATORS,
  PEDIATRIC_VERY_URGENT_DISCRIMINATORS,
  PEDIATRIC_URGENT_DISCRIMINATORS,
  hasCheckedDiscriminators,
  getCheckedDiscriminatorLabels,
  isDiscriminatorRequired,
} from '@/utils/discriminators';
import {
  validateTEWSInputs,
  getAbnormalValidations,
  hasImpossibleValues,
  getValidationBgColor,
} from '@/utils/vitalValidation';
import { getScoreBreakdownText } from '@/utils/tewsTrend';

export function MSATTriage() {
  const { patientId } = useParams<{ patientId: string }>();
  const navigate = useNavigate();
  const { glassCard, isDark } = useTheme();
  const patient = usePatientStore((state) => state.getPatient(patientId!));
  const setEmergencySigns = usePatientStore((state) => state.setEmergencySigns);
  const setTEWSInput = usePatientStore((state) => state.setTEWSInput);
  const assignCategory = usePatientStore((state) => state.assignCategory);
  const setTriageStatus = usePatientStore((state) => state.setTriageStatus);
  const addAuditEntry = useAuditStore((state) => state.addEntry);
  const addTEWSHistoryEntry = useTEWSHistoryStore((state) => state.addEntry);
  const tewsTrend = useTEWSHistoryStore((state) => patientId ? state.getTrend(patientId) : null);
  const tewsHistoryCount = useTEWSHistoryStore((state) => patientId ? state.getHistory(patientId).length : 0);

  const [currentStep, setCurrentStep] = useState(0);
  const [triageStartedAt] = useState(() => new Date());
  const [emergencySigns, setEmergencySignsLocal] = useState<EmergencySigns>({
    airwayCompromise: false,
    coma: false,
    severeRespiratoryDistress: false,
    severeBurns: false,
    shockSigns: false,
    convulsions: false,
    hypoglycemia: false,
  });

  const [tewsInput, setTEWSInputLocal] = useState<TEWSInput>({
    mobility: 'AMBULATORY',
    temperature: 37,
    respiratoryRate: 16,
    avpu: 'A',
    pulse: 75,
    trauma: false,
    systolicBP: 120,
    spo2: 98,
  });

  // Discriminator state (mSAT Step 3)
  const [checkedVeryUrgent, setCheckedVeryUrgent] = useState<Record<string, boolean>>({});
  const [checkedUrgent, setCheckedUrgent] = useState<Record<string, boolean>>({});
  const [discriminatorReviewed, setDiscriminatorReviewed] = useState(false);

  const { scoring, category, riskLevel, isValid, validationResults, abnormalValidations, hasImpossible } = useTEWSCalculator({
    input: tewsInput,
    isPediatric: patient?.isPediatric,
    age: patient?.age,
    patientId,
  });

  // Vital validation for current TEWS input
  const vitalWarnings = useMemo(() => getAbnormalValidations(
    validateTEWSInputs(
      { temperature: tewsInput.temperature, respiratoryRate: tewsInput.respiratoryRate, pulse: tewsInput.pulse, systolicBP: tewsInput.systolicBP, spo2: tewsInput.spo2 },
      patient?.isPediatric ?? false,
      patient?.age,
    )
  ), [tewsInput, patient?.isPediatric, patient?.age]);

  // Select age-appropriate discriminator lists
  const vuDiscriminators = patient?.isPediatric ? PEDIATRIC_VERY_URGENT_DISCRIMINATORS : VERY_URGENT_DISCRIMINATORS;
  const uDiscriminators = patient?.isPediatric ? PEDIATRIC_URGENT_DISCRIMINATORS : URGENT_DISCRIMINATORS;

  // Discriminator computed
  const hasVeryUrgentSigns = useMemo(() => hasCheckedDiscriminators(vuDiscriminators, checkedVeryUrgent), [vuDiscriminators, checkedVeryUrgent]);
  const hasUrgentSigns = useMemo(() => hasCheckedDiscriminators(uDiscriminators, checkedUrgent), [uDiscriminators, checkedUrgent]);

  if (!patient) {
    return (
      <div className="p-6">
        <div className="text-center text-gray-600">Patient not found</div>
      </div>
    );
  }

  const hasEmergency = hasEmergencySigns(emergencySigns);

  // Determine final category incorporating discriminators
  const getFinalCategory = (): { category: TriageCategory; reason: string } => {
    if (hasEmergency) return { category: 'RED', reason: 'Emergency signs present' };
    if (tewsInput.spo2 < 92) return { category: 'RED', reason: 'SpO\u2082 < 92%' };
    if (scoring.totalScore >= 7) return { category: 'RED', reason: `TEWS ${scoring.totalScore} (\u22657)` };
    if (scoring.totalScore >= 5) return { category: 'ORANGE', reason: `TEWS ${scoring.totalScore} (5-6)` };
    // TEWS 0-4: discriminator assessment required
    if (hasVeryUrgentSigns) return { category: 'ORANGE', reason: 'Very urgent discriminator' };
    if (hasUrgentSigns) return { category: 'YELLOW', reason: 'Urgent discriminator' };
    if (scoring.totalScore >= 3) return { category: 'YELLOW', reason: `TEWS ${scoring.totalScore} (3-4)` };
    return { category: 'GREEN', reason: 'No urgent criteria' };
  };

  const discriminatorNeeded = isDiscriminatorRequired(scoring.totalScore, hasEmergency);

  const steps = [
    { label: 'Emergency Signs', description: 'Critical assessment' },
    { label: 'TEWS Input', description: 'Vital parameters' },
    ...(discriminatorNeeded ? [{ label: 'Discriminators', description: 'Symptom check' }] : []),
    { label: 'Category Assignment', description: 'Final triage' },
  ];

  const toggleVeryUrgent = (id: string) => setCheckedVeryUrgent((p) => ({ ...p, [id]: !p[id] }));
  const toggleUrgent = (id: string) => setCheckedUrgent((p) => ({ ...p, [id]: !p[id] }));

  const handleEmergencySignsComplete = () => {
    setEmergencySigns(patient.id, emergencySigns);
    
    if (hasEmergency) {
      // Skip TEWS + discriminators, go straight to RED
      assignCategory(patient.id, 'RED', undefined);
      addAuditEntry({
        action: 'CATEGORY_ASSIGNED',
        performedBy: 'SYSTEM',
        performedByName: 'mSAT Engine',
        patientId: patient.id,
        details: 'Emergency signs detected \u2014 RED assigned immediately',
        newValue: 'RED',
      });
      setCurrentStep(discriminatorNeeded ? 3 : 2);
    } else {
      setCurrentStep(1);
    }
  };

  const handleTEWSComplete = () => {
    setTEWSInput(patient.id, tewsInput);

    // Record TEWS calculation in history
    const final = getFinalCategory();
    addTEWSHistoryEntry(
      patient.id,
      scoring,
      final.category,
      final.reason,
      {
        spo2: tewsInput.spo2,
        hadEmergencySigns: hasEmergency,
        discriminatorApplied: false,
        performedBy: 'mSAT Engine',
      },
    );

    if (discriminatorNeeded) {
      // Go to discriminator step
      setCurrentStep(2);
    } else {
      // TEWS >= 5, skip discriminators, assign category directly
      assignCategory(patient.id, final.category, scoring.totalScore);
      setCurrentStep(2);
    }
  };

  const handleDiscriminatorComplete = () => {
    const final = getFinalCategory();
    assignCategory(patient.id, final.category, scoring.totalScore);
    setDiscriminatorReviewed(true);

    // Update history with discriminator-applied entry
    addTEWSHistoryEntry(
      patient.id,
      scoring,
      final.category,
      final.reason,
      {
        spo2: tewsInput.spo2,
        hadEmergencySigns: false,
        discriminatorApplied: true,
        performedBy: 'mSAT Engine',
      },
    );

    setCurrentStep(3);
  };

  const handleFinishTriage = () => {
    const final = getFinalCategory();
    setTriageStatus(patient.id, 'TRIAGED');

    addAuditEntry({
      action: 'TRIAGE_COMPLETED',
      performedBy: 'SYSTEM',
      performedByName: 'mSAT Engine',
      patientId: patient.id,
      details: `mSAT triage completed. TEWS: ${scoring.totalScore}, Category: ${final.category}, Reason: ${final.reason}. ${discriminatorNeeded ? `Discriminator: VU=${hasVeryUrgentSigns}, U=${hasUrgentSigns}.` : 'Discriminator not required.'} Duration: ${Math.round((Date.now() - triageStartedAt.getTime()) / 1000)}s`,
    });

    addAuditEntry({
      action: 'CATEGORY_ASSIGNED',
      performedBy: 'SYSTEM',
      performedByName: 'mSAT Engine',
      patientId: patient.id,
      details: `Assigned ${final.category} \u2014 ${final.reason}`,
      newValue: final.category,
    });

    navigate('/dashboard');
  };

  // Determine which step index is the "final" step
  const finalStepIndex = steps.length - 1;

  return (
    <div className="min-h-full p-5">
      <div className="max-w-5xl mx-auto">
      {/* Header */}
      <div className="mb-5">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold bg-gradient-to-r from-accent-600 via-accent-500 to-accent-400 bg-clip-text text-transparent mb-1">mSAT Triage Assessment</h2>
            <p className="text-gray-600 flex items-center gap-2 mt-1">
              <div className="w-2 h-2 bg-accent-600 rounded-full"></div>
              Patient: {patient.fullName} | Age: {patient.age} | {patient.gender}
            </p>
          </div>
          {patient.isPediatric && (
            <div className="px-4 py-2 bg-gradient-to-r from-accent-100 to-accent-200 border border-accent-300 rounded-xl flex items-center gap-2 shadow-md">
              <span>👶</span>
              <span className="font-semibold text-accent-600">Pediatric Mode</span>
            </div>
          )}
        </div>
      </div>

      {/* Stepper */}
      <Stepper steps={steps} currentStep={currentStep} />

      {/* Step Content */}
      <div className="mt-5">
        {/* Step 1: Emergency Signs */}
        {currentStep === 0 && (
          <div className="space-y-4">
            <div className="bg-white/80 backdrop-blur-sm rounded-2xl p-5 shadow-lg border border-gray-100">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-12 h-12 bg-gradient-to-br from-red-100 to-red-200 rounded-xl flex items-center justify-center">
                  <AlertCircle className="w-6 h-6 text-red-600" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-gray-900">Emergency Signs Checklist</h3>
                  <p className="text-sm text-gray-600">
                    If ANY sign is present, patient receives <Badge category="RED" /> immediately
                  </p>
                </div>
              </div>

              <div className="space-y-4">
                {EMERGENCY_SIGNS_CHECKLIST.map((sign) => (
                  <div key={sign.key} className="border-2 border-gray-200 rounded-xl p-4 hover:border-accent-300 hover:shadow-md transition-all duration-200 bg-white/50">
                    <label className="flex items-start gap-4 cursor-pointer">
                      <div className="flex items-center h-6">
                        <input
                          type="checkbox"
                          checked={emergencySigns[sign.key]}
                          onChange={(e) =>
                            setEmergencySignsLocal({
                              ...emergencySigns,
                              [sign.key]: e.target.checked,
                            })
                          }
                          className="w-5 h-5 text-red-600 border-gray-300 rounded focus:ring-red-500"
                        />
                      </div>
                      <div className="flex-1">
                        <div className="font-medium text-gray-900">{sign.label}</div>
                        <div className="text-sm text-gray-600 mt-1">{sign.description}</div>
                      </div>
                    </label>
                  </div>
                ))}
              </div>

              {hasEmergency && (
                <div className="mt-6 p-5 bg-gradient-to-r from-red-50 to-rose-50 border border-red-200 rounded-2xl shadow-md">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-red-500 rounded-xl flex items-center justify-center shadow-md">
                      <AlertCircle className="w-5 h-5 text-white" />
                    </div>
                    <div className="flex-1">
                      <div className="font-semibold text-red-900">Emergency Signs Detected</div>
                      <div className="text-sm text-red-700">
                        Patient will be assigned <Badge category="RED" size="sm" /> category
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>

            <div className="flex justify-end">
              <button onClick={handleEmergencySignsComplete} className="px-8 py-3 bg-gradient-to-r from-accent-600 to-accent-400 text-white rounded-xl hover:shadow-lg hover:scale-105 transition-all font-semibold flex items-center gap-2 shadow-md">
                Continue
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}

        {/* Step 2: TEWS Input */}
        {currentStep === 1 && !hasEmergency && (
          <div className="space-y-4">
            <div className="grid grid-cols-3 gap-4">
              {/* Input Form */}
              <div className="col-span-2 bg-white/80 backdrop-blur-sm rounded-2xl p-5 shadow-lg border border-gray-100 space-y-4">
                <h3 className="text-sm font-bold text-gray-900 mb-4 flex items-center gap-2">
                  <div className="w-1 h-6 bg-gradient-to-b from-accent-600 to-accent-400 rounded-full"></div>
                  TEWS Parameters
                </h3>

                {/* Mobility */}
                <div>
                  <label className="label">Mobility</label>
                  <select
                    className="input-field"
                    value={tewsInput.mobility}
                    onChange={(e) =>
                      setTEWSInputLocal({ ...tewsInput, mobility: e.target.value as Mobility })
                    }
                  >
                    <option value="AMBULATORY">Ambulatory (walking)</option>
                    <option value="WHEELCHAIR">Wheelchair</option>
                    <option value="STRETCHER">Stretcher</option>
                  </select>
                </div>

                {/* AVPU */}
                <div>
                  <label className="label">AVPU (Consciousness Level)</label>
                  <select
                    className="input-field"
                    value={tewsInput.avpu}
                    onChange={(e) => setTEWSInputLocal({ ...tewsInput, avpu: e.target.value as AVPU })}
                  >
                    <option value="A">Alert</option>
                    <option value="V">Responds to Voice</option>
                    <option value="P">Responds to Pain</option>
                    <option value="U">Unresponsive</option>
                  </select>
                </div>

                {/* Vital Signs Grid */}
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="label">Temperature (°C)</label>
                    <input
                      type="number"
                      step="0.1"
                      className="input-field"
                      value={tewsInput.temperature}
                      onChange={(e) =>
                        setTEWSInputLocal({ ...tewsInput, temperature: parseFloat(e.target.value) })
                      }
                    />
                  </div>

                  <div>
                    <label className="label">Respiratory Rate (breaths/min)</label>
                    <input
                      type="number"
                      className="input-field"
                      value={tewsInput.respiratoryRate}
                      onChange={(e) =>
                        setTEWSInputLocal({ ...tewsInput, respiratoryRate: parseInt(e.target.value) })
                      }
                    />
                  </div>

                  <div>
                    <label className="label">Pulse (bpm)</label>
                    <input
                      type="number"
                      className="input-field"
                      value={tewsInput.pulse}
                      onChange={(e) =>
                        setTEWSInputLocal({ ...tewsInput, pulse: parseInt(e.target.value) })
                      }
                    />
                  </div>

                  <div>
                    <label className="label">Systolic BP (mmHg)</label>
                    <input
                      type="number"
                      className="input-field"
                      value={tewsInput.systolicBP}
                      onChange={(e) =>
                        setTEWSInputLocal({ ...tewsInput, systolicBP: parseInt(e.target.value) })
                      }
                    />
                  </div>

                  <div>
                    <label className="label">SpO₂ (%)</label>
                    <input
                      type="number"
                      className="input-field"
                      value={tewsInput.spo2}
                      onChange={(e) =>
                        setTEWSInputLocal({ ...tewsInput, spo2: parseInt(e.target.value) })
                      }
                    />
                  </div>

                  <div>
                    <label className="label">Trauma</label>
                    <select
                      className="input-field"
                      value={tewsInput.trauma ? 'YES' : 'NO'}
                      onChange={(e) =>
                        setTEWSInputLocal({ ...tewsInput, trauma: e.target.value === 'YES' })
                      }
                    >
                      <option value="NO">No</option>
                      <option value="YES">Yes</option>
                    </select>
                  </div>
                </div>
              </div>

              {/* Live Score Display */}
              <div className="bg-white/80 backdrop-blur-sm rounded-2xl p-6 shadow-lg border border-gray-100 flex flex-col items-center justify-center">
                <ScoreDisplay
                  score={scoring.totalScore}
                  category={category}
                  riskLevel={riskLevel}
                  size="md"
                />

                {tewsInput.spo2 < 92 && (
                  <div className="mt-4 p-3 bg-gradient-to-r from-red-50 to-rose-50 border border-red-200 rounded-xl text-xs font-semibold text-red-800 text-center shadow-sm">
                    ⚠️ SpO₂ &lt; 92% → Automatic RED
                  </div>
                )}

                {/* TEWS Trend Indicator */}
                {tewsTrend && tewsHistoryCount >= 2 && (
                  <div className={`mt-3 p-3 rounded-xl text-xs font-semibold text-center border ${
                    tewsTrend.direction === 'WORSENING' ? 'bg-red-50 border-red-200 text-red-800' :
                    tewsTrend.direction === 'IMPROVING' ? 'bg-green-50 border-green-200 text-green-800' :
                    'bg-gray-50 border-gray-200 text-gray-700'
                  }`}>
                    {tewsTrend.direction === 'WORSENING' && `▲ Score increased by ${tewsTrend.delta} (prev: ${tewsTrend.previousScore})`}
                    {tewsTrend.direction === 'IMPROVING' && `▼ Score decreased by ${Math.abs(tewsTrend.delta)} (prev: ${tewsTrend.previousScore})`}
                    {tewsTrend.direction === 'STABLE' && `⬤ Score stable at ${tewsTrend.currentScore}`}
                    {tewsTrend.alertRequired && tewsTrend.alertMessage && (
                      <div className="mt-1 text-red-600 font-bold">{tewsTrend.alertMessage}</div>
                    )}
                  </div>
                )}

                {/* Score Breakdown */}
                <div className="mt-3 p-2.5 bg-gray-50/80 rounded-xl text-xs text-gray-600 text-center">
                  {getScoreBreakdownText(scoring)}
                </div>
              </div>
            </div>

            {/* Vital Validation Warnings */}
            {vitalWarnings.length > 0 && (
              <div className="bg-white/80 backdrop-blur-sm rounded-2xl p-4 shadow-lg border border-amber-200">
                <h4 className="text-xs font-bold text-amber-900 mb-2 flex items-center gap-2">
                  <Shield className="w-4 h-4 text-amber-600" />
                  Vital Sign Validation ({vitalWarnings.length} alert{vitalWarnings.length > 1 ? 's' : ''})
                </h4>
                <div className="space-y-1.5">
                  {vitalWarnings.map((v, i) => (
                    <div key={i} className={`px-3 py-1.5 rounded-lg text-xs font-medium border ${getValidationBgColor(v.severity)}`}>
                      {v.message}
                    </div>
                  ))}
                </div>
                {hasImpossibleValues(vitalWarnings.map((v) => v)) && (
                  <div className="mt-2 text-xs text-red-700 font-bold">
                    ⛔ One or more values appear to be data entry errors. Please verify before continuing.
                  </div>
                )}
              </div>
            )}

            <div className="flex justify-between">
              <button onClick={() => setCurrentStep(0)} className="px-6 py-3 bg-white border-2 border-gray-300 text-gray-700 rounded-xl hover:bg-gray-50 hover:border-gray-400 transition-all font-semibold shadow-sm flex items-center gap-2">
                <ArrowLeft className="w-4 h-4" />
                Back
              </button>
              <button
                onClick={handleTEWSComplete}
                disabled={!isValid}
                className="px-8 py-3 bg-gradient-to-r from-accent-600 to-accent-400 text-white rounded-xl hover:shadow-lg hover:scale-105 transition-all font-semibold flex items-center gap-2 shadow-md disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100"
              >
                {discriminatorNeeded ? 'Continue to Discriminators' : 'Assign Category'}
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}

        {/* Step 3: Discriminator Assessment (only when TEWS 0-4) */}
        {currentStep === 2 && discriminatorNeeded && (
          <div className="space-y-4">
            <div className="bg-white/80 backdrop-blur-sm rounded-2xl p-5 shadow-lg border border-gray-100">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-12 h-12 bg-gradient-to-br from-amber-100 to-amber-200 rounded-xl flex items-center justify-center">
                  <Shield className="w-6 h-6 text-amber-600" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-gray-900">Symptom Discriminator Assessment</h3>
                  <p className="text-sm text-gray-600">
                    TEWS {scoring.totalScore} (0-4) — Check presenting symptoms to determine urgency
                  </p>
                </div>
              </div>

              {/* Discriminator Result Banner */}
              <div className={`rounded-xl p-3 mb-4 border ${hasVeryUrgentSigns ? 'bg-orange-50 border-orange-200' : hasUrgentSigns ? 'bg-yellow-50 border-yellow-200' : 'bg-green-50 border-green-200'}`}>
                <div className="flex items-center gap-2">
                  <div className={`w-3 h-3 rounded-full ${hasVeryUrgentSigns ? 'bg-orange-500' : hasUrgentSigns ? 'bg-yellow-500' : 'bg-green-500'}`} />
                  <span className="text-sm font-bold text-gray-800">
                    {hasVeryUrgentSigns ? 'VERY URGENT \u2192 ORANGE (10 min)' : hasUrgentSigns ? 'URGENT \u2192 YELLOW (30 min)' : 'ROUTINE \u2192 GREEN (60 min)'}
                  </span>
                </div>
              </div>

              {/* Very Urgent */}
              <div className="mb-4">
                <div className="flex items-center gap-2 mb-3">
                  <div className="w-2.5 h-2.5 rounded-full bg-orange-500" />
                  <span className="text-sm font-bold text-gray-800">Very Urgent Signs</span>
                  <Badge category="ORANGE" size="sm" />
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {vuDiscriminators.map((group) => (
                    <div key={group.system} className={`rounded-xl p-3 ${group.bgColor} border border-white/40`}>
                      <div className="flex items-center gap-1.5 mb-2">
                        <span className="text-xs">{group.icon}</span>
                        <span className={`text-xs font-bold ${group.color}`}>{group.system}</span>
                      </div>
                      <div className="space-y-1.5">
                        {group.items.map((item) => (
                          <label key={item.id} className="flex items-start gap-2 cursor-pointer">
                            <input type="checkbox" checked={!!checkedVeryUrgent[item.id]} onChange={() => toggleVeryUrgent(item.id)} className="w-4 h-4 rounded border-gray-300 text-orange-600 focus:ring-orange-500 mt-0.5" />
                            <span className={`text-sm ${checkedVeryUrgent[item.id] ? 'font-semibold text-orange-800' : 'text-gray-600'}`}>{item.label}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Urgent */}
              <div>
                <div className="flex items-center gap-2 mb-3">
                  <div className="w-2.5 h-2.5 rounded-full bg-yellow-500" />
                  <span className="text-sm font-bold text-gray-800">Urgent Signs</span>
                  <Badge category="YELLOW" size="sm" />
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {uDiscriminators.map((group) => (
                    <div key={group.system} className={`rounded-xl p-3 ${group.bgColor} border border-white/40`}>
                      <div className="flex items-center gap-1.5 mb-2">
                        <span className="text-xs">{group.icon}</span>
                        <span className={`text-xs font-bold ${group.color}`}>{group.system}</span>
                      </div>
                      <div className="space-y-1.5">
                        {group.items.map((item) => (
                          <label key={item.id} className="flex items-start gap-2 cursor-pointer">
                            <input type="checkbox" checked={!!checkedUrgent[item.id]} onChange={() => toggleUrgent(item.id)} className="w-4 h-4 rounded border-gray-300 text-yellow-600 focus:ring-yellow-500 mt-0.5" />
                            <span className={`text-sm ${checkedUrgent[item.id] ? 'font-semibold text-yellow-800' : 'text-gray-600'}`}>{item.label}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Checked discriminators summary */}
              {(hasVeryUrgentSigns || hasUrgentSigns) && (
                <div className="mt-4 p-4 bg-gradient-to-r from-slate-50 to-gray-50 border border-gray-200 rounded-xl">
                  <div className="text-xs font-semibold text-gray-700 mb-2">Selected Discriminators:</div>
                  <div className="space-y-1">
                    {getCheckedDiscriminatorLabels(vuDiscriminators, checkedVeryUrgent).map((label, i) => (
                      <div key={`vu-${i}`} className="text-xs text-orange-700 flex items-center gap-1.5"><span className="w-1.5 h-1.5 bg-orange-500 rounded-full" />{label}</div>
                    ))}
                    {getCheckedDiscriminatorLabels(uDiscriminators, checkedUrgent).map((label, i) => (
                      <div key={`u-${i}`} className="text-xs text-yellow-700 flex items-center gap-1.5"><span className="w-1.5 h-1.5 bg-yellow-500 rounded-full" />{label}</div>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <div className="flex justify-between">
              <button onClick={() => setCurrentStep(1)} className="px-6 py-3 bg-white border-2 border-gray-300 text-gray-700 rounded-xl hover:bg-gray-50 hover:border-gray-400 transition-all font-semibold shadow-sm flex items-center gap-2">
                <ArrowLeft className="w-4 h-4" />
                Back to TEWS
              </button>
              <button
                onClick={handleDiscriminatorComplete}
                className="px-8 py-3 bg-gradient-to-r from-accent-600 to-accent-400 text-white rounded-xl hover:shadow-lg hover:scale-105 transition-all font-semibold flex items-center gap-2 shadow-md"
              >
                Assign Category
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}

        {/* Final Step: Category Assignment */}
        {currentStep === finalStepIndex && (
          <div className="space-y-6">
            <div className="bg-white/80 backdrop-blur-sm rounded-2xl p-5 shadow-lg border border-gray-100 text-center">
              <h3 className="text-sm font-bold bg-gradient-to-r from-accent-600 to-accent-400 bg-clip-text text-transparent mb-4">mSAT Triage Complete</h3>
              
              <ScoreDisplay
                score={patient.tewsScore || scoring.totalScore}
                category={patient.category || getFinalCategory().category}
                riskLevel={riskLevel}
                size="lg"
              />

              <div className="mt-4 p-4 bg-gradient-to-br from-accent-100 to-accent-200 rounded-2xl shadow-sm">
                <div className="text-sm text-gray-600 mb-2">Category assigned at:</div>
                <div className="font-bold text-gray-900">
                  {patient.categoryAssignedAt?.toLocaleString() || new Date().toLocaleString()}
                </div>
                <div className="text-xs text-gray-500 mt-1">
                  Reason: {getFinalCategory().reason}
                </div>
              </div>

              {hasEmergency && (
                <div className="mt-4 p-5 bg-gradient-to-r from-red-50 to-rose-50 border border-red-200 rounded-2xl shadow-md">
                  <div className="text-sm text-red-900 font-semibold">
                    Emergency signs detected \u2014 bypassed TEWS + discriminator assessment
                  </div>
                </div>
              )}

              {discriminatorNeeded && discriminatorReviewed && (
                <div className="mt-4 p-4 bg-gradient-to-r from-amber-50 to-yellow-50 border border-amber-200 rounded-2xl shadow-sm">
                  <div className="text-sm font-semibold text-amber-900 mb-2">Discriminator Assessment Applied</div>
                  <div className="text-xs text-amber-700">
                    {hasVeryUrgentSigns && 'Very urgent signs present \u2192 Upgraded to ORANGE'}
                    {hasUrgentSigns && !hasVeryUrgentSigns && 'Urgent signs present \u2192 Assigned YELLOW'}
                    {!hasVeryUrgentSigns && !hasUrgentSigns && 'No discriminators checked \u2192 Routine GREEN'}
                  </div>
                  {(hasVeryUrgentSigns || hasUrgentSigns) && (
                    <div className="mt-2 space-y-0.5">
                      {getCheckedDiscriminatorLabels(vuDiscriminators, checkedVeryUrgent).map((label, i) => (
                        <div key={i} className="text-xs text-orange-700 flex items-center gap-1.5 justify-center"><span className="w-1 h-1 bg-orange-500 rounded-full" />{label}</div>
                      ))}
                      {getCheckedDiscriminatorLabels(uDiscriminators, checkedUrgent).map((label, i) => (
                        <div key={i} className="text-xs text-yellow-700 flex items-center gap-1.5 justify-center"><span className="w-1 h-1 bg-yellow-500 rounded-full" />{label}</div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="flex justify-center">
              <button onClick={handleFinishTriage} className="px-7 py-2.5 bg-gradient-to-r from-accent-600 to-accent-400 text-white rounded-xl hover:shadow-xl hover:scale-105 transition-all font-bold text-sm shadow-lg">
                Complete Triage & Return to Dashboard
              </button>
            </div>
          </div>
        )}
      </div>
      </div>
    </div>
  );
}
