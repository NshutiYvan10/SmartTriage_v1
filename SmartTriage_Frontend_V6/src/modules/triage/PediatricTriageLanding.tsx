import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Baby, Search, UserPlus, AlertTriangle, ChevronRight, Shield, Activity,
} from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';

export function PediatricTriageLanding() {
  const navigate = useNavigate();
  const patients = usePatientStore((state) => state.patients);
  const [searchQuery, setSearchQuery] = useState('');

  // Filter to pediatric patients (age ≤ 3) who are not yet triaged
  const pediatricPatients = patients.filter(
    (p) => p.isPediatric && p.age <= 3
  );

  const filteredPatients = pediatricPatients.filter((p) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      p.fullName.toLowerCase().includes(q) ||
      p.chiefComplaint.toLowerCase().includes(q) ||
      p.id.toLowerCase().includes(q)
    );
  });

  const waitingPatients = filteredPatients.filter((p) => p.triageStatus === 'WAITING');

  return (
    <div className="min-h-full">
      <div className="p-6 max-w-6xl mx-auto space-y-5">

        {/* Header */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
          <div className="bg-gradient-to-r from-accent-600 to-accent-500 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center">
                  <Baby className="w-7 h-7 text-white" />
                </div>
                <div>
                  <h1 className="text-xl font-bold text-white">Pediatric Triage</h1>
                  <p className="text-white/90 text-sm">Infant Triage Form (Ages 0–3 Years)</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="bg-white/15 backdrop-blur rounded-xl px-4 py-2 text-center">
                  <p className="text-2xl font-bold text-white">{pediatricPatients.length}</p>
                  <p className="text-[10px] text-white/80 uppercase tracking-wider">Infants Today</p>
                </div>
                <div className="bg-white/15 backdrop-blur rounded-xl px-4 py-2 text-center">
                  <p className="text-2xl font-bold text-white">{waitingPatients.length}</p>
                  <p className="text-[10px] text-white/80 uppercase tracking-wider">Awaiting Triage</p>
                </div>
              </div>
            </div>
          </div>

          {/* Search & Actions */}
          <div className="px-6 py-4 flex items-center justify-between gap-4">
            <div className="relative flex-1 max-w-md">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search infant patients..."
                className="w-full pl-10 pr-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-accent-600/30 focus:border-accent-600"
              />
            </div>
            <button
              onClick={() => navigate('/entry')}
              className="px-4 py-2.5 bg-accent-600 text-white rounded-xl text-sm font-semibold hover:bg-accent-500 transition-all shadow-sm flex items-center gap-2"
            >
              <UserPlus className="w-4 h-4" />
              Register New Infant
            </button>
          </div>
        </div>

        {/* Info cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-4 flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-red-100 flex items-center justify-center flex-shrink-0">
              <AlertTriangle className="w-5 h-5 text-red-600" />
            </div>
            <div>
              <p className="text-sm font-bold text-gray-900">Emergency Protocol</p>
              <p className="text-xs text-gray-500">Any emergency sign → RED (immediate). SpO₂ &lt; 92% → RED.</p>
            </div>
          </div>
          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-4 flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-accent-100 flex items-center justify-center flex-shrink-0">
              <Activity className="w-5 h-5 text-accent-600" />
            </div>
            <div>
              <p className="text-sm font-bold text-gray-900">TEWS Auto-Scoring</p>
              <p className="text-xs text-gray-500">Scores calculate in real-time as vitals are entered.</p>
            </div>
          </div>
          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-4 flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-amber-100 flex items-center justify-center flex-shrink-0">
              <Shield className="w-5 h-5 text-amber-600" />
            </div>
            <div>
              <p className="text-sm font-bold text-gray-900">Age-Adjusted Ranges</p>
              <p className="text-xs text-gray-500">Vital sign thresholds adjust for infant age (0–3 months, 3–12 months, 1–3 years).</p>
            </div>
          </div>
        </div>

        {/* Patient List */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
            <h2 className="text-base font-bold text-gray-900">Infant Patients Queue</h2>
            <span className="text-xs text-gray-500">
              {`${filteredPatients.length} patient${filteredPatients.length !== 1 ? 's' : ''}`}
            </span>
          </div>

          {/* Patient list */}
          {filteredPatients.length > 0 && (
            <div className="divide-y divide-gray-100">
              {filteredPatients.map((patient) => {
                const statusStyle: Record<string, string> = {
                  WAITING: 'bg-orange-100 text-orange-700',
                  IN_TRIAGE: 'bg-accent-100 text-accent-600',
                  TRIAGED: 'bg-green-100 text-green-700',
                };
                return (
                  <div
                    key={patient.id}
                    onClick={() => navigate(`/pediatric-triage/${patient.id}`)}
                    className="px-6 py-4 flex items-center gap-4 hover:bg-gray-50 cursor-pointer transition-all duration-300 group hover:-translate-y-0.5"
                  >
                    <div className="w-10 h-10 rounded-xl bg-accent-100/50 flex items-center justify-center flex-shrink-0">
                      <Baby className="w-5 h-5 text-accent-600" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-semibold text-gray-900">{patient.fullName}</p>
                      <p className="text-xs text-gray-500 truncate">{patient.age}y, {patient.gender} · {patient.chiefComplaint}</p>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <span className={`text-[11px] font-bold px-2.5 py-1 rounded-lg ${statusStyle[patient.triageStatus] || 'bg-gray-100 text-gray-600'}`}>
                        {patient.triageStatus.replace('_', ' ')}
                      </span>
                      {patient.category && (
                        <span className={`text-[11px] font-bold px-2.5 py-1 rounded-lg ${
                          patient.category === 'RED' ? 'bg-red-100 text-red-700' :
                          patient.category === 'ORANGE' ? 'bg-orange-100 text-orange-700' :
                          patient.category === 'YELLOW' ? 'bg-yellow-100 text-yellow-700' :
                          'bg-green-100 text-green-700'
                        }`}>
                          {patient.category}
                        </span>
                      )}
                      <ChevronRight className="w-4 h-4 text-gray-300 group-hover:text-accent-600 transition-colors" />
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Empty state */}
          {filteredPatients.length === 0 && (
            <div className="py-16 text-center">
              <Baby className="w-10 h-10 text-gray-300 mx-auto mb-3" />
              <p className="text-sm font-medium text-gray-500">No infant patients found</p>
              <p className="text-xs text-gray-400 mt-1">Register a new patient aged 0–3 years to begin.</p>
              <button
                onClick={() => navigate('/entry')}
                className="mt-4 px-4 py-2 bg-accent-600 text-white rounded-xl text-xs font-semibold hover:bg-accent-500 transition-all"
              >
                Register Infant
              </button>
            </div>
          )}
        </div>

      </div>
    </div>
  );
}
