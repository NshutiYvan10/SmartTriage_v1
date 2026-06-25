import { useState, useMemo } from 'react';
import {
  Brain, ShieldAlert, ShieldCheck, TrendingUp, TrendingDown, Minus,
  Activity, Heart, Wind, Droplet, Thermometer, Zap, Candy,
  AlertTriangle, CheckCircle, ChevronRight, Search, 
  ArrowUpCircle, ArrowDownCircle, BarChart3, Users, RefreshCw,
  XCircle,
} from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { useVitalStore } from '@/store/vitalStore';
import { useAlertStore } from '@/store/alertStore';
import { useAuditStore } from '@/store/auditStore';
import { useAuthStore } from '@/store/authStore';
import { analyzePatientRetriage } from '@/hooks/useDynamicRetriage';
import { useGlobalRetriage } from '@/hooks/useDynamicRetriage';
import { TriageCategory } from '@/types';
import { useTheme } from '@/hooks/useTheme';

const CATEGORY_CONFIG: Record<string, { color: string; bg: string; border: string; label: string }> = {
  RED:    { color: 'text-red-600',    bg: 'rgba(239,68,68,0.08)',   border: '1px solid rgba(239,68,68,0.2)',   label: 'Emergency' },
  ORANGE: { color: 'text-orange-600', bg: 'rgba(249,115,22,0.08)',  border: '1px solid rgba(249,115,22,0.2)',  label: 'Very Urgent' },
  YELLOW: { color: 'text-yellow-600', bg: 'rgba(234,179,8,0.08)',   border: '1px solid rgba(234,179,8,0.2)',   label: 'Urgent' },
  GREEN:  { color: 'text-emerald-600',bg: 'rgba(34,197,94,0.08)',   border: '1px solid rgba(34,197,94,0.2)',   label: 'Standard' },
  BLUE:   { color: 'text-blue-600',   bg: 'rgba(59,130,246,0.08)',  border: '1px solid rgba(59,130,246,0.2)',  label: 'Non-Urgent' },
};

const vitalIcons: Record<string, any> = {
  heartRate: Heart,
  respiratoryRate: Wind,
  spo2: Droplet,
  systolicBP: Activity,
  temperature: Thermometer,
  glucose: Candy,
  ecg: Zap,
};

const vitalLabels: Record<string, string> = {
  heartRate: 'HR',
  respiratoryRate: 'RR',
  spo2: 'SpO₂',
  systolicBP: 'SBP',
  temperature: 'Temp',
  glucose: 'Gluc',
  ecg: 'ECG',
};

