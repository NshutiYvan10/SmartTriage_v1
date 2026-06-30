import { useState, useMemo, useEffect } from 'react';
import {
  Search,
  Users,
  UserPlus,
  ChevronRight as ChevronRightIcon,
  ChevronDown,
  ArrowUpDown,
  Siren,
  MapPin,
  Phone,
  CreditCard,
  Calendar,
  Baby,
  Filter,
} from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { useAuthStore } from '@/store/authStore';
import { useNavigate } from 'react-router-dom';
import { useTheme } from '@/hooks/useTheme';
import type { Patient } from '@/types';
import { HandoffPriorityBadges } from '@/components/HandoffPriorityBadges';
import { chartPath } from '@/lib/chartNav';

/* ─── Arrival mode config ─── */
const arrivalModeConfig: Record<string, { label: string; icon: string; bg: string; text: string; border: string }> = {
  WALK_IN: { label: 'Walk-in', icon: '🚶', bg: 'bg-slate-50', text: 'text-slate-600', border: 'border-slate-200' },
  AMBULANCE: { label: 'Ambulance', icon: '🚑', bg: 'bg-red-50', text: 'text-red-600', border: 'border-red-200' },
  REFERRAL: { label: 'Referral', icon: '🏥', bg: 'bg-indigo-50', text: 'text-indigo-600', border: 'border-indigo-200' },
};

/* ─── Extended patient type with demographics for registry view ─── */
interface RegistryPatient extends Patient {
  phone?: string;
  province?: string;
  district?: string;
  registeredAt?: Date;
}

function formatDate(date: Date): string {
  const d = new Date(date);
  const day = d.getDate().toString().padStart(2, '0');
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const month = months[d.getMonth()];
  const year = d.getFullYear();
  const hrs = d.getHours().toString().padStart(2, '0');
  const mins = d.getMinutes().toString().padStart(2, '0');
  return `${day} ${month} ${year}, ${hrs}:${mins}`;
}

