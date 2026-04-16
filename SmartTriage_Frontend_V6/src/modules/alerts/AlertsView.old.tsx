import { useState } from 'react';
import { AlertCircle, AlertTriangle, Clock, CheckCircle, Shield, Search, Eye, Activity } from 'lucide-react';
import { useAlertStore } from '@/store/alertStore';
import { usePatientStore } from '@/store/patientStore';
import { formatDistanceToNow } from 'date-fns';
import { AIAlert } from '@/types';

export function AlertsView() {
  const alerts = useAlertStore((state) => state.alerts);
  const acknowledgeAlert = useAlertStore((state) => state.acknowledgeAlert);
  const patients = usePatientStore((state) => state.patients);
  const [filter, setFilter] = useState<'all' | 'active' | 'acknowledged'>('all');
  const [searchQuery, setSearchQuery] = useState('');

  // Mock alerts for demonstration
  const mockAlerts: AIAlert[] = alerts.length === 0 ? [
    {
      id: 'alert-1',
      patientId: '1',
      timestamp: new Date(Date.now() - 300000),
      type: 'DETERIORATION',
      severity: 'CRITICAL',
      message: 'Patient showing signs of deterioration - TEWS score increased from 3 to 7',
      previousCategory: 'YELLOW',
      recommendedCategory: 'RED',
      contributingFactors: ['Increased respiratory rate', 'Dropping SpO2', 'Elevated heart rate'],
      acknowledged: false,
    },
    {
      id: 'alert-2',
      patientId: '2',
      timestamp: new Date(Date.now() - 600000),
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: 'Pediatric patient temperature exceeds critical threshold (39.5°C)',
      contributingFactors: ['High fever (39.5°C)', 'Age < 15 years'],
      acknowledged: false,
    },
    {
      id: 'alert-3',
      patientId: '3',
      timestamp: new Date(Date.now() - 900000),
      type: 'TREND_WARNING',
      severity: 'MEDIUM',
      message: 'Declining blood pressure trend detected over last 30 minutes',
      contributingFactors: ['Systolic BP decreasing', 'Patient reports dizziness'],
      acknowledged: true,
      acknowledgedBy: 'Dr. Smith',
      acknowledgedAt: new Date(Date.now() - 600000),
      comment: 'IV fluids administered, monitoring continues',
    },
    {
      id: 'alert-4',
      patientId: '4',
      timestamp: new Date(Date.now() - 1800000),
      type: 'THRESHOLD_BREACH',
      severity: 'HIGH',
      message: 'Patient oxygen saturation below 90% - immediate intervention needed',
      contributingFactors: ['SpO2: 87%', 'Respiratory distress symptoms'],
      acknowledged: true,
      acknowledgedBy: 'Dr. Johnson',
      acknowledgedAt: new Date(Date.now() - 1500000),
      comment: 'Oxygen therapy started at 4L/min',
    },
  ] : alerts;

  const filteredAlerts = mockAlerts.filter((alert) => {
    if (filter === 'active') return !alert.acknowledgedAt;
    if (filter === 'acknowledged') return !!alert.acknowledgedAt;
    return true;
  }).filter((alert) => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return alert.message.toLowerCase().includes(q) || alert.type.toLowerCase().includes(q);
  });

  const stats = {
    total: mockAlerts.length,
    active: mockAlerts.filter((a) => !a.acknowledgedAt).length,
    acknowledged: mockAlerts.filter((a) => !!a.acknowledgedAt).length,
    critical: mockAlerts.filter((a) => a.severity === 'CRITICAL' && !a.acknowledgedAt).length,
  };

  const getSeverityConfig = (severity: AIAlert['severity']) => {
    switch (severity) {
      case 'CRITICAL':
        return { bg: 'bg-red-50', border: 'border-red-200', iconBg: 'bg-red-100', text: 'text-red-700', icon: 'text-red-600', badge: 'bg-red-100 text-red-700' };
      case 'HIGH':
        return { bg: 'bg-amber-50', border: 'border-amber-200', iconBg: 'bg-amber-100', text: 'text-amber-700', icon: 'text-amber-600', badge: 'bg-amber-100 text-amber-700' };
      case 'MEDIUM':
        return { bg: 'bg-yellow-50', border: 'border-yellow-200', iconBg: 'bg-yellow-100', text: 'text-yellow-700', icon: 'text-yellow-600', badge: 'bg-yellow-100 text-yellow-700' };
      default:
        return { bg: 'bg-gray-50', border: 'border-gray-200', iconBg: 'bg-gray-100', text: 'text-gray-700', icon: 'text-gray-600', badge: 'bg-gray-100 text-gray-700' };
    }
  };

  return (
    <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-5 animate-fade-in">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 animate-fade-up">
        <div>
          <h1 className="text-xl font-extrabold text-gray-900 flex items-center gap-3 tracking-tight">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-800 to-slate-700 flex items-center justify-center shadow-lg shadow-slate-800/20">
              <Shield className="w-5 h-5 text-white" />
            </div>
            AI Alert Management
          </h1>
          <p className="text-sm text-gray-500 mt-1 font-medium">
            Real-time automated monitoring and intelligent alerts
          </p>
        </div>
        {stats.critical > 0 && (
          <span className="inline-flex items-center gap-2 px-3 py-2 rounded-xl bg-gradient-to-r from-red-500 to-red-600 text-white text-xs font-bold shadow-sm">
            <AlertTriangle className="w-4 h-4" />
            {stats.critical} critical alert{stats.critical > 1 ? 's' : ''}
          </span>
        )}
      </div>

      {/* AI Alerts Information Banner */}
      <div className="glass-card-dark rounded-3xl overflow-hidden animate-fade-up" style={{ animationDelay: '0.1s' }}>
        <div className="p-6">
          <div className="flex items-start gap-4">
            {/* Icon Section */}
            <div className="flex-shrink-0">
              <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-slate-800 to-cyan-600 flex items-center justify-center shadow-lg">
                <Shield className="w-8 h-8 text-white" />
              </div>
            </div>

            {/* Content Section */}
            <div className="flex-1 min-w-0">
              <div className="flex items-start justify-between gap-4 mb-3">
                <div>
                  <h2 className="text-xl font-bold text-gray-900 mb-1">AI-Powered Alert Management</h2>
                  <p className="text-sm text-gray-600 leading-relaxed">
                    Our intelligent monitoring system continuously analyzes patient vitals, TEWS scores, and clinical patterns to identify potential risks and deteriorating conditions in real-time.
                  </p>
                </div>
              </div>

              {/* Info Grid */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-4">
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-white/60 backdrop-blur-sm flex items-center justify-center flex-shrink-0">
                    <Activity className="w-4 h-4 text-cyan-600" />
                  </div>
                  <div>
                    <p className="text-xs font-bold text-gray-900 mb-0.5">Real-Time Monitoring</p>
                    <p className="text-xs text-gray-600">Continuous vital signs analysis</p>
                  </div>
                </div>
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-white/60 backdrop-blur-sm flex items-center justify-center flex-shrink-0">
                    <AlertTriangle className="w-4 h-4 text-red-600" />
                  </div>
                  <div>
                    <p className="text-xs font-bold text-gray-900 mb-0.5">Predictive Alerts</p>
                    <p className="text-xs text-gray-600">Early warning for deterioration</p>
                  </div>
                </div>
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-white/60 backdrop-blur-sm flex items-center justify-center flex-shrink-0">
                    <CheckCircle className="w-4 h-4 text-emerald-600" />
                  </div>
                  <div>
                    <p className="text-xs font-bold text-gray-900 mb-0.5">Clinical Validation</p>
                    <p className="text-xs text-gray-600">Acknowledge and document alerts</p>
                  </div>
                </div>
              </div>

              {/* Stats Bar */}
              <div className="mt-4 flex items-center gap-6 pt-4 border-t border-cyan-200/30">
                <div className="flex items-center gap-2">
                  <span className="text-2xl font-bold text-gray-900">{stats.total}</span>
                  <span className="text-xs text-gray-600">Total Alerts</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-2xl font-bold text-amber-600">{stats.active}</span>
                  <span className="text-xs text-gray-600">Active</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-2xl font-bold text-red-600">{stats.critical}</span>
                  <span className="text-xs text-gray-600">Critical</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-2xl font-bold text-emerald-600">{stats.acknowledged}</span>
                  <span className="text-xs text-gray-600">Resolved</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="glass-card rounded-3xl p-5 animate-fade-up" style={{ animationDelay: '0.2s' }}>
        <div className="flex flex-col sm:flex-row sm:items-center gap-3">
          {/* Search */}
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search alerts..."
              className="w-full pl-10 pr-4 py-2.5 bg-white/80 backdrop-blur-sm border-2 border-gray-200/60 rounded-2xl text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm"
            />
          </div>

          {/* Filter Pills */}
          <div className="flex items-center gap-2">
            {(['all', 'active', 'acknowledged'] as const).map((f) => (
              <button
                key={f}
                onClick={() => setFilter(f)}
                className={`inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-lg transition-all ${filter === f
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-lg shadow-slate-800/20'
                    : 'bg-white/60 backdrop-blur-sm text-gray-600 hover:bg-white/80 border border-white/60 shadow-sm'
                  }`}
              >
                {f === 'all' && <AlertCircle className="w-3.5 h-3.5" />}
                {f === 'active' && <Clock className="w-3.5 h-3.5" />}
                {f === 'acknowledged' && <CheckCircle className="w-3.5 h-3.5" />}
                {f.charAt(0).toUpperCase() + f.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Alerts List */}
      <div className="glass-card rounded-3xl p-6 animate-fade-up" style={{ animationDelay: '0.3s' }}>
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-xl font-extrabold text-gray-900 tracking-tight">AI Alerts</h3>
            <p className="text-sm text-gray-500">{filteredAlerts.length} alert{filteredAlerts.length !== 1 ? 's' : ''} shown</p>
          </div>
          <AlertTriangle className="w-5 h-5 text-cyan-600" />
        </div>

        {filteredAlerts.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-gray-400">
            <div className="w-16 h-16 rounded-2xl bg-gray-100 flex items-center justify-center mb-4">
              <CheckCircle className="w-8 h-8 opacity-40" />
            </div>
            <p className="text-sm font-semibold text-gray-600">No alerts found</p>
            <p className="text-xs text-gray-500 mt-1">
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
                const config = getSeverityConfig(alert.severity);
                const patient = patients.find((p) => p.id === alert.patientId);

                return (
                  <div
                    key={alert.id}
                    className={`bg-white/60 backdrop-blur-sm rounded-2xl p-4 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-500 border border-white/60 border-l-4 ${alert.acknowledgedAt ? 'opacity-60 border-l-gray-300' : config.border.replace('border-', 'border-l-')
                      }`}
                  >
                    <div className="flex items-start gap-3">
                      {/* Icon */}
                      <div className={`w-10 h-10 rounded-xl ${config.iconBg} flex items-center justify-center flex-shrink-0 shadow-sm`}>
                        {alert.severity === 'CRITICAL' ? (
                          <AlertTriangle className={`w-5 h-5 ${config.icon}`} />
                        ) : (
                          <AlertCircle className={`w-5 h-5 ${config.icon}`} />
                        )}
                      </div>

                      {/* Content */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <h3 className="text-sm font-semibold text-gray-900">{alert.message}</h3>
                        </div>

                        {patient && (
                          <p className="text-xs text-gray-600 mb-2">
                            Patient: <span className="font-semibold text-gray-800">{patient.fullName}</span> ({patient.id})
                          </p>
                        )}

                        {/* Contributing Factors */}
                        {alert.contributingFactors && alert.contributingFactors.length > 0 && (
                          <div className="flex flex-wrap gap-1.5 mt-2 mb-2">
                            {alert.contributingFactors.map((factor, i) => (
                              <span key={i} className="px-2 py-1 text-[10px] font-semibold bg-gray-100 text-gray-700 rounded-lg border border-gray-200">
                                {factor}
                              </span>
                            ))}
                          </div>
                        )}

                        <div className="flex items-center gap-3 mt-2">
                          <span className={`inline-flex items-center px-2 py-1 text-[10px] font-bold rounded-lg ${config.badge} border`}>
                            {alert.severity}
                          </span>
                          <span className="text-xs text-gray-500 flex items-center gap-1">
                            <Clock className="w-3 h-3" />
                            {formatDistanceToNow(alert.timestamp, { addSuffix: true })}
                          </span>
                        </div>

                        {/* Acknowledged Info */}
                        {alert.acknowledgedAt && (
                          <div className="flex items-center gap-2 mt-2 px-3 py-2 bg-cyan-50 border border-cyan-200 rounded-xl">
                            <CheckCircle className="w-3.5 h-3.5 text-cyan-700 flex-shrink-0" />
                            <span className="text-xs text-cyan-700">
                              Acknowledged by <span className="font-semibold">{alert.acknowledgedBy}</span> {formatDistanceToNow(alert.acknowledgedAt, { addSuffix: true })}
                              {alert.comment && <span className="font-semibold"> — {alert.comment}</span>}
                            </span>
                          </div>
                        )}
                      </div>

                      {/* Actions */}
                      <div className="flex items-start gap-1 flex-shrink-0">
                        {!alert.acknowledgedAt && (
                          <button
                            onClick={() => {
                              const comment = prompt('Add a comment (optional):');
                              acknowledgeAlert(alert.id, 'DR001', comment || undefined);
                            }}
                            className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all duration-300 shadow-lg shadow-slate-800/20"
                          >
                            <Eye className="w-3.5 h-3.5" />
                            Acknowledge
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
          </div>
        )}
      </div>
    </div>
  );
}
