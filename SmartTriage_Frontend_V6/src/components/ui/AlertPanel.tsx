import React from 'react';
import { AlertTriangle, AlertCircle, Info, X } from 'lucide-react';
import { AIAlert } from '@/types';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

interface AlertPanelProps {
  alerts: AIAlert[];
  onAcknowledge?: (alertId: string, comment?: string) => void;
  onClose?: () => void;
}

export function AlertPanel({ alerts, onAcknowledge, onClose }: AlertPanelProps) {
  const { isDark } = useTheme();
  const [acknowledgeComment, setAcknowledgeComment] = React.useState<{ [key: string]: string }>({});

  const getSeverityIcon = (severity: AIAlert['severity']) => {
    switch (severity) {
      case 'CRITICAL':
        return <AlertTriangle className="w-5 h-5 text-red-600" />;
      case 'HIGH':
        return <AlertCircle className="w-5 h-5 text-orange-600" />;
      case 'MEDIUM':
        return <AlertCircle className="w-5 h-5 text-yellow-600" />;
      default:
        return <Info className="w-5 h-5 text-blue-600" />;
    }
  };

  const getSeverityColor = (severity: AIAlert['severity']) => {
    if (isDark) {
      switch (severity) {
        case 'CRITICAL':
          return 'border-l-red-500 bg-red-500/15';
        case 'HIGH':
          return 'border-l-orange-500 bg-orange-500/15';
        case 'MEDIUM':
          return 'border-l-yellow-500 bg-yellow-500/15';
        default:
          return 'border-l-blue-500 bg-blue-500/15';
      }
    }
    switch (severity) {
      case 'CRITICAL':
        return 'border-l-red-600 bg-red-50';
      case 'HIGH':
        return 'border-l-orange-600 bg-orange-50';
      case 'MEDIUM':
        return 'border-l-yellow-600 bg-yellow-50';
      default:
        return 'border-l-blue-600 bg-blue-50';
    }
  };

  if (alerts.length === 0) {
    return null;
  }

  return (
    <div
      className={`fixed right-4 top-4 w-96 max-h-[80vh] overflow-y-auto rounded-lg shadow-xl z-50 ${isDark ? '' : 'bg-white border border-gray-200'}`}
      style={isDark ? { background: 'rgba(8,47,73,0.95)', backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)', border: '1px solid rgba(2,132,199,0.22)', boxShadow: '0 8px 32px rgba(0,0,0,0.4)' } : undefined}
    >
      <div
        className={`sticky top-0 border-b px-4 py-3 flex items-center justify-between ${isDark ? 'border-white/10' : 'bg-white border-b-gray-200'}`}
        style={isDark ? { background: 'rgba(8,47,73,0.98)' } : undefined}
      >
        <h3 className={`font-semibold ${isDark ? 'text-white' : 'text-gray-900'}`}>AI Alerts ({alerts.length})</h3>
        {onClose && (
          <button onClick={onClose} className={isDark ? 'text-slate-400 hover:text-slate-200' : 'text-gray-400 hover:text-gray-600'}>
            <X className="w-5 h-5" />
          </button>
        )}
      </div>

      <div className={`divide-y ${isDark ? 'divide-white/10' : 'divide-gray-200'}`}>
        {alerts.map((alert) => (
          <div
            key={alert.id}
            className={`p-4 border-l-4 ${getSeverityColor(alert.severity)} alert-enter`}
          >
            <div className="flex items-start gap-3">
              {getSeverityIcon(alert.severity)}
              <div className="flex-1">
                <div className="flex items-center justify-between mb-1">
                  <span className={`text-xs font-medium ${isDark ? 'text-slate-400' : 'text-gray-500'} uppercase`}>
                    {alert.type.replace('_', ' ')}
                  </span>
                  <span className={`text-xs ${isDark ? 'text-slate-400' : 'text-gray-500'}`}>
                    {formatDistanceToNow(alert.timestamp, { addSuffix: true })}
                  </span>
                </div>

                <p className={`text-sm font-medium ${isDark ? 'text-white' : 'text-gray-900'} mb-2`}>{alert.message}</p>

                {alert.previousCategory && alert.recommendedCategory && (
                  <div className="flex items-center gap-2 mb-2">
                    <span className="badge badge-yellow">{alert.previousCategory}</span>
                    <span className={`text-xs ${isDark ? 'text-slate-400' : 'text-gray-500'}`}>→</span>
                    <span className="badge badge-red">{alert.recommendedCategory}</span>
                  </div>
                )}

                {alert.contributingFactors.length > 0 && (
                  <ul className={`text-xs ${isDark ? 'text-slate-300' : 'text-gray-600'} space-y-1 mb-3`}>
                    {alert.contributingFactors.map((factor, i) => (
                      <li key={i}>• {factor}</li>
                    ))}
                  </ul>
                )}

                {!alert.acknowledged && onAcknowledge && (
                  <div className="space-y-2">
                    <textarea
                      placeholder="Add comment (optional)..."
                      className={`w-full text-xs border rounded p-2 focus:outline-none focus:ring-2 focus:ring-primary-500 ${isDark ? 'bg-white/5 border-white/10 text-white placeholder-slate-500' : 'border-gray-300'}`}
                      rows={2}
                      value={acknowledgeComment[alert.id] || ''}
                      onChange={(e) =>
                        setAcknowledgeComment({ ...acknowledgeComment, [alert.id]: e.target.value })
                      }
                    />
                    <button
                      onClick={() => onAcknowledge(alert.id, acknowledgeComment[alert.id])}
                      className="btn-primary text-xs py-1"
                    >
                      Acknowledge
                    </button>
                  </div>
                )}

                {alert.acknowledged && (
                  <div className={`text-xs ${isDark ? 'text-slate-400' : 'text-gray-500'} italic`}>
                    Acknowledged by {alert.acknowledgedBy}
                    {alert.comment && `: "${alert.comment}"`}
                  </div>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