export function DynamicRetriage() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const patients = usePatientStore((s) => s.patients);
  const updatePatient = usePatientStore((s) => s.updatePatient);
  const addOverride = usePatientStore((s) => s.addOverride);
  const getVitalHistory = useVitalStore((s) => s.getVitalHistory);
  const alerts = useAlertStore((s) => s.alerts);
  const dismissAlert = useAlertStore((s) => s.dismissAlert);
  const addAuditEntry = useAuditStore((s) => s.addEntry);
  const { runGlobalCheck } = useGlobalRetriage();
  const user = useAuthStore((s) => s.user);

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPatientId, setSelectedPatientId] = useState<string | null>(null);
  const [filterCategory, setFilterCategory] = useState<TriageCategory | 'ALL'>('ALL');

  // Only show triaged patients
  const triagedPatients = useMemo(() => {
    return patients.filter(p => p.category && p.triageStatus === 'TRIAGED');
  }, [patients]);

  // Run analysis on all patients
  const analyses = useMemo(() => {
    return triagedPatients.map(p => ({
      patient: p,
      analysis: analyzePatientRetriage(p.id, p.category!, p.isPediatric, getVitalHistory),
    }));
  }, [triagedPatients, getVitalHistory]);

  // Filter analyses
  const filteredAnalyses = useMemo(() => {
    let result = analyses;
    if (filterCategory !== 'ALL') {
      result = result.filter(a => a.patient.category === filterCategory);
    }
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      result = result.filter(a =>
        a.patient.fullName.toLowerCase().includes(q) ||
        a.patient.id.toLowerCase().includes(q)
      );
    }
    // Sort by composite risk score (highest first)
    return result.sort((a, b) => b.analysis.compositeRiskScore - a.analysis.compositeRiskScore);
  }, [analyses, filterCategory, searchQuery]);

  // Stats
  const stats = {
    total: triagedPatients.length,
    escalate: analyses.filter(a => a.analysis.direction === 'ESCALATE').length,
    deescalate: analyses.filter(a => a.analysis.direction === 'DE_ESCALATE').length,
    stable: analyses.filter(a => a.analysis.direction === 'STABLE').length,
    avgRisk: analyses.length > 0 ? Math.round(analyses.reduce((s, a) => s + a.analysis.compositeRiskScore, 0) / analyses.length) : 0,
    activeAlerts: alerts.filter(a => !a.acknowledged).length,
  };

  const selectedAnalysis = selectedPatientId
    ? analyses.find(a => a.patient.id === selectedPatientId)
    : null;

  // Handle applying AI recommendation
  const handleApplyRecommendation = (patientId: string, previousCat: TriageCategory, newCat: TriageCategory) => {
    // Update patient category
    updatePatient(patientId, { category: newCat });
    
    // Add override record
    const clinicianId = user?.id ?? 'UNKNOWN';
    const clinicianName = user?.fullName ?? 'Unknown Clinician';
    addOverride(patientId, {
      id: `OV${Date.now()}`,
      timestamp: new Date(),
      clinicianId,
      clinicianName,
      originalCategory: previousCat,
      newCategory: newCat,
      reason: 'AI-recommended re-triage applied by clinician',
    });

    // Log to audit
    addAuditEntry({
      action: 'CATEGORY_OVERRIDDEN',
      performedBy: clinicianId,
      performedByName: clinicianName,
      patientId,
      details: `AI re-triage applied: ${previousCat} → ${newCat}`,
      previousValue: previousCat,
      newValue: newCat,
    });
  };

  const handleDismiss = (patientId: string) => {
    const dismissClinicianId = user?.id ?? 'UNKNOWN';
    const dismissClinicianName = user?.fullName ?? 'Unknown Clinician';
    const patientAlerts = alerts.filter(a => a.patientId === patientId && !a.acknowledged);
    patientAlerts.forEach(a => {
      dismissAlert(a.id, dismissClinicianId, 'Clinician reviewed — no action needed');
    });

    addAuditEntry({
      action: 'ALERT_ACKNOWLEDGED',
      performedBy: dismissClinicianId,
      performedByName: dismissClinicianName,
      patientId,
      details: `AI alert(s) dismissed — clinician determined no action needed`,
    });
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Dark Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Brain className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white">Dynamic Re-Triage AI Engine</h1>
                  <p className="text-sm text-white/50">Real-time AI analysis with 7-vital composite scoring & bi-directional recommendations</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => runGlobalCheck()}
                  className="flex items-center gap-2 px-4 py-2 bg-white/15 hover:bg-white/25 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all duration-300 border border-white/10"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  Run Analysis
                </button>
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-violet-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Module 5</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* ── Stats Row ── */}
        <div className="grid grid-cols-2 md:grid-cols-6 gap-3 animate-fade-up" style={{ animationDelay: '0.06s' } as any}>
          {[
            { label: 'Monitored', value: stats.total, icon: Users, color: 'text-slate-700', bg: 'rgba(100,116,139,0.08)' },
            { label: 'Escalate', value: stats.escalate, icon: ArrowUpCircle, color: 'text-red-600', bg: 'rgba(239,68,68,0.08)' },
            { label: 'De-escalate', value: stats.deescalate, icon: ArrowDownCircle, color: 'text-emerald-600', bg: 'rgba(34,197,94,0.08)' },
            { label: 'Stable', value: stats.stable, icon: Minus, color: 'text-slate-500', bg: 'rgba(148,163,184,0.08)' },
            { label: 'Avg Risk', value: `${stats.avgRisk}%`, icon: BarChart3, color: 'text-indigo-600', bg: 'rgba(99,102,241,0.08)' },
            { label: 'Active Alerts', value: stats.activeAlerts, icon: AlertTriangle, color: 'text-amber-600', bg: 'rgba(245,158,11,0.08)' },
          ].map((stat) => {
            const Icon = stat.icon;
            return (
              <div key={stat.label} className="rounded-2xl p-4" style={glassCard}>
                <div className="flex items-center gap-2.5">
                  <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: stat.bg }}>
                    <Icon className={`w-5 h-5 ${stat.color}`} />
                  </div>
                  <div>
                    <p className={`text-lg font-extrabold ${text.heading}`}>{stat.value}</p>
                    <p className={`text-[10px] font-semibold ${text.muted} uppercase tracking-wider`}>{stat.label}</p>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* ── Search & Filter ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.12s' } as any}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="relative flex-1">
              <Search className={`absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted}`} />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search patients by name or ID..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm ${text.body} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center gap-1.5 flex-wrap">
              {(['ALL', 'RED', 'ORANGE', 'YELLOW', 'GREEN'] as const).map((cat) => (
                <button
                  key={cat}
                  onClick={() => setFilterCategory(cat)}
                  className={`px-3 py-2 text-[11px] font-bold rounded-xl transition-all duration-300 border ${filterCategory === cat
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent'
                    : `${text.body} hover:bg-white/5 border-transparent`
                  }`}
                >
                  {cat}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* ── Main Content: Two columns ── */}
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">

          {/* ── Left: Patient List ── */}
          <div className="lg:col-span-2 space-y-2.5">
            <div className="flex items-center gap-2 px-1 animate-fade-up" style={{ animationDelay: '0.16s' } as any}>
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
                <Activity className="w-3.5 h-3.5 text-indigo-500" />
              </div>
              <h3 className={`text-sm font-extrabold ${text.heading}`}>Patient Risk Assessment</h3>
              <span className={`text-[10px] ${text.muted} font-medium ml-auto`}>{filteredAnalyses.length} patients</span>
            </div>

            <div className="space-y-2 max-h-[600px] overflow-y-auto pr-1 scrollbar-thin">
              {filteredAnalyses.length === 0 ? (
                <div className="rounded-2xl p-8 text-center" style={glassCard}>
                  <Users className={`w-10 h-10 ${text.muted} mx-auto mb-3`} />
                  <p className={`text-sm font-bold ${text.label}`}>No triaged patients found</p>
                  <p className={`text-xs ${text.muted} mt-1`}>Register and triage patients to see AI analysis</p>
                </div>
              ) : (
                filteredAnalyses.map(({ patient, analysis }, idx) => {
                  const catCfg = CATEGORY_CONFIG[patient.category!] || CATEGORY_CONFIG.GREEN;
                  const isSelected = selectedPatientId === patient.id;
                  const isEscalate = analysis.direction === 'ESCALATE';
                  const isDeEscalate = analysis.direction === 'DE_ESCALATE';

                  return (
                    <button
                      key={patient.id}
                      onClick={() => setSelectedPatientId(patient.id)}
                      className={`w-full text-left rounded-2xl p-4 transition-all duration-300 animate-fade-up ${
                        isSelected ? 'ring-2 ring-violet-400/40 -translate-y-0.5' : 'hover:-translate-y-0.5'
                      } ${isEscalate ? 'animate-critical-border' : ''}`}
                      style={{
                        ...glassCard,
                        border: isSelected
                          ? '1px solid rgba(139,92,246,0.4)'
                          : isEscalate
                            ? undefined
                            : glassCard.border,
                        animationDelay: `${0.18 + idx * 0.04}s`,
                      } as any}
                    >
                      <div className="flex items-center gap-3">
                        {/* Risk Score Circle */}
                        <div className={`w-12 h-12 rounded-xl flex flex-col items-center justify-center ${
                          analysis.compositeRiskScore > 60 ? 'bg-red-500/10' :
                          analysis.compositeRiskScore > 30 ? 'bg-amber-500/10' : 'bg-emerald-500/10'
                        }`}>
                          <span className={`text-base font-extrabold ${
                            analysis.compositeRiskScore > 60 ? 'text-red-600' :
                            analysis.compositeRiskScore > 30 ? 'text-amber-600' : 'text-emerald-600'
                          }`}>{analysis.compositeRiskScore}</span>
                          <span className={`text-[8px] font-bold ${text.muted} -mt-0.5`}>RISK</span>
                        </div>

                        {/* Patient Info */}
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <p className={`text-[13px] font-bold ${text.heading} truncate`}>{patient.fullName}</p>
                            {patient.isPediatric && (
                              <span
                                className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-pink-600"
                                style={{ background: 'rgba(236,72,153,0.08)', border: '1px solid rgba(236,72,153,0.2)' }}
                              >PED</span>
                            )}
                          </div>
                          <div className="flex items-center gap-2 mt-0.5">
                            <span
                              className={`text-[10px] font-bold ${catCfg.color} px-2 py-0.5 rounded-md`}
                              style={{ background: catCfg.bg, border: catCfg.border }}
                            >
                              {patient.category}
                            </span>
                            <span className={`text-[10px] ${text.muted}`}>
                              Confidence: {Math.round(analysis.confidence * 100)}%
                            </span>
                          </div>
                        </div>

                        {/* Direction Badge */}
                        <div className="flex-shrink-0">
                          {isEscalate && (
                            <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg" style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}>
                              <ArrowUpCircle className="w-3.5 h-3.5 text-red-400" />
                              <span className="text-[10px] font-bold text-red-600">ESCALATE</span>
                            </div>
                          )}
                          {isDeEscalate && (
                            <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg" style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}>
                              <ArrowDownCircle className="w-3.5 h-3.5 text-emerald-400" />
                              <span className="text-[10px] font-bold text-emerald-600">DE-ESCALATE</span>
                            </div>
                          )}
                          {analysis.direction === 'STABLE' && (
                            <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                              <Minus className={`w-3.5 h-3.5 ${text.muted}`} />
                              <span className={`text-[10px] font-bold ${text.body}`}>STABLE</span>
                            </div>
                          )}
                        </div>
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          </div>

          {/* ── Right: Selected Patient Detail ── */}
          <div className="lg:col-span-3">
            {!selectedAnalysis ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={{ ...glassCard, animationDelay: '0.2s' } as any}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(139,92,246,0.08)' }}>
                  <Brain className="w-8 h-8 text-violet-400" />
                </div>
                <p className={`text-sm font-bold ${text.label}`}>Select a Patient</p>
                <p className={`text-xs ${text.muted} mt-1`}>Choose a patient from the list to see detailed AI re-triage analysis</p>
              </div>
            ) : (
              <div className="space-y-3 animate-fade-up" style={{ animationDelay: '0.15s' } as any}>
                {/* Patient Header */}
                <div className="rounded-2xl p-5" style={glassCard}>
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${
                        selectedAnalysis.analysis.direction === 'ESCALATE' ? 'bg-red-500/10' :
                        selectedAnalysis.analysis.direction === 'DE_ESCALATE' ? 'bg-emerald-500/10' : 'bg-slate-500/10'
                      }`}>
                        {selectedAnalysis.analysis.direction === 'ESCALATE' ? (
                          <ShieldAlert className="w-6 h-6 text-red-500" />
                        ) : selectedAnalysis.analysis.direction === 'DE_ESCALATE' ? (
                          <ShieldCheck className="w-6 h-6 text-emerald-500" />
                        ) : (
                          <Activity className={`w-6 h-6 ${text.muted}`} />
                        )}
                      </div>
                      <div>
                        <h3 className={`text-base font-extrabold ${text.heading}`}>{selectedAnalysis.patient.fullName}</h3>
                        <div className="flex items-center gap-2 mt-0.5">
                          <span className={`text-[11px] ${text.muted}`}>ID: {selectedAnalysis.patient.id.slice(0, 10)}</span>
                          <span className={`text-[11px] ${text.muted}`}>|</span>
                          <span className={`text-[11px] ${text.muted}`}>Age: {selectedAnalysis.patient.age}</span>
                          {selectedAnalysis.patient.isPediatric && (
                            <span
                              className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg text-pink-600"
                              style={{ background: 'rgba(236,72,153,0.08)', border: '1px solid rgba(236,72,153,0.2)' }}
                            >Pediatric</span>
                          )}
                        </div>
                      </div>
                    </div>

                    {/* Category Change Visualization */}
                    {selectedAnalysis.analysis.recommendedCategory && (
                      <div className="flex items-center gap-2">
                        <span
                          className={`text-xs font-bold px-3 py-1.5 rounded-lg ${CATEGORY_CONFIG[selectedAnalysis.patient.category!]?.color}`}
                          style={{
                            background: CATEGORY_CONFIG[selectedAnalysis.patient.category!]?.bg,
                            border: CATEGORY_CONFIG[selectedAnalysis.patient.category!]?.border,
                          }}
                        >
                          {selectedAnalysis.patient.category}
                        </span>
                        <ChevronRight className="w-4 h-4 text-slate-400" />
                        <span
                          className={`text-xs font-bold px-3 py-1.5 rounded-lg ${CATEGORY_CONFIG[selectedAnalysis.analysis.recommendedCategory]?.color}`}
                          style={{
                            background: CATEGORY_CONFIG[selectedAnalysis.analysis.recommendedCategory]?.bg,
                            border: CATEGORY_CONFIG[selectedAnalysis.analysis.recommendedCategory]?.border,
                          }}
                        >
                          {selectedAnalysis.analysis.recommendedCategory}
                        </span>
                      </div>
                    )}
                  </div>
                </div>

                {/* Composite Risk & Confidence */}
                <div className="grid grid-cols-2 gap-3">
                  <div className="rounded-2xl p-4" style={glassCard}>
                    <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-2`}>Composite Risk Score</p>
                    <div className="flex items-end gap-2">
                      <span className={`text-3xl font-extrabold ${
                        selectedAnalysis.analysis.compositeRiskScore > 60 ? 'text-red-600' :
                        selectedAnalysis.analysis.compositeRiskScore > 30 ? 'text-amber-600' : 'text-emerald-600'
                      }`}>
                        {selectedAnalysis.analysis.compositeRiskScore}
                      </span>
                      <span className={`text-xs ${text.muted} font-semibold mb-1`}>/100</span>
                    </div>
                    {/* Risk bar */}
                    <div className="w-full h-2 rounded-full bg-slate-500/15 mt-3 overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all duration-1000 ${
                          selectedAnalysis.analysis.compositeRiskScore > 60 ? 'bg-gradient-to-r from-red-400 to-red-500' :
                          selectedAnalysis.analysis.compositeRiskScore > 30 ? 'bg-gradient-to-r from-amber-400 to-amber-500' :
                          'bg-gradient-to-r from-emerald-400 to-emerald-500'
                        }`}
                        style={{ width: `${selectedAnalysis.analysis.compositeRiskScore}%` }}
                      />
                    </div>
                  </div>

                  <div className="rounded-2xl p-4" style={glassCard}>
                    <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-2`}>AI Confidence</p>
                    <div className="flex items-end gap-2">
                      <span className={`text-3xl font-extrabold ${
                        selectedAnalysis.analysis.confidence > 0.7 ? 'text-indigo-600' :
                        selectedAnalysis.analysis.confidence > 0.4 ? 'text-amber-600' : 'text-slate-500'
                      }`}>
                        {Math.round(selectedAnalysis.analysis.confidence * 100)}%
                      </span>
                    </div>
                    <div className="w-full h-2 rounded-full bg-slate-500/15 mt-3 overflow-hidden">
                      <div
                        className="h-full rounded-full bg-gradient-to-r from-violet-400 to-indigo-500 transition-all duration-1000"
                        style={{ width: `${selectedAnalysis.analysis.confidence * 100}%` }}
                      />
                    </div>
                  </div>
                </div>

                {/* Vital Trend Summary */}
                <div className="rounded-2xl p-4" style={glassCard}>
                  <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-3`}>Vital Trend Analysis (7 Channels)</p>
                  <div className="grid grid-cols-7 gap-2">
                    {(['heartRate', 'respiratoryRate', 'spo2', 'systolicBP', 'temperature', 'glucose', 'ecg'] as const).map((vitalKey) => {
                      const Icon = vitalIcons[vitalKey];
                      const history = getVitalHistory(selectedAnalysis.patient.id, vitalKey);
                      const latest = history.length > 0 ? history[history.length - 1].value : null;
                      const label = vitalLabels[vitalKey];

                      // Determine if this vital is contributing to the recommendation
                      const isDeteriorating = selectedAnalysis.analysis.deteriorationFactors.some(f => f.toLowerCase().includes(label.toLowerCase()));
                      const isImproving = selectedAnalysis.analysis.improvementFactors.some(f => f.toLowerCase().includes(label.toLowerCase()));
                      const isBreach = selectedAnalysis.analysis.thresholdBreaches.some(f => f.toLowerCase().includes(label.toLowerCase()));

                      return (
                        <div
                          key={vitalKey}
                          className={`rounded-xl p-2.5 flex flex-col items-center gap-1.5 transition-all duration-300 ${
                            isBreach ? 'ring-1 ring-red-300' : isDeteriorating ? 'ring-1 ring-amber-300' : isImproving ? 'ring-1 ring-emerald-300' : ''
                          }`}
                          style={glassInner}
                        >
                          <Icon className={`w-4 h-4 ${
                            isBreach ? 'text-red-500' : isDeteriorating ? 'text-amber-500' : isImproving ? 'text-emerald-500' : text.muted
                          }`} />
                          <span className={`text-[9px] font-bold ${text.body}`}>{label}</span>
                          <span className={`text-xs font-extrabold ${
                            latest === null ? text.muted :
                            isBreach ? 'text-red-600' : isDeteriorating ? 'text-amber-600' : isImproving ? 'text-emerald-600' : text.heading
                          }`}>
                            {latest !== null ? latest.toFixed(vitalKey === 'temperature' ? 1 : 0) : '—'}
                          </span>
                          <div className="flex items-center">
                            {isDeteriorating && <TrendingUp className="w-3 h-3 text-red-400" />}
                            {isImproving && <TrendingDown className="w-3 h-3 text-emerald-400" />}
                            {!isDeteriorating && !isImproving && <Minus className={`w-3 h-3 ${text.muted}`} />}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Factors Lists */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {/* Deterioration Factors */}
                  {selectedAnalysis.analysis.deteriorationFactors.length > 0 && (
                    <div className="rounded-2xl p-4" style={{ ...glassCard, border: '1px solid rgba(239,68,68,0.2)' }}>
                      <div className="flex items-center gap-2 mb-3">
                        <TrendingUp className="w-4 h-4 text-red-500" />
                        <p className="text-[11px] font-bold text-red-600 uppercase tracking-wider">Deterioration Factors</p>
                      </div>
                      <div className="space-y-1.5">
                        {selectedAnalysis.analysis.deteriorationFactors.map((f, i) => (
                          <div key={i} className="flex items-start gap-2 px-2.5 py-2 rounded-lg" style={glassInner}>
                            <AlertTriangle className="w-3 h-3 text-red-400 mt-0.5 flex-shrink-0" />
                            <span className={`text-[11px] ${text.label} font-medium`}>{f}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Threshold Breaches */}
                  {selectedAnalysis.analysis.thresholdBreaches.length > 0 && (
                    <div className="rounded-2xl p-4" style={{ ...glassCard, border: '1px solid rgba(239,68,68,0.3)' }}>
                      <div className="flex items-center gap-2 mb-3">
                        <Zap className="w-4 h-4 text-red-600" />
                        <p className="text-[11px] font-bold text-red-700 uppercase tracking-wider">Threshold Breaches</p>
                      </div>
                      <div className="space-y-1.5">
                        {selectedAnalysis.analysis.thresholdBreaches.map((f, i) => (
                          <div key={i} className="flex items-start gap-2 px-2.5 py-2 rounded-lg bg-red-500/10 border border-red-500/20">
                            <ShieldAlert className="w-3 h-3 text-red-500 mt-0.5 flex-shrink-0" />
                            <span className="text-[11px] text-red-300 font-medium">{f}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Improvement Factors */}
                  {selectedAnalysis.analysis.improvementFactors.length > 0 && (
                    <div className="rounded-2xl p-4" style={{ ...glassCard, border: '1px solid rgba(34,197,94,0.2)' }}>
                      <div className="flex items-center gap-2 mb-3">
                        <TrendingDown className="w-4 h-4 text-emerald-500" />
                        <p className="text-[11px] font-bold text-emerald-600 uppercase tracking-wider">Improvement Factors</p>
                      </div>
                      <div className="space-y-1.5">
                        {selectedAnalysis.analysis.improvementFactors.map((f, i) => (
                          <div key={i} className="flex items-start gap-2 px-2.5 py-2 rounded-lg" style={glassInner}>
                            <CheckCircle className="w-3 h-3 text-emerald-400 mt-0.5 flex-shrink-0" />
                            <span className={`text-[11px] ${text.label} font-medium`}>{f}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* No findings */}
                  {selectedAnalysis.analysis.deteriorationFactors.length === 0 &&
                   selectedAnalysis.analysis.improvementFactors.length === 0 &&
                   selectedAnalysis.analysis.thresholdBreaches.length === 0 && (
                    <div className="rounded-2xl p-6 text-center col-span-2" style={glassCard}>
                      <CheckCircle className="w-8 h-8 text-emerald-400 mx-auto mb-2" />
                      <p className={`text-sm font-bold ${text.label}`}>All vitals stable</p>
                      <p className={`text-xs ${text.muted} mt-1`}>No significant trends or threshold breaches detected</p>
                    </div>
                  )}
                </div>

                {/* Action Buttons */}
                {selectedAnalysis.analysis.recommendedCategory && (
                  <div className="rounded-2xl p-4" style={glassCard}>
                    <div className="flex items-center justify-between">
                      <div>
                        <p className={`text-xs font-bold ${text.label}`}>AI Recommendation</p>
                        <p className={`text-[11px] ${text.muted} mt-0.5`}>
                          {selectedAnalysis.analysis.direction === 'ESCALATE'
                            ? `Escalate from ${selectedAnalysis.patient.category} to ${selectedAnalysis.analysis.recommendedCategory}`
                            : `De-escalate from ${selectedAnalysis.patient.category} to ${selectedAnalysis.analysis.recommendedCategory}`
                          }
                        </p>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          onClick={() => handleDismiss(selectedAnalysis.patient.id)}
                          className={`flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold ${text.body} hover:bg-white/5 rounded-xl transition-all duration-300`}
                          style={{ border: borderStyle }}
                        >
                          <XCircle className="w-3.5 h-3.5" />
                          Dismiss
                        </button>
                        <button
                          onClick={() => handleApplyRecommendation(
                            selectedAnalysis.patient.id,
                            selectedAnalysis.patient.category!,
                            selectedAnalysis.analysis.recommendedCategory!,
                          )}
                          className={`flex items-center gap-1.5 px-5 py-2.5 text-xs font-bold text-white rounded-xl transition-all duration-300 shadow-md hover:-translate-y-0.5 ${
                            selectedAnalysis.analysis.direction === 'ESCALATE'
                              ? 'bg-gradient-to-r from-red-500 to-red-600 shadow-red-500/20 hover:shadow-red-500/30'
                              : 'bg-gradient-to-r from-emerald-500 to-emerald-600 shadow-emerald-500/20 hover:shadow-emerald-500/30'
                          }`}
                        >
                          {selectedAnalysis.analysis.direction === 'ESCALATE' ? (
                            <ArrowUpCircle className="w-3.5 h-3.5" />
                          ) : (
                            <ArrowDownCircle className="w-3.5 h-3.5" />
                          )}
                          Apply {selectedAnalysis.analysis.direction === 'ESCALATE' ? 'Escalation' : 'De-escalation'}
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
