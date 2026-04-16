import { useState, useMemo, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Users, Search, Clock, AlertTriangle, CheckCircle,
  UserPlus, Filter, Stethoscope, Activity,
} from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { useAuthStore } from '@/store/authStore';
import type { Patient } from '@/types';

const categoryColor: Record<string, string> = {
  RED: 'bg-red-500',
  ORANGE: 'bg-orange-500',
  YELLOW: 'bg-yellow-400',
  GREEN: 'bg-green-500',
  BLUE: 'bg-blue-500',
};

const categoryBg: Record<string, string> = {
  RED: 'bg-red-50 text-red-700 border-red-200',
  ORANGE: 'bg-orange-50 text-orange-700 border-orange-200',
  YELLOW: 'bg-yellow-50 text-yellow-700 border-yellow-200',
  GREEN: 'bg-green-50 text-green-700 border-green-200',
  BLUE: 'bg-blue-50 text-blue-700 border-blue-200',
};

const statusBg: Record<string, string> = {
  WAITING: 'bg-gray-100 text-gray-700',
  IN_TRIAGE: 'bg-accent-100 text-accent-600',
  TRIAGED: 'bg-green-100 text-green-700',
  IN_TREATMENT: 'bg-blue-100 text-blue-700',
};

function formatWait(ts: Date): string {
  const mins = Math.floor((Date.now() - ts.getTime()) / 60000);
  if (mins < 60) return `${mins}m`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ${mins % 60}m`;
}

export function AdultTriageQueue() {
  const navigate = useNavigate();
  const storePatients = usePatientStore((s) => s.patients);
  const fetchActiveVisits = usePatientStore((s) => s.fetchActiveVisits);
  const user = useAuthStore((s) => s.user);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');

  useEffect(() => {
    fetchActiveVisits(user?.hospitalId || '');
  }, [fetchActiveVisits, user?.hospitalId]);

  const allPatients = useMemo(() =>
    storePatients.filter((p) => !p.isPediatric || p.age >= 12),
  [storePatients]);

  const filtered = useMemo(() => {
    return allPatients.filter((p) => {
      if (statusFilter !== 'all' && p.triageStatus !== statusFilter) return false;
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        return p.fullName.toLowerCase().includes(q) || p.chiefComplaint.toLowerCase().includes(q) || p.id.toLowerCase().includes(q);
      }
      return true;
    });
  }, [allPatients, searchQuery, statusFilter]);

  const stats = useMemo(() => ({
    total: allPatients.length,
    waiting: allPatients.filter((p) => p.triageStatus === 'WAITING').length,
    inTriage: allPatients.filter((p) => p.triageStatus === 'IN_TRIAGE').length,
    triaged: allPatients.filter((p) => p.triageStatus === 'TRIAGED').length,
    critical: allPatients.filter((p) => p.category === 'RED').length,
  }), [allPatients]);

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="bg-white/90 backdrop-blur-xl rounded-2xl border border-primary-100/50 shadow-sm overflow-hidden animate-fade-up">
          <div className="bg-gradient-to-r from-accent-600 to-accent-500 px-6 py-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-white/20 rounded-xl flex items-center justify-center">
                <Stethoscope className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-base font-bold text-white tracking-wide">Adult Triage Queue</h1>
                <p className="text-white/90 text-xs">Ages 12+ — Emergency Department</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                <Users className="w-4 h-4 text-white" />
                <span className="text-xs font-semibold text-white">ADULT</span>
              </div>
              <button
                onClick={() => navigate('/adult-triage/new')}
                className="bg-white text-accent-600 px-4 py-2 rounded-xl text-xs font-bold hover:bg-accent-100 transition-all flex items-center gap-2 shadow-sm"
              >
                <UserPlus className="w-4 h-4" />
                New Triage
              </button>
            </div>
          </div>

          {/* Age Banner */}
          <div className="bg-accent-100 border-b border-accent-300 px-6 py-2 flex items-center gap-2">
            <div className="w-5 h-5 bg-accent-200 rounded-full flex items-center justify-center">
              <Users className="w-3 h-3 text-accent-600" />
            </div>
            <span className="text-xs font-semibold text-accent-600">Adult patient is from age 12 and above</span>
          </div>
        </div>

        {/* Stats Cards */}
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          {[
            { label: 'Total', value: stats.total, icon: Users, iconBg: 'bg-gradient-to-br from-gray-500 to-gray-600' },
            { label: 'Waiting', value: stats.waiting, icon: Clock, iconBg: 'bg-gradient-to-br from-amber-500 to-amber-600' },
            { label: 'In Triage', value: stats.inTriage, icon: Activity, iconBg: 'bg-gradient-to-br from-accent-600 to-accent-500' },
            { label: 'Triaged', value: stats.triaged, icon: CheckCircle, iconBg: 'bg-gradient-to-br from-emerald-500 to-emerald-600' },
            { label: 'Critical', value: stats.critical, icon: AlertTriangle, iconBg: 'bg-gradient-to-br from-red-500 to-red-600' },
          ].map((s) => (
            <div key={s.label} className="bg-white/90 backdrop-blur-xl relative rounded-2xl border border-primary-100/50 shadow-sm hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-500 overflow-hidden group animate-fade-up" style={{ animationDelay: `${0.05 * ['Total', 'Waiting', 'In Triage', 'Triaged', 'Critical'].indexOf(s.label)}s` }}>
              <div className="p-4">
                <div className={`w-10 h-10 rounded-full ${s.iconBg} flex items-center justify-center mb-3 shadow-sm`}>
                  <s.icon className="w-5 h-5 text-white" />
                </div>
                <p className="text-lg font-bold text-gray-900 mb-1">{s.value}</p>
                <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide">{s.label}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Search & Filter */}
        <div className="bg-white/90 backdrop-blur-xl rounded-2xl border border-primary-100/50 shadow-sm p-5 animate-fade-up" style={{ animationDelay: '0.3s' }}>
          <div className="flex items-center gap-3">
            <div className="flex-1 relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by name, complaint, or ID..."
                className="w-full pl-10 pr-4 py-2.5 bg-white/80 backdrop-blur-sm border-2 border-gray-200/60 rounded-2xl text-sm focus:outline-none focus:ring-4 focus:ring-accent-600/10 focus:border-accent-600 focus:shadow-sm transition-all duration-300 shadow-sm"
              />
            </div>
            <div className="flex items-center gap-1.5">
              <Filter className="w-4 h-4 text-gray-400" />
              {['all', 'WAITING', 'IN_TRIAGE', 'TRIAGED'].map((f) => (
                <button
                  key={f}
                  onClick={() => setStatusFilter(f)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${statusFilter === f ? 'bg-gradient-to-r from-accent-600 to-primary-800 text-white shadow-lg shadow-accent-600/20' : 'bg-white/60 backdrop-blur-sm text-gray-600 hover:bg-white/80 border border-white/60 shadow-sm'
                    }`}
                >
                  {f === 'all' ? 'All' : f === 'IN_TRIAGE' ? 'In Triage' : f === 'WAITING' ? 'Waiting' : 'Triaged'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Patient Queue Table */}
        <div className="bg-white/90 backdrop-blur-xl rounded-2xl border border-primary-100/50 shadow-sm p-4 animate-fade-up" style={{ animationDelay: '0.35s' }}>
          <div className="flex items-center justify-between mb-3">
            <div>
              <h2 className="text-base font-bold text-gray-900 tracking-tight">Patient Queue</h2>
              <p className="text-sm text-gray-500">{filtered.length} patient{filtered.length !== 1 ? 's' : ''} shown</p>
            </div>
            <Activity className="w-5 h-5 text-accent-600" />
          </div>

          <div className="space-y-3">
            {filtered
              .sort((a, b) => {
                // Waiting first, then in-triage, then triaged
                const order: Record<string, number> = { WAITING: 0, IN_TRIAGE: 1, TRIAGED: 2, IN_TREATMENT: 3 };
                const diff = (order[a.triageStatus] ?? 4) - (order[b.triageStatus] ?? 4);
                if (diff !== 0) return diff;
                return a.arrivalTimestamp.getTime() - b.arrivalTimestamp.getTime();
              })
              .map((patient) => (
                <div
                  key={patient.id}
                  className="bg-white/60 backdrop-blur-sm rounded-2xl p-4 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-500 border border-white/60 flex items-center gap-3 group"
                >
                  {/* Category indicator */}
                  <div
                    className={`w-1.5 h-10 rounded-full flex-shrink-0 shadow-sm ${patient.category ? categoryColor[patient.category] : 'bg-gray-300'}`}
                  />

                  {/* Patient info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-gray-900 text-sm">{patient.fullName}</span>
                      <span className="text-[10px] text-gray-400 font-medium">{patient.age}y, {patient.gender === 'MALE' ? 'M' : 'F'}</span>
                    </div>
                    <p className="text-xs text-gray-500 mt-0.5 truncate">{patient.chiefComplaint}</p>
                  </div>

                  {/* Badges */}
                  <div className="flex items-center gap-2 flex-shrink-0">
                    {patient.tewsScore !== undefined && (
                      <span className="text-[11px] font-bold bg-gray-100 text-gray-600 px-2 py-1 rounded-lg">
                        TEWS {patient.tewsScore}
                      </span>
                    )}
                    {patient.category && (
                      <span className={`text-[11px] font-bold px-2 py-1 rounded-lg border ${categoryBg[patient.category]}`}>
                        {patient.category}
                      </span>
                    )}
                    <span className={`text-[11px] font-bold px-2 py-1 rounded-lg ${statusBg[patient.triageStatus]}`}>
                      {patient.triageStatus === 'IN_TRIAGE' ? 'In Triage' : patient.triageStatus === 'IN_TREATMENT' ? 'In Treatment' : patient.triageStatus.replace('_', ' ')}
                    </span>
                    <span className="text-[11px] text-gray-400 font-medium min-w-[55px] text-right flex items-center gap-1">
                      <Clock className="w-3 h-3" />
                      {formatWait(patient.arrivalTimestamp)}
                    </span>
                  </div>
                </div>
              ))}

            {filtered.length === 0 && (
              <div className="text-center py-12 text-gray-400">
                <Users className="w-10 h-10 mx-auto mb-3 opacity-30" />
                <p className="text-sm font-medium">No patients match your search</p>
              </div>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}