export function PatientsList() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const storePatients = usePatientStore((s) => s.patients);
  const fetchActiveVisits = usePatientStore((s) => s.fetchActiveVisits);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();

  // Refresh patient data from backend each time this page is viewed
  useEffect(() => {
    const hospitalId = user?.hospitalId || 'a0000000-0000-0000-0000-000000000001';
    fetchActiveVisits(hospitalId);
  }, [fetchActiveVisits, user?.hospitalId]);

  const [search, setSearch] = useState('');
  const [arrivalFilter, setArrivalFilter] = useState<string>('all');
  const [typeFilter, setTypeFilter] = useState<string>('all');
  const [sortBy, setSortBy] = useState<'time' | 'name' | 'age'>('time');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  const allPatients: RegistryPatient[] = storePatients.map(p => ({ ...p } as RegistryPatient));

  // ── "Minor" filter for the registry view ──
  //
  // This is a UI display filter only — it does NOT determine clinical
  // routing. The system's authoritative pediatric boundary is set by
  // the Rwanda mSAT triage form ("Adult: Over 12 years"; "Child: 3–12
  // years") and computed in Patient.isPediatric() on the backend
  // (<13). That stored flag drives triage form selection, peds resus
  // routing, and pediatric dose checks.
  //
  // The registry filter widens to <18 deliberately because operational
  // reporting often wants "all minors" (the WHO definition of "child")
  // rather than the clinical cutoff. Patients aged 13–17 will show
  // here as "minor" even though the system treats them clinically as
  // adults.
  const isMinor = (p: RegistryPatient): boolean => {
    if (p.isPediatric === true) return true;
    return typeof p.age === 'number' && p.age >= 0 && p.age < 18;
  };

  // Summary stats
  const stats = useMemo(() => ({
    total: allPatients.length,
    adults: allPatients.filter((p) => !isMinor(p)).length,
    pediatric: allPatients.filter((p) => isMinor(p)).length,
    ambulance: allPatients.filter((p) => p.arrivalMode === 'AMBULANCE').length,
    walkIn: allPatients.filter((p) => p.arrivalMode === 'WALK_IN').length,
    referral: allPatients.filter((p) => p.arrivalMode === 'REFERRAL').length,
  }), [allPatients]);

  const filtered = useMemo(() => {
    let list = [...allPatients];

    // Search
    if (search.trim()) {
      const q = search.toLowerCase();
      list = list.filter(
        (p) =>
          p.fullName.toLowerCase().includes(q) ||
          p.id.toLowerCase().includes(q) ||
          (p.nationalId && p.nationalId.includes(q)) ||
          (p.phone && p.phone.includes(q)) ||
          (p.district && p.district.toLowerCase().includes(q)) ||
          (p.province && p.province.toLowerCase().includes(q))
      );
    }

    // Arrival mode filter
    if (arrivalFilter !== 'all') {
      list = list.filter((p) => p.arrivalMode === arrivalFilter);
    }

    // Type filter (adult/pediatric) — uses age-based rule so records with
    // missing backend flag still classify correctly.
    if (typeFilter === 'adult') list = list.filter((p) => !isMinor(p));
    if (typeFilter === 'pediatric') list = list.filter((p) => isMinor(p));

    // Sort
    list.sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      if (sortBy === 'time') {
        return dir * (new Date(a.arrivalTimestamp).getTime() - new Date(b.arrivalTimestamp).getTime());
      }
      if (sortBy === 'name') {
        return dir * a.fullName.localeCompare(b.fullName);
      }
      if (sortBy === 'age') {
        return dir * (a.age - b.age);
      }
      return 0;
    });

    return list;
  }, [allPatients, search, arrivalFilter, typeFilter, sortBy, sortDir]);

  const toggleSort = (field: typeof sortBy) => {
    if (sortBy === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortBy(field);
      setSortDir('desc');
    }
  };

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  return (
    <div className="min-h-full animate-fade-in">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <Users className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-white tracking-tight leading-tight">
                  Patient Registry
                </h1>
                <p className="text-sm text-white/50 mt-0.5 font-medium">
                  {allPatients.length} total patients registered
                </p>
              </div>
            </div>

            <button
              onClick={() => navigate('/entry')}
              className="inline-flex items-center gap-2 px-5 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-sm font-bold transition-all duration-300 shadow-lg shadow-cyan-500/25 hover:-translate-y-1 hover:shadow-xl"
            >
              <UserPlus className="w-4 h-4" />
              New Patient
            </button>
          </div>
        </div>

        {/* ── Quick Summary Bar ── */}
        <div className="rounded-3xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.05s' }}>
          <div className="flex items-center gap-5 flex-wrap">
            {/* Total */}
            <div className="flex items-center gap-2.5">
              <div className="w-9 h-9 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <Users className="w-4 h-4 text-cyan-400" />
              </div>
              <div>
                <p className={`text-xl font-bold ${text.heading} leading-none`}>{stats.total}</p>
                <p className={`text-[10px] ${text.muted} font-bold uppercase tracking-wider`}>Total</p>
              </div>
            </div>

            <div className="w-px h-10" style={{ background: isDark ? 'rgba(2,132,199,0.18)' : 'rgba(203,213,225,0.5)' }} />

            {/* Adults */}
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={glassInner}>
                <Users className={`w-3.5 h-3.5 ${text.muted}`} />
              </div>
              <div>
                <p className={`text-base font-bold ${text.label} leading-none`}>{stats.adults}</p>
                <p className={`text-[10px] ${text.muted} font-semibold`}>Adults</p>
              </div>
            </div>

            {/* Pediatric */}
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg bg-pink-500/20 border border-pink-500/30 flex items-center justify-center">
                <Baby className="w-3.5 h-3.5 text-pink-400" />
              </div>
              <div>
                <p className={`text-base font-bold ${text.label} leading-none`}>{stats.pediatric}</p>
                <p className={`text-[10px] ${text.muted} font-semibold`}>Pediatric</p>
              </div>
            </div>

            <div className="w-px h-10" style={{ background: isDark ? 'rgba(2,132,199,0.18)' : 'rgba(203,213,225,0.5)' }} />

            {/* Arrival modes */}
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg" style={glassInner}>
                <span className="text-xs">🚶</span>
                <span className={`text-xs font-bold ${text.body}`}>{stats.walkIn}</span>
              </div>
              <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-red-500/20 border border-red-500/30">
                <span className="text-xs">🚑</span>
                <span className="text-xs font-bold text-red-400">{stats.ambulance}</span>
              </div>
              <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-indigo-500/20 border border-indigo-500/30">
                <span className="text-xs">🏥</span>
                <span className="text-xs font-bold text-indigo-400">{stats.referral}</span>
              </div>
            </div>

            <div className="flex-1" />

            {/* Live indicator */}
            <div className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span className={`text-[10px] ${text.muted} font-medium`}>Registry Live</span>
            </div>
          </div>
        </div>

        {/* ── Search & Filters ── */}
        <div className="flex flex-wrap items-center gap-3 animate-fade-up" style={{ animationDelay: '0.1s' }}>
          {/* Search */}
          <div className="relative flex-1 min-w-[240px] group">
            <Search className={`absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted} group-focus-within:text-cyan-500 transition-colors`} />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by name, ID, phone, or location..."
              className={`w-full pl-11 pr-4 py-2.5 rounded-xl text-sm ${text.label} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all`}
              style={glassInner}
            />
          </div>

          {/* Arrival Mode Filter */}
          <div className="relative">
            <Siren className={`absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 ${text.muted} pointer-events-none`} />
            <select
              value={arrivalFilter}
              onChange={(e) => setArrivalFilter(e.target.value)}
              className={`appearance-none pl-9 pr-8 py-2.5 rounded-xl text-xs font-semibold ${text.body} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 cursor-pointer`}
              style={glassInner}
            >
              <option value="all">All Arrivals</option>
              <option value="WALK_IN">🚶 Walk-in</option>
              <option value="AMBULANCE">🚑 Ambulance</option>
              <option value="REFERRAL">🏥 Referral</option>
            </select>
            <ChevronDown className={`absolute right-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 ${text.muted} pointer-events-none`} />
          </div>

          {/* Type Filter */}
          <div className="flex items-center gap-1.5">
            <Filter className={`w-3.5 h-3.5 ${text.muted}`} />
            {(['all', 'adult', 'pediatric'] as const).map((f) => (
              <button
                key={f}
                onClick={() => setTypeFilter(f)}
                className={`px-3 py-2 rounded-xl text-[11px] font-bold transition-all duration-300 ${
                  typeFilter === f
                    ? 'bg-cyan-600 text-white shadow-md shadow-cyan-500/20'
                    : `${text.body} hover:${text.label}`
                }`}
                style={typeFilter !== f ? glassInner : {}}
              >
                {f === 'all' ? 'All' : f === 'adult' ? 'Adults' : 'Pediatric'}
              </button>
            ))}
          </div>

          {/* Sort */}
          <button
            onClick={() => toggleSort(sortBy === 'time' ? 'name' : sortBy === 'name' ? 'age' : 'time')}
            className={`flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-xs font-semibold ${text.body} hover:${text.label} transition-all`}
            style={glassInner}
          >
            <ArrowUpDown className="w-3.5 h-3.5" />
            {sortBy === 'time' ? 'Time' : sortBy === 'name' ? 'Name' : 'Age'}
          </button>
        </div>

        {/* ── Patient List ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={{ ...glassCard, animationDelay: '0.15s' }}>
          {/* Header bar */}
          <div className="px-5 py-3.5 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
            <div className="flex items-center gap-2">
              <span className={`text-[11px] font-bold uppercase tracking-wider ${text.muted}`}>Patient Records</span>
              <span
                className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg text-cyan-600"
                style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}
              >
                {filtered.length}
              </span>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span className={`text-[10px] ${text.muted} font-medium`}>Live</span>
            </div>
          </div>

          {/* Rows */}
          {filtered.length === 0 ? (
            <div className="px-6 py-16 text-center">
              <Users className={`w-12 h-12 ${text.muted} opacity-50 mx-auto mb-3`} />
              <p className={`text-sm font-semibold ${text.muted}`}>No patients found</p>
              <p className={`text-xs ${text.muted} mt-1`}>Try adjusting your search or filters</p>
            </div>
          ) : (
            <div className="divide-y" style={{ borderColor: isDark ? 'rgba(2,132,199,0.15)' : 'rgba(203,213,225,0.15)' }}>
              {filtered.map((patient) => {
                const arrivalMode = arrivalModeConfig[patient.arrivalMode];
                const peds = isMinor(patient);

                return (
                  <div
                    key={patient.id}
                    className="px-5 py-4 transition-all duration-300 group cursor-pointer hover:bg-white/[0.03] hover:-translate-y-0.5"
                    onClick={() => navigate(chartPath(patient.id))}
                  >
                    {/* Top row: Avatar + Name + Age + Arrival + Arrow */}
                    <div className="flex items-center gap-3">
                      {/* Avatar */}
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center text-xs font-bold text-white flex-shrink-0 shadow-md"
                        style={{
                          background: peds
                            ? 'linear-gradient(135deg, #f472b6, #ec4899)'
                            : 'linear-gradient(135deg, #64748b, #475569)',
                        }}
                      >
                        {patient.fullName.split(' ').map(n => n[0]).join('').slice(0, 2)}
                      </div>

                      {/* Name + sub */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <p className={`text-sm font-semibold ${text.label} truncate group-hover:text-cyan-500 transition-colors`}>
                            {patient.fullName}
                          </p>
                          {peds && (
                            <span
                              className="inline-flex items-center gap-0.5 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-violet-600 flex-shrink-0"
                              style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}
                            >
                              <Baby className="w-2.5 h-2.5" />
                              PEDS
                            </span>
                          )}
                        </div>
                        <p className={`text-[11px] ${text.muted} font-medium mt-0.5`}>
                          {patient.age < 1 ? `${Math.round(patient.age * 12)}mo` : `${patient.age}y`} · {patient.gender === 'MALE' ? 'Male' : patient.gender === 'FEMALE' ? 'Female' : '—'}
                        </p>
                      </div>

                      {/* Arrival badge — Pathways translucent style, semantic hue per arrival mode */}
                      <span
                        className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg text-[10px] font-bold flex-shrink-0"
                        style={(() => {
                          const mode = patient.arrivalMode;
                          const palettes = {
                            WALK_IN: { bg: 'rgba(100,116,139,0.08)', color: '#475569', border: 'rgba(100,116,139,0.2)' },
                            AMBULANCE: { bg: 'rgba(239,68,68,0.08)', color: '#dc2626', border: 'rgba(239,68,68,0.2)' },
                            REFERRAL: { bg: 'rgba(59,130,246,0.08)', color: '#2563eb', border: 'rgba(59,130,246,0.2)' },
                          } as const;
                          const palette = palettes[mode as keyof typeof palettes] ?? palettes.WALK_IN;
                          return { background: palette.bg, color: palette.color, border: `1px solid ${palette.border}` };
                        })()}
                      >
                        <span>{arrivalMode.icon}</span>
                        <span className="hidden sm:inline">{arrivalMode.label}</span>
                      </span>

                      {/* Arrow */}
                      <ChevronRightIcon className={`w-4 h-4 ${text.muted} group-hover:text-cyan-500 transition-colors flex-shrink-0`} />
                    </div>

                    {/* Shift-handoff priority badges — pending labs, pending
                        meds, critical results back, open ICU escalation.
                        Self-hides when this patient has nothing outstanding. */}
                    <div className="mt-2 ml-[52px]">
                      <HandoffPriorityBadges signals={patient} />
                    </div>

                    {/* Bottom row: Detail pills — responsive wrap */}
                    <div className="flex items-center gap-x-4 gap-y-1 flex-wrap mt-2 ml-[52px]">
                      {/* Current ED location — zone + bed — so staff know
                          where to physically find the patient. Only render
                          when a location is known (the name is already shown
                          above, so this row is purely the "where"). */}
                      {(patient.currentEdZone || patient.currentBedLabel) && (
                        <div className="flex items-center gap-1.5">
                          <MapPin className={`w-3 h-3 ${text.muted} opacity-70 flex-shrink-0`} />
                          <span className={`text-[11px] ${text.body}`}>
                            {patient.currentEdZone || 'Zone —'}
                            {patient.currentBedLabel ? ` · Bed ${patient.currentBedLabel}` : ''}
                          </span>
                        </div>
                      )}

                      {/* National ID — hidden from list view for privacy; visible on detail page */}
                      {patient.nationalId && (
                        <div className="flex items-center gap-1.5" title="National ID on file">
                          <CreditCard className={`w-3 h-3 ${text.muted} opacity-70 flex-shrink-0`} />
                          <span className={`text-[11px] font-mono ${text.body}`}>
                            &bull;&bull;&bull;&bull; {patient.nationalId.slice(-4)}
                          </span>
                        </div>
                      )}

                      {/* Phone */}
                      {patient.phone && (
                        <div className="flex items-center gap-1.5">
                          <Phone className={`w-3 h-3 ${text.muted} opacity-70 flex-shrink-0`} />
                          <span className={`text-[11px] ${text.body}`}>{patient.phone}</span>
                        </div>
                      )}

                      {/* Location */}
                      {(patient.district || patient.province) && (
                        <div className="flex items-center gap-1.5">
                          <MapPin className={`w-3 h-3 ${text.muted} opacity-70 flex-shrink-0`} />
                          <span className={`text-[11px] ${text.body}`}>
                            {patient.district && patient.province
                              ? `${patient.district}, ${patient.province}`
                              : patient.province || patient.district}
                          </span>
                        </div>
                      )}

                      {/* Registered time */}
                      <div className="flex items-center gap-1.5">
                        <Calendar className={`w-3 h-3 ${text.muted} opacity-70 flex-shrink-0`} />
                        <span className={`text-[10px] ${text.muted} font-medium`}>
                          {formatDate(patient.registeredAt || patient.arrivalTimestamp)}
                        </span>
                      </div>

                      {/* Referring facility */}
                      {patient.referringFacility && (
                        <span
                          className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-lg text-[9px] font-bold text-blue-600"
                          style={{ background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)' }}
                        >
                          From: {patient.referringFacility}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Footer */}
          <div className={`px-5 py-3 flex items-center justify-between text-xs ${text.muted} font-medium`} style={{ borderTop: borderStyle }}>
            <span>Showing {filtered.length} of {allPatients.length} patients</span>
            <span className={`text-[10px] ${text.muted}`}>Sorted by {sortBy === 'time' ? 'registration time' : sortBy === 'name' ? 'name' : 'age'}</span>
          </div>
        </div>
      </div>
    </div>
  );
}
