import { useState } from 'react';
import { AlertTriangle, CheckCircle, Filter, Search, ChevronDown } from 'lucide-react';
import { useAlertStore } from '@/store/alertStore';
import { usePatientStore } from '@/store/patientStore';
import { formatDistanceToNow } from 'date-fns';
import { AIAlert } from '@/types';

export function AlertsView() {
  const alerts = useAlertStore((state) => state.alerts);
  const acknowledgeAlert = useAlertStore((state) => state.acknowledgeAlert);
  const patients = usePatientStore((state) => state.patients);
  const [filter, setFilter] = useState<'all' | 'active' | 'acknowledged'>('all');
  const [severityFilter, setSeverityFilter] = useState<'all' | 'CRITICAL' | 'HIGH' | 'MEDIUM'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedAlert, setExpandedAlert] = useState<string | null>(null);

  const filteredAlerts = alerts.filter((alert) => {
    if (filter === 'active') return !alert.acknowledgedAt;
    if (filter === 'acknowledged') return !!alert.acknowledgedAt;
    return true;
  }).filter((alert) => {
    if (severityFilter !== 'all') return alert.severity === severityFilter;
    return true;
  }).filter((alert) => {
    if (searchQuery.trim()) {
      return alert.message.toLowerCase().includes(searchQuery.toLowerCase());
    }
    return true;
  });

  const getSeverityColor = (severity: AIAlert['severity']) => {
    switch (severity) {
      case 'CRITICAL':
        return { bg: 'bg-red-50', border: 'border-red-500', text: 'text-red-700', icon: 'text-red-600', badge: 'bg-red-100 text-red-700' };
      case 'HIGH':
        return { bg: 'bg-orange-50', border: 'border-orange-500', text: 'text-orange-700', icon: 'text-orange-600', badge: 'bg-orange-100 text-orange-700' };
      case 'MEDIUM':
        return { bg: 'bg-yellow-50', border: 'border-yellow-500', text: 'text-yellow-700', icon: 'text-yellow-600', badge: 'bg-yellow-100 text-yellow-700' };
      default:
        return { bg: 'bg-gray-50', border: 'border-gray-300', text: 'text-gray-700', icon: 'text-gray-600', badge: 'bg-gray-100 text-gray-700' };
    }
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-5 space-y-4">

        {/* ── Header Card ── */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-extrabold text-gray-900">AI Alert Management</h1>
              <p className="text-gray-500 text-sm mt-1 flex items-center gap-2">
                <span className="w-2 h-2 bg-red-500 rounded-full animate-pulse" />
                Real-time automated monitoring and intelligent alerts
              </p>
            </div>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search alerts..."
                className="pl-10 pr-4 py-2.5 w-64 bg-gray-50 border border-gray-200 rounded-xl text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
              />
            </div>
          </div>
        </div>



        {/* ── Filter Card ── */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-5">
          <div className="flex items-center gap-4">
            <div className="w-10 h-10 bg-cyan-50 rounded-xl flex items-center justify-center">
              <Filter className="w-5 h-5 text-cyan-600" />
            </div>
            <div className="flex gap-2 flex-1">
              {(['all', 'active', 'acknowledged'] as const).map((filterType) => (
                <button
                  key={filterType}
                  onClick={() => setFilter(filterType)}
                  className={`px-5 py-2.5 rounded-xl text-sm font-semibold transition-all duration-200 ${
                    filter === filterType
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-sm'
                      : 'bg-gray-50 text-gray-700 hover:bg-gray-100 border border-gray-200'
                  }`}
                >
                  {filterType.charAt(0).toUpperCase() + filterType.slice(1)}
                </button>
              ))}
            </div>
            <div className="relative min-w-[140px]">
              <select
                value={severityFilter}
                onChange={(e) => setSeverityFilter(e.target.value as any)}
                className="w-full appearance-none bg-gray-50 border border-gray-200 rounded-xl px-4 py-2.5 pr-10 text-sm font-medium text-gray-700 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
              >
                <option value="all">All Severity</option>
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High</option>
                <option value="MEDIUM">Medium</option>
              </select>
              <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 pointer-events-none" />
            </div>
          </div>
        </div>

        {/* ── Alerts List ── */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div className="flex items-center justify-between mb-5">
            <div>
              <h3 className="text-sm font-bold text-gray-900">AI Alerts</h3>
              <p className="text-sm text-gray-500">{filteredAlerts.length} alert{filteredAlerts.length !== 1 ? 's' : ''} shown</p>
            </div>
            <AlertTriangle className="w-5 h-5 text-cyan-600" />
          </div>

          {filteredAlerts.length === 0 ? (
            <div className="text-center py-12">
              <CheckCircle className="w-10 h-10 text-gray-300 mx-auto mb-3" />
              <h3 className="text-sm font-semibold text-gray-900 mb-1">No Alerts</h3>
              <p className="text-xs text-gray-500">
                {filter === 'all' && 'No alerts in the system'}
                {filter === 'active' && 'No active alerts at this time'}
                {filter === 'acknowledged' && 'No acknowledged alerts'}
              </p>
            </div>
          ) : (
            <div className="space-y-3">
            {filteredAlerts
              .sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime())
              .map((alert) => {
                const colors = getSeverityColor(alert.severity);
                const patient = patients.find((p) => p.id === alert.patientId);
                const isExpanded = expandedAlert === alert.id;

                return (
                  <div
                    key={alert.id}
                    className={`rounded-xl p-4 cursor-pointer border-l-4 border border-gray-100 bg-white hover:bg-gray-50 transition-all duration-200 ${
                      colors.border
                    } ${alert.acknowledgedAt ? 'opacity-60' : ''}`}
                    onClick={() => setExpandedAlert(isExpanded ? null : alert.id)}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex-1 min-w-0">
                        <p className={`text-sm font-semibold leading-snug ${colors.text}`}>{alert.message}</p>
                        <div className="flex items-center gap-2 mt-1.5">
                          {patient && (
                            <span className="text-xs text-gray-500 font-medium">{patient.fullName}</span>
                          )}
                          {patient && <span className="text-gray-300">·</span>}
                          <span className="text-xs text-gray-400">{formatDistanceToNow(alert.timestamp, { addSuffix: true })}</span>
                        </div>
                      </div>
                      <div className="flex items-center gap-1.5 flex-shrink-0">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${colors.badge}`}>
                          {alert.severity}
                        </span>
                        {alert.acknowledgedAt && (
                          <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-green-100 text-green-700">
                            RESOLVED
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Expanded Details */}
                    {isExpanded && (
                      <div className="border-t border-gray-100 pt-4 mt-4">
                        {/* Contributing Factors */}
                        {alert.contributingFactors && alert.contributingFactors.length > 0 && (
                          <div className="mb-4">
                            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wide mb-2">Contributing Factors</h4>
                            <div className="flex flex-wrap gap-2">
                              {alert.contributingFactors.map((factor, i) => (
                                <span key={i} className="text-xs bg-gray-50 text-gray-700 px-3 py-1.5 rounded-lg border border-gray-200">
                                  {factor}
                                </span>
                              ))}
                            </div>
                          </div>
                        )}

                        {/* Category Change */}
                        {alert.previousCategory && alert.recommendedCategory && (
                          <div className="mb-4 flex items-center gap-2">
                            <span className="text-xs font-bold text-gray-500 uppercase tracking-wide">Category Change:</span>
                            <span className="text-xs font-bold px-2 py-1 rounded-lg bg-yellow-100 text-yellow-700">{alert.previousCategory}</span>
                            <span className="text-xs text-gray-400">→</span>
                            <span className="text-xs font-bold px-2 py-1 rounded-lg bg-red-100 text-red-700">{alert.recommendedCategory}</span>
                          </div>
                        )}

                        {/* Acknowledge Info or Button */}
                        {alert.acknowledgedAt ? (
                          <div className="flex items-center gap-2 text-sm text-gray-600 bg-green-50 rounded-xl p-4 border border-green-100">
                            <CheckCircle className="w-4 h-4 text-green-600 flex-shrink-0" />
                            <div>
                              <span className="font-medium">Acknowledged by {alert.acknowledgedBy}</span>
                              {alert.acknowledgedAt && <span className="text-gray-400"> · {formatDistanceToNow(alert.acknowledgedAt, { addSuffix: true })}</span>}
                              {alert.comment && <p className="text-xs text-gray-500 mt-1">{alert.comment}</p>}
                            </div>
                          </div>
                        ) : (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              const comment = prompt('Add a comment (optional):');
                              acknowledgeAlert(alert.id, 'DR001', comment || undefined);
                            }}
                            className="px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl hover:from-slate-700 hover:to-slate-600 transition-all text-sm font-semibold shadow-sm"
                          >
                            Acknowledge Alert
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            }
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
