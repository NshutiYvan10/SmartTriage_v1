import { useState, useMemo, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Stethoscope, Search, Clock, Users, Baby, Filter,
  Activity, ChevronRight, Siren,
  Timer, UserPlus, HeartPulse, ShieldAlert, Leaf, Droplets, BedDouble,
} from 'lucide-react';
import { usePatientStore, visitResponseToPatient } from '@/store/patientStore';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';
import { triageApi } from '@/api/triage';
import type { Patient, TriageCategory } from '@/types';
import type { VisitResponse } from '@/api/types';
import { PlacePatientDialog } from '@/modules/beds/PlacePatientDialog';

/* ─── Config ─── */
const categoryConfig: Record<TriageCategory, { label: string; bg: string; text: string; border: string; dot: string; gradient: string }> = {
  RED: { label: 'Emergency', bg: 'bg-red-500/20', text: 'text-red-300', border: 'border-red-500/30', dot: 'bg-red-500', gradient: 'from-red-500 to-red-600' },
  ORANGE: { label: 'Very Urgent', bg: 'bg-orange-500/20', text: 'text-orange-300', border: 'border-orange-500/30', dot: 'bg-orange-500', gradient: 'from-orange-500 to-orange-600' },
  YELLOW: { label: 'Urgent', bg: 'bg-yellow-500/20', text: 'text-yellow-300', border: 'border-yellow-500/30', dot: 'bg-yellow-500', gradient: 'from-yellow-500 to-amber-600' },
  GREEN: { label: 'Standard', bg: 'bg-emerald-500/20', text: 'text-emerald-300', border: 'border-emerald-500/30', dot: 'bg-emerald-500', gradient: 'from-emerald-500 to-emerald-600' },
  BLUE: { label: 'Non-Urgent', bg: 'bg-blue-500/20', text: 'text-blue-300', border: 'border-blue-500/30', dot: 'bg-blue-500', gradient: 'from-blue-500 to-blue-600' },
};

const statusConfig: Record<string, { label: string; bg: string; text: string; border: string }> = {
  WAITING: { label: 'Waiting', bg: 'bg-amber-500/10', text: 'text-amber-600', border: 'border-amber-500/20' },
  IN_TRIAGE: { label: 'In Triage', bg: 'bg-cyan-500/10', text: 'text-cyan-600', border: 'border-cyan-500/20' },
  TRIAGED: { label: 'Triaged', bg: 'bg-emerald-500/10', text: 'text-emerald-600', border: 'border-emerald-500/20' },
  IN_TREATMENT: { label: 'In Treatment', bg: 'bg-indigo-500/10', text: 'text-indigo-600', border: 'border-indigo-500/20' },
};

const categoryGradients: Record<string, [string, string]> = {
  RED: ['#ef4444', '#dc2626'],
  ORANGE: ['#f97316', '#ea580c'],
  YELLOW: ['#eab308', '#ca8a04'],
  GREEN: ['#10b981', '#059669'],
  BLUE: ['#3b82f6', '#2563eb'],
};

/* ─── Triage showcase config ─── */
const showcaseConfig = [
  {
    key: 'RED',
    label: 'Emergency',
    sublabel: 'Immediate attention required',
    icon: Siren,
    gradient: 'from-red-500 to-red-600',
    lightBg: 'rgba(254,226,226,0.6)',
    darkBg: 'rgba(239,68,68,0.15)',
    borderColor: 'rgba(239,68,68,0.25)',
    darkBorderColor: 'rgba(239,68,68,0.35)',
    accentColor: '#ef4444',
    dotColor: 'bg-red-500',
    textColor: 'text-red-700',
    badgeBg: 'bg-red-100',
    badgeBorder: 'border-red-200',
    shadowColor: 'shadow-red-500/20',
  },
  {
    key: 'ORANGE',
    label: 'Very Urgent',
    sublabel: 'Seen within 10 minutes',
    icon: ShieldAlert,
    gradient: 'from-orange-500 to-orange-600',
    lightBg: 'rgba(255,237,213,0.6)',
    darkBg: 'rgba(249,115,22,0.15)',
    borderColor: 'rgba(249,115,22,0.25)',
    darkBorderColor: 'rgba(249,115,22,0.35)',
    accentColor: '#f97316',
    dotColor: 'bg-orange-500',
    textColor: 'text-orange-700',
    badgeBg: 'bg-orange-100',
    badgeBorder: 'border-orange-200',
    shadowColor: 'shadow-orange-500/20',
  },
  {
    key: 'YELLOW',
    label: 'Urgent',
    sublabel: 'Seen within 60 minutes',
    icon: HeartPulse,
    gradient: 'from-yellow-500 to-amber-600',
    lightBg: 'rgba(254,249,195,0.6)',
    darkBg: 'rgba(234,179,8,0.15)',
    borderColor: 'rgba(234,179,8,0.25)',
    darkBorderColor: 'rgba(234,179,8,0.35)',
    accentColor: '#eab308',
    dotColor: 'bg-yellow-500',
    textColor: 'text-yellow-700',
    badgeBg: 'bg-yellow-100',
    badgeBorder: 'border-yellow-200',
    shadowColor: 'shadow-yellow-500/20',
  },
  {
    key: 'GREEN',
    label: 'Standard',
    sublabel: 'Seen within 4 hours',
    icon: Leaf,
    gradient: 'from-emerald-500 to-emerald-600',
    lightBg: 'rgba(209,250,229,0.6)',
    darkBg: 'rgba(16,185,129,0.15)',
    borderColor: 'rgba(16,185,129,0.25)',
    darkBorderColor: 'rgba(16,185,129,0.35)',
    accentColor: '#10b981',
    dotColor: 'bg-emerald-500',
    textColor: 'text-emerald-700',
    badgeBg: 'bg-emerald-100',
    badgeBorder: 'border-emerald-200',
    shadowColor: 'shadow-emerald-500/20',
  },
  {
    key: 'BLUE',
    label: 'Non-Urgent',
    sublabel: 'Routine care needed',
    icon: Droplets,
    gradient: 'from-blue-500 to-blue-600',
    lightBg: 'rgba(219,234,254,0.6)',
    darkBg: 'rgba(59,130,246,0.15)',
    borderColor: 'rgba(59,130,246,0.25)',
    darkBorderColor: 'rgba(59,130,246,0.35)',
    accentColor: '#3b82f6',
    dotColor: 'bg-blue-500',
    textColor: 'text-blue-700',
    badgeBg: 'bg-blue-100',
    badgeBorder: 'border-blue-200',
    shadowColor: 'shadow-blue-500/20',
  },
];

function timeAgo(date: Date): string {
  const mins = Math.floor((Date.now() - new Date(date).getTime()) / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ${mins % 60}m ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

/* ─── Animated Triage Showcase Component ─── */
function TriageShowcase({ allPatients, onNavigate }: { allPatients: Patient[]; onNavigate: (id: string) => void }) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [activeIdx, setActiveIdx] = useState(0);
  const [isTransitioning, setIsTransitioning] = useState(false);

  const categoryCounts = useMemo(() => {
    const counts: Record<string, Patient[]> = { RED: [], ORANGE: [], YELLOW: [], GREEN: [], BLUE: [], NONE: [] };
    allPatients.forEach((p) => {
      const cat = p.category || 'NONE';
      if (counts[cat]) counts[cat].push(p);
    });
    return counts;
  }, [allPatients]);

  useEffect(() => {
    const interval = setInterval(() => {
      setIsTransitioning(true);
      setTimeout(() => {
        setActiveIdx((prev) => (prev + 1) % showcaseConfig.length);
        setIsTransitioning(false);
      }, 200);
    }, 5000);
    return () => clearInterval(interval);
  }, []);

  const goTo = useCallback((idx: number) => {
    setIsTransitioning(true);
    setTimeout(() => {
      setActiveIdx(idx);
      setIsTransitioning(false);
    }, 200);
  }, []);

  const active = showcaseConfig[activeIdx];
  const activePatients = categoryCounts[active.key] || [];
  const ActiveIcon = active.icon;
  const total = allPatients.length;
  const waiting = allPatients.filter((p) => p.triageStatus === 'WAITING').length;

  return (
    <div className="animate-fade-up" style={{ animationDelay: '0.05s' }}>
      <div className="rounded-2xl overflow-hidden" style={glassCard}>
        <div className="flex flex-col lg:flex-row">

          {/* Left: Category selector pills */}
          <div className="lg:w-52 flex lg:flex-col gap-1.5 p-3 lg:py-4 overflow-x-auto lg:overflow-x-visible flex-shrink-0" style={{ borderRight: isDark ? '1px solid rgba(2,132,199,0.18)' : '1px solid rgba(203,213,225,0.2)' }}>
            <div className="hidden lg:flex flex-col gap-1 px-2 pb-3 mb-1" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.15)' }}>
              <span className={`text-2xl font-bold ${text.heading}`}>{total}</span>
              <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Total Patients</span>
              <div className="flex items-center gap-1.5 mt-1">
                <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse" />
                <span className="text-[10px] text-amber-600 font-semibold">{waiting} waiting</span>
              </div>
            </div>

            {showcaseConfig.map((cat, idx) => {
              const count = (categoryCounts[cat.key] || []).length;
              const isActive = idx === activeIdx;
              return (
                <button
                  key={cat.key}
                  onClick={() => goTo(idx)}
                  className={`flex items-center gap-2.5 px-3 py-2 rounded-xl transition-all duration-300 flex-shrink-0 ${
                    isActive ? 'shadow-md scale-[1.02]' : isDark ? 'hover:bg-white/10' : 'hover:bg-white/40'
                  }`}
                  style={isActive ? {
                    background: isDark ? cat.darkBg : cat.lightBg,
                    border: `1px solid ${isDark ? cat.darkBorderColor : cat.borderColor}`,
                    boxShadow: `0 4px 16px ${isDark ? 'rgba(0,0,0,0.3)' : cat.borderColor}`,
                  } : {}}
                >
                  <div className={`w-2.5 h-2.5 rounded-full ${cat.dotColor} flex-shrink-0 ${isActive ? 'animate-pulse' : ''}`} />
                  <span className={`text-xs font-bold flex-1 text-left ${isActive ? cat.textColor : text.body}`}>
                    {cat.label}
                  </span>
                  <span className={`text-xs font-bold min-w-[20px] text-center px-1.5 py-0.5 rounded-md ${
                    isActive ? `${cat.badgeBg} ${cat.textColor} border ${cat.badgeBorder}` : text.muted
                  }`}>
                    {count}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Right: Active category showcase */}
          <div className="flex-1 p-5 lg:p-6 min-w-0">
            <div className={`transition-all duration-300 ${isTransitioning ? 'opacity-0 translate-y-2' : 'opacity-100 translate-y-0'}`}>
              <div className="flex items-center gap-3 mb-4">
                <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${active.gradient} flex items-center justify-center shadow-lg ${active.shadowColor}`}>
                  <ActiveIcon className="w-5 h-5 text-white" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className={`text-base font-bold ${active.textColor}`}>{active.label}</h3>
                    <span className={`text-[10px] font-bold ${active.badgeBg} ${active.textColor} border ${active.badgeBorder} px-2 py-0.5 rounded-md`}>
                      {activePatients.length} patient{activePatients.length !== 1 ? 's' : ''}
                    </span>
                  </div>
                  <p className={`text-[11px] ${text.muted} font-medium mt-0.5`}>{active.sublabel}</p>
                </div>
              </div>

              {activePatients.length === 0 ? (
                <div className="py-8 text-center rounded-xl" style={glassInner}>
                  <ActiveIcon className="w-8 h-8 mx-auto mb-2" style={{ color: active.accentColor, opacity: 0.3 }} />
                  <p className={`text-xs ${text.muted} font-medium`}>No patients in this category</p>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
                  {activePatients.slice(0, 4).map((patient, pIdx) => (
                    <button
                      key={patient.id}
                      onClick={() => onNavigate(patient.id)}
                      className="flex items-center gap-3 px-3.5 py-3 rounded-xl text-left transition-all duration-300 hover:scale-[1.02] hover:shadow-md group"
                      style={{
                        ...glassInner,
                        borderLeft: `3px solid ${active.accentColor}`,
                        animationDelay: `${pIdx * 0.08}s`,
                      }}
                    >
                      <div className="w-9 h-9 rounded-lg flex items-center justify-center text-[11px] font-bold text-white flex-shrink-0"
                        style={{ background: `linear-gradient(135deg, ${categoryGradients[active.key][0]}, ${categoryGradients[active.key][1]})` }}
                      >
                        {patient.fullName.split(' ').map(n => n[0]).join('').slice(0, 2)}
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className={`text-sm font-semibold ${text.body} truncate group-hover:text-cyan-600 transition-colors`}>
                          {patient.fullName}
                        </p>
                        <p className={`text-[10px] ${text.muted} font-medium truncate`}>
                          {patient.age}y · {patient.chiefComplaint} · {timeAgo(patient.arrivalTimestamp)}
                        </p>
                      </div>
                      {patient.tewsScore !== undefined && (
                        <div className={`w-7 h-7 rounded-md flex items-center justify-center text-[11px] font-bold flex-shrink-0 ${
                          patient.tewsScore >= 7
                            ? 'bg-red-500/20 text-red-300 border border-red-500/30'
                            : patient.tewsScore >= 4
                              ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                              : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                        }`}>
                          {patient.tewsScore}
                        </div>
                      )}
                      <ChevronRight className={`w-3.5 h-3.5 ${text.muted} group-hover:text-cyan-500 transition-colors flex-shrink-0`} />
                    </button>
                  ))}
                  {activePatients.length > 4 && (
                    <div className={`flex items-center justify-center px-3 py-2 rounded-xl text-[11px] font-semibold ${text.muted}`} style={glassInner}>
                      +{activePatients.length - 4} more patient{activePatients.length - 4 > 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              )}

              <div className="flex items-center justify-center gap-1.5 mt-4">
                {showcaseConfig.map((cat, idx) => (
                  <button
                    key={cat.key}
                    onClick={() => goTo(idx)}
                    className={`rounded-full transition-all duration-300 ${
                      idx === activeIdx
                        ? `w-6 h-2 ${cat.dotColor}`
                        : isDark ? 'w-2 h-2 bg-white/20 hover:bg-white/30' : 'w-2 h-2 bg-slate-300 hover:bg-slate-400'
                    }`}
                  />
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function formatWait(ts: Date): string {
  const mins = Math.floor((Date.now() - new Date(ts).getTime()) / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ${mins % 60}m`;
  return `${Math.floor(hrs / 24)}d`;
}

type Tab = 'all' | 'adult' | 'pediatric';
type StatusFilter = 'all' | 'WAITING' | 'IN_TRIAGE' | 'TRIAGED';

export function TriageQueue() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const storePatients = usePatientStore((s) => s.patients);
  const fetchActiveVisits = usePatientStore((s) => s.fetchActiveVisits);
  const user = useAuthStore((s) => s.user);
  const refreshCurrentShift = useAuthStore((s) => s.refreshCurrentShift);

  const [activeTab, setActiveTab] = useState<Tab>('all');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [now, setNow] = useState(Date.now());
  const [placeVisit, setPlaceVisit] = useState<VisitResponse | null>(null);

  // RBAC fix — refresh the current shift assignment whenever the Triage
  // Queue mounts so a Charge Nurse reassignment mid-session is reflected
  // immediately (instead of waiting until the next login).
  useEffect(() => {
    refreshCurrentShift().catch(() => { /* silent — stale cache is the fallback */ });
  }, [refreshCurrentShift]);

  // Fetch patients from backend
  useEffect(() => {
    fetchActiveVisits(user?.hospitalId || '');
  }, [fetchActiveVisits, user?.hospitalId]);

  // Refresh wait times every minute
  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 60000);
    return () => clearInterval(interval);
  }, []);

  const allPatients = useMemo(() => storePatients, [storePatients]);

  // The triage-DESK queue is for patients awaiting their INITIAL ED triage. A patient
  // who is still 'WAITING' (AWAITING_TRIAGE) but already holds a real treatment zone has
  // been placed there ahead of the desk — a field-triaged RED/ORANGE ambulance arrival
  // (acuity-split: they bypass the desk and go straight to Resus/Acute) or a Direct Resus
  // admission. They belong on their ZONE board, not this queue, so we exclude them here —
  // this is what stops a field-triaged "Unknown Alpha" RED showing as awaiting triage for
  // a charge-nurse/all-zones viewer. (The pre-triage placeholder zone TRIAGE is NOT a real
  // placement, so those stay in the queue.)
  const placedAwaitingTriage = useCallback(
    (p: Patient) => p.triageStatus === 'WAITING' && !!p.currentEdZone && p.currentEdZone !== 'TRIAGE',
    [],
  );

  // Only show triage-relevant statuses (not IN_TREATMENT usually, but include for visibility)
  const triageQueue = useMemo(() => {
    let list = allPatients.filter((p) => !placedAwaitingTriage(p));

    // Tab filter
    if (activeTab === 'adult') list = list.filter((p) => !p.isPediatric);
    if (activeTab === 'pediatric') list = list.filter((p) => p.isPediatric);

    // Status filter
    if (statusFilter !== 'all') list = list.filter((p) => p.triageStatus === statusFilter);

    // Search
    if (search.trim()) {
      const q = search.toLowerCase();
      list = list.filter(
        (p) =>
          p.fullName.toLowerCase().includes(q) ||
          p.chiefComplaint.toLowerCase().includes(q) ||
          p.id.toLowerCase().includes(q)
      );
    }

    // Sort: WAITING first (by arrival time ASC), then IN_TRIAGE, then TRIAGED
    const statusOrder: Record<string, number> = { WAITING: 0, IN_TRIAGE: 1, TRIAGED: 2, IN_TREATMENT: 3 };
    list.sort((a, b) => {
      const diff = (statusOrder[a.triageStatus] ?? 4) - (statusOrder[b.triageStatus] ?? 4);
      if (diff !== 0) return diff;
      // Within same status, sort by TEWS (highest first for urgency), then by arrival (oldest first)
      if (a.tewsScore !== undefined && b.tewsScore !== undefined) {
        const tewsDiff = b.tewsScore - a.tewsScore;
        if (tewsDiff !== 0) return tewsDiff;
      }
      return new Date(a.arrivalTimestamp).getTime() - new Date(b.arrivalTimestamp).getTime();
    });

    return list;
  }, [allPatients, activeTab, statusFilter, search, now, placedAwaitingTriage]);

  // Stats
  const stats = useMemo(() => {
    // Keep the counts consistent with the queue: exclude zone-placed patients who are
    // awaiting their bedside triage (field-triaged ambulance arrivals / Direct Resus).
    const deskBase = allPatients.filter((p) => !placedAwaitingTriage(p));
    const scope = activeTab === 'adult'
      ? deskBase.filter((p) => !p.isPediatric)
      : activeTab === 'pediatric'
        ? deskBase.filter((p) => p.isPediatric)
        : deskBase;
    return {
      total: scope.length,
      waiting: scope.filter((p) => p.triageStatus === 'WAITING').length,
      inTriage: scope.filter((p) => p.triageStatus === 'IN_TRIAGE').length,
      triaged: scope.filter((p) => p.triageStatus === 'TRIAGED').length,
      critical: scope.filter((p) => p.category === 'RED' || p.category === 'ORANGE').length,
      adults: allPatients.filter((p) => !p.isPediatric).length,
      peds: allPatients.filter((p) => p.isPediatric).length,
    };
  }, [allPatients, activeTab, now, placedAwaitingTriage]);

  const ensurePatient = usePatientStore((s) => s.ensurePatient);

  const handleStartTriage = useCallback((patient: Patient) => {
    // Put the patient into the store so the triage form can pre-fill fields
    ensurePatient(patient);
    if (patient.isPediatric) {
      navigate(`/pediatric-triage/${patient.id}`);
    } else {
      navigate(`/adult-triage/${patient.id}`);
    }
  }, [navigate, ensurePatient]);

  /** Build a minimal VisitResponse from a local Patient to hand to PlacePatientDialog. */
  const toVisitLike = useCallback((p: Patient): VisitResponse => ({
    id: p.id,
    visitNumber: '',
    patientId: p.id,
    patientName: p.fullName,
    hospitalId: user?.hospitalId || '',
    arrivalMode: (p.arrivalMode as VisitResponse['arrivalMode']) ?? 'WALK_IN',
    arrivalTime: new Date(p.arrivalTimestamp).toISOString(),
    chiefComplaint: p.chiefComplaint,
    status: p.triageStatus === 'TRIAGED' ? 'TRIAGED' : 'AWAITING_ASSESSMENT',
    currentTriageCategory: p.category ?? null,
    currentTewsScore: p.tewsScore ?? null,
    triageTime: p.categoryAssignedAt ? new Date(p.categoryAssignedAt).toISOString() : null,
    assessmentStartTime: null,
    dispositionType: null,
    dispositionTime: null,
    dispositionNotes: null,
    referringFacility: p.referringFacility ?? null,
    isPediatric: p.isPediatric,
    retriageCount: 0,
    // Phase 1+2 zone-routing fields. Pre-triage patients haven't been
    // placed in a zone yet, so currentEdZone is null; primary clinician
    // is null until first action.
    currentEdZone: null,
    primaryClinicianId: null,
    primaryClinicianName: null,
    createdAt: new Date(p.arrivalTimestamp).toISOString(),
    updatedAt: new Date().toISOString(),
  }), [user?.hospitalId]);

  const handlePlaceInBed = useCallback((p: Patient) => {
    setPlaceVisit(toVisitLike(p));
  }, [toVisitLike]);

  return (
    <div className="min-h-full p-5 animate-fade-in">
      <div className="space-y-5">

        {/* ── Header ── */}
        <div className="flex items-start justify-between animate-fade-up">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-500 to-cyan-700 flex items-center justify-center shadow-lg shadow-cyan-500/20">
              <Stethoscope className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className={`text-xl font-bold ${text.heading} tracking-tight leading-tight`}>
                Triage Queue
              </h1>
              <p className={`text-sm ${text.muted} mt-0.5 font-medium`}>
                {stats.waiting} patient{stats.waiting !== 1 ? 's' : ''} waiting for triage
              </p>
            </div>
          </div>

          <button
            onClick={() => navigate('/entry')}
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-sm font-bold transition-all duration-300 shadow-lg shadow-cyan-500/25 hover:-translate-y-1 hover:shadow-xl"
          >
            <UserPlus className="w-4 h-4" />
            Register Patient
          </button>
        </div>

        {/* ── Triage Category Showcase ── */}
        <TriageShowcase allPatients={allPatients} onNavigate={(id) => {
          const p = allPatients.find(pt => pt.id === id);
          if (p) handleStartTriage(p);
        }} />

        {/* ── Tabs + Search + Filters ── */}
        <div className="flex flex-col gap-3 animate-fade-up" style={{ animationDelay: '0.1s' }}>
          {/* Tab bar */}
          <div className="flex items-center gap-2">
            {([
              { key: 'all' as Tab, label: 'All Patients', count: stats.total, icon: Users },
              { key: 'adult' as Tab, label: 'Adults', count: stats.adults, icon: Users },
              { key: 'pediatric' as Tab, label: 'Pediatric', count: stats.peds, icon: Baby },
            ]).map((tab) => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-bold transition-all duration-300 ${
                  activeTab === tab.key
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                }`}
                style={activeTab !== tab.key ? glassInner : {}}
              >
                <tab.icon className="w-3.5 h-3.5" />
                {tab.label}
                <span className={`px-1.5 py-0.5 rounded-md text-[10px] font-bold ${
                  activeTab === tab.key
                    ? 'bg-white/25 text-white'
                    : isDark ? 'bg-white/10 text-slate-400' : 'bg-slate-100 text-slate-400'
                }`}>
                  {tab.count}
                </span>
              </button>
            ))}
          </div>

          {/* Search + status filter */}
          <div className="flex items-center gap-3 flex-wrap">
            <div className="relative flex-1 min-w-[220px] group">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 group-focus-within:text-cyan-500 transition-colors" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search by name, complaint, or ID..."
                className={`w-full pl-11 pr-4 py-2.5 rounded-xl text-sm ${text.body} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all`}
                style={glassInner}
              />
            </div>

            <div className="flex items-center gap-1.5">
              <Filter className="w-3.5 h-3.5 text-slate-400" />
              {(['all', 'WAITING', 'IN_TRIAGE', 'TRIAGED'] as StatusFilter[]).map((f) => (
                <button
                  key={f}
                  onClick={() => setStatusFilter(f)}
                  className={`px-3 py-2 rounded-xl text-[11px] font-bold transition-all duration-300 ${
                    statusFilter === f
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                      : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                  }`}
                  style={statusFilter !== f ? glassInner : {}}
                >
                  {f === 'all' ? 'All' : f === 'IN_TRIAGE' ? 'In Triage' : f === 'WAITING' ? 'Waiting' : 'Triaged'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* ── Placed, awaiting ED triage (acuity-split arrivals that bypass the desk queue) ── */}
        <PlacedAwaitingEdTriageSection glassCard={glassCard} isDark={isDark} text={text} />

        {/* ── Queue List ── */}
        <div className="rounded-2xl overflow-hidden animate-fade-up" style={{ ...glassCard, animationDelay: '0.15s' }}>
          {/* Header */}
          <div className="px-5 py-3.5 flex items-center justify-between" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.18)' : '1px solid rgba(203,213,225,0.3)' }}>
            <div className="flex items-center gap-2">
              <span className={`text-[11px] font-bold uppercase tracking-wider ${text.muted}`}>Triage Queue</span>
              <span className="inline-flex items-center px-2.5 py-0.5 text-[10px] font-bold rounded-lg text-cyan-600" style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}>{triageQueue.length}</span>
            </div>
            <div className="flex items-center gap-1.5 text-[10px] text-slate-400 font-medium">
              <Timer className="w-3 h-3" />
              Priority sorted
            </div>
          </div>

          {triageQueue.length === 0 ? (
            <div className="px-6 py-16 text-center">
              <Stethoscope className={`w-12 h-12 ${text.muted} mx-auto mb-3`} />
              {/* RBAC fix — explain WHY the queue is empty instead of
                  showing a silent "no patients" with no next action. */}
              {user?.isOnShift === false ? (
                <>
                  <p className={`text-sm font-semibold ${text.muted}`}>You're not on shift</p>
                  <p className={`text-xs ${text.muted} mt-1`}>Ask the Charge Nurse to add you to today's triage shift.</p>
                </>
              ) : user?.currentShiftFunction && user.currentShiftFunction !== 'TRIAGE_NURSE'
                  && user.currentShiftFunction !== 'CHARGE_NURSE' && !user.isShiftLead ? (
                <>
                  <p className={`text-sm font-semibold ${text.muted}`}>Not the Triage Nurse today</p>
                  <p className={`text-xs ${text.muted} mt-1`}>
                    Your shift function is <span className="font-mono">{user.currentShiftFunction}</span>.
                    Only the Triage Nurse (or Charge Nurse override) sees this queue.
                  </p>
                </>
              ) : (
                <>
                  <p className={`text-sm font-semibold ${text.muted}`}>No patients waiting</p>
                  <p className={`text-xs ${text.muted} mt-1`}>Newly registered patients will appear here for triage.</p>
                </>
              )}
            </div>
          ) : (
            <div className="p-4 space-y-2.5">
              {triageQueue.map((patient) => {
                const status = statusConfig[patient.triageStatus];
                const category = patient.category ? categoryConfig[patient.category] : null;
                const isWaiting = patient.triageStatus === 'WAITING';
                const isInTriage = patient.triageStatus === 'IN_TRIAGE';
                const isCritical = patient.category === 'RED';

                return (
                  <div
                    key={patient.id}
                    className={`rounded-xl p-3.5 hover:-translate-y-1 transition-all duration-400 cursor-pointer flex items-center gap-3.5 group ${isCritical ? 'animate-critical-pulse' : ''}`}
                    style={glassInner}
                    onClick={() => handleStartTriage(patient)}
                  >
                    {/* Priority indicator + avatar */}
                    <div className="relative flex-shrink-0">
                      <div className="w-11 h-11 rounded-xl flex items-center justify-center text-xs font-bold text-white shadow-md"
                        style={{
                          background: patient.isPediatric
                            ? 'linear-gradient(135deg, #f472b6, #ec4899)'
                            : category
                              ? `linear-gradient(135deg, ${categoryGradients[patient.category!]?.[0] ?? '#94a3b8'}, ${categoryGradients[patient.category!]?.[1] ?? '#64748b'})`
                              : 'linear-gradient(135deg, #94a3b8, #64748b)',
                        }}
                      >
                        {patient.fullName.split(' ').map(n => n[0]).join('').slice(0, 2)}
                      </div>
                      {/* Pulse ring for waiting patients */}
                      {isWaiting && (
                        <div className={`absolute -top-1 -right-1 w-4 h-4 rounded-full bg-amber-400 border-2 ${isDark ? 'border-slate-900' : 'border-white'} flex items-center justify-center`}>
                          <div className="w-1.5 h-1.5 rounded-full bg-white" />
                        </div>
                      )}
                      {isInTriage && (
                        <div className={`absolute -top-1 -right-1 w-4 h-4 rounded-full bg-cyan-400 border-2 ${isDark ? 'border-slate-900' : 'border-white'} animate-pulse flex items-center justify-center`}>
                          <Activity className="w-2 h-2 text-white" />
                        </div>
                      )}
                    </div>

                    {/* Patient info */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <p className={`text-sm font-semibold ${text.body} truncate`}>
                          {patient.fullName}
                        </p>
                        {patient.isPediatric && (
                          <span className="inline-flex items-center gap-0.5 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-violet-600 flex-shrink-0" style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}>
                            <Baby className="w-2.5 h-2.5" />
                            PEDS
                          </span>
                        )}
                      </div>
                      <p className={`text-[11px] ${text.muted} font-medium flex items-center gap-1.5 mt-0.5`}>
                        <span>{patient.age < 1 ? `${Math.round(patient.age * 12)}mo` : `${patient.age}y`} · {patient.gender === 'MALE' ? 'M' : 'F'}</span>
                        {patient.weight && <span>· {patient.weight}kg</span>}
                        <span className={text.muted}>·</span>
                        <span className="truncate">{patient.chiefComplaint}</span>
                      </p>
                    </div>

                    {/* Wait time */}
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      <Clock className="w-3 h-3 text-slate-400" />
                      <span className={`text-[11px] font-bold ${
                        (() => {
                          const mins = Math.floor((now - new Date(patient.arrivalTimestamp).getTime()) / 60000);
                          return mins > 30 ? 'text-red-500' : mins > 15 ? 'text-amber-500' : 'text-slate-500';
                        })()
                      }`}>
                        {formatWait(patient.arrivalTimestamp)}
                      </span>
                    </div>

                    {/* Badges */}
                    <div className="flex items-center gap-2 flex-shrink-0">
                      {/* Status */}
                      <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-bold ${status.bg} ${status.text} border ${status.border}`}>
                        {status.label}
                      </span>

                      {/* Category */}
                      {category ? (
                        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[10px] font-bold ${category.bg} ${category.text} border ${category.border}`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${category.dot}`} />
                          <span className="hidden md:inline">{category.label}</span>
                        </span>
                      ) : null}

                      {/* TEWS */}
                      {patient.tewsScore !== undefined && (
                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold flex-shrink-0 ${
                          patient.tewsScore >= 7
                            ? 'bg-red-500/20 text-red-300 border border-red-500/30'
                            : patient.tewsScore >= 4
                              ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                              : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                        }`}>
                          {patient.tewsScore}
                        </div>
                      )}
                    </div>

                    {/* Action buttons */}
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      {patient.triageStatus === 'TRIAGED' && (
                        <button
                          onClick={(e) => { e.stopPropagation(); handlePlaceInBed(patient); }}
                          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl text-[11px] font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-500/30 transition-all duration-300"
                          title="Assign this patient to a bed"
                        >
                          <BedDouble className="w-3.5 h-3.5" />
                          Place in bed
                        </button>
                      )}
                      <button
                        onClick={(e) => { e.stopPropagation(); handleStartTriage(patient); }}
                        className={`flex items-center gap-1.5 px-3.5 py-2 rounded-xl text-[11px] font-bold transition-all duration-300 ${
                          isWaiting
                            ? 'bg-cyan-600 hover:bg-cyan-700 text-white shadow-md shadow-cyan-500/20 hover:shadow-lg hover:-translate-y-0.5'
                            : isInTriage
                              ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 hover:bg-cyan-500/30'
                              : isDark ? 'text-slate-400 hover:text-slate-200 hover:bg-white/10' : 'text-slate-400 hover:text-slate-600 hover:bg-white/60'
                        }`}
                        style={!isWaiting && !isInTriage ? glassInner : {}}
                      >
                        {isWaiting ? (
                          <>
                            <Stethoscope className="w-3.5 h-3.5" />
                            Start Triage
                          </>
                        ) : isInTriage ? (
                          <>
                            <Activity className="w-3.5 h-3.5" />
                            Continue
                          </>
                        ) : (
                          <>
                            <ChevronRight className="w-3.5 h-3.5" />
                            View
                          </>
                        )}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Footer */}
          <div className={`px-5 py-3 flex items-center justify-between text-xs ${text.muted} font-medium`} style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.18)' : '1px solid rgba(203,213,225,0.2)' }}>
            <span>
              {triageQueue.length} patient{triageQueue.length !== 1 ? 's' : ''} in queue
              {activeTab !== 'all' && ` (${activeTab === 'adult' ? 'adults only' : 'pediatric only'})`}
            </span>
            <span className="text-[10px]">
              {activeTab === 'pediatric' ? 'Pediatric form (0–11y)' : activeTab === 'adult' ? 'Adult form (12+)' : 'Auto-routes to correct form'}
            </span>
          </div>
        </div>

      </div>

      {/* Place-in-bed modal (patient-first) */}
      {placeVisit && (
        <PlacePatientDialog
          open={!!placeVisit}
          mode={{ kind: 'patient-first', visit: placeVisit }}
          onClose={() => setPlaceVisit(null)}
          onPlaced={() => {
            setPlaceVisit(null);
            // Refresh the active visits so the row picks up the new bed/status.
            fetchActiveVisits(user?.hospitalId || '');
          }}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Placed — awaiting ED triage (acuity-split arrivals that bypass the desk queue)
// ─────────────────────────────────────────────────────────────────

/**
 * The worklist of patients who were routed straight into a treatment zone (a field-RED/ORANGE
 * ambulance arrival, or a Direct Resus admission) and so never entered the pre-triage desk queue,
 * yet still owe a formal ED triage. Without this they'd be reachable only via an alert. Self-hides
 * when empty. "Perform triage" hydrates the store from the visit (the form resolves its patient
 * from the store by visitId) then opens the triage form.
 */
function PlacedAwaitingEdTriageSection({ glassCard, isDark, text }: { glassCard: any; isDark: boolean; text: any }) {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const ensurePatient = usePatientStore((s) => s.ensurePatient);
  const [rows, setRows] = useState<VisitResponse[]>([]);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    try {
      const page = await triageApi.awaitingEdTriage(hospitalId, 0, 50);
      setRows(page.content || []);
      setError(false);
    } catch (e) {
      console.error('[triage] awaiting-ED-triage load failed:', e);
      setError(true);
    }
  }, [hospitalId]);

  useEffect(() => {
    load();
    const id = setInterval(load, 30_000);
    return () => clearInterval(id);
  }, [load]);

  // A load failure must NOT masquerade as "no placed-untriaged patients" on a safety worklist:
  // self-hide only when genuinely empty; if the fetch errored, keep the section visible with a
  // retry so a placed RESUS/ACUTE patient can't silently disappear behind a network/auth blip.
  if (rows.length === 0 && !error) return null;

  const startTriage = (v: VisitResponse) => {
    ensurePatient(visitResponseToPatient(v));
    const path = v.isPediatric ? '/pediatric-triage' : '/adult-triage';
    navigate(`${path}/${v.id}?visitId=${v.id}`);
  };

  return (
    <div className="rounded-2xl overflow-hidden animate-fade-up mb-4" style={{ ...glassCard }}>
      <div className="px-5 py-3.5 flex items-center gap-2 bg-gradient-to-r from-rose-600 to-rose-500 text-white">
        <Siren className="w-4 h-4" />
        <span className="text-[11px] font-bold uppercase tracking-wider">Placed — awaiting ED triage</span>
        <span className="inline-flex items-center px-2 py-0.5 text-[10px] font-bold rounded-lg bg-white/20">{rows.length}</span>
        <span className="hidden sm:inline text-[10px] text-white/80 ml-auto">Ambulance / Direct-Resus arrivals that bypassed the queue</span>
      </div>
      {error && (
        <div className="px-5 py-2.5 flex items-center gap-2 text-[11px] font-semibold bg-rose-500/10 text-rose-600">
          <span className="flex-1">Couldn't load the awaiting-triage list — a load error, NOT confirmation that none are waiting.</span>
          <button onClick={() => load()} className="px-2.5 py-1 rounded-lg bg-rose-500 text-white font-bold hover:bg-rose-600">Retry</button>
        </div>
      )}
      <div className={isDark ? 'divide-y divide-white/10' : 'divide-y divide-slate-200/70'}>
        {rows.map((v) => (
          <div key={v.id} className="px-5 py-3 flex items-center gap-3">
            <div className="flex-1 min-w-0">
              <div className={`text-sm font-bold ${text.heading} truncate`}>
                {v.patientName || 'Unidentified patient'}
                {v.visitNumber && <span className={`ml-2 text-[11px] font-normal ${text.muted}`}>{v.visitNumber}</span>}
              </div>
              <div className={`text-[11px] ${text.muted} flex flex-wrap gap-x-2`}>
                {v.currentEdZone && <span className="font-semibold text-rose-500">{v.currentEdZone}</span>}
                {v.currentTriageCategory && <span>· field {v.currentTriageCategory}</span>}
                {v.chiefComplaint && <span className="truncate">· {v.chiefComplaint}</span>}
              </div>
            </div>
            <button
              onClick={() => startTriage(v)}
              className="shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-bold text-white bg-cyan-600 hover:bg-cyan-700"
            >
              <Stethoscope className="w-3.5 h-3.5" /> Perform triage
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
