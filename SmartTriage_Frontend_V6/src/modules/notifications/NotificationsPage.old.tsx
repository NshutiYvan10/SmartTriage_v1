import { useState } from 'react';
import {
  Bell, AlertTriangle, AlertCircle, CheckCircle, Info,
  Search, Clock, Trash2, CheckCheck, Eye,
  Shield, Heart, UserCheck, Settings,
} from 'lucide-react';

type NotificationType = 'critical' | 'warning' | 'info' | 'success';
type NotificationCategory = 'all' | 'triage' | 'vitals' | 'system' | 'patient';

interface Notification {
  id: string;
  title: string;
  message: string;
  type: NotificationType;
  category: NotificationCategory;
  timestamp: Date;
  read: boolean;
  patientName?: string;
  patientId?: string;
}

const sampleNotifications: Notification[] = [
  {
    id: '1',
    title: 'Critical Vital Signs Alert',
    message: 'Patient Jean Mugabo\'s blood pressure has dropped to 85/55 mmHg. Immediate intervention required.',
    type: 'critical',
    category: 'vitals',
    timestamp: new Date(Date.now() - 1000 * 60 * 3),
    read: false,
    patientName: 'Jean Mugabo',
    patientId: 'PT-001',
  },
  {
    id: '2',
    title: 'Triage Override Requested',
    message: 'Dr. Uwimana has requested a triage category override for patient Marie Ishimwe from GREEN to YELLOW.',
    type: 'warning',
    category: 'triage',
    timestamp: new Date(Date.now() - 1000 * 60 * 12),
    read: false,
    patientName: 'Marie Ishimwe',
    patientId: 'PT-002',
  },
  {
    id: '3',
    title: 'New Patient Registered',
    message: 'Patient Emmanuel Habimana has been registered and is awaiting initial triage assessment.',
    type: 'info',
    category: 'patient',
    timestamp: new Date(Date.now() - 1000 * 60 * 25),
    read: false,
    patientName: 'Emmanuel Habimana',
    patientId: 'PT-003',
  },
  {
    id: '4',
    title: 'AI Prediction: Sepsis Risk',
    message: 'Machine learning model detects elevated sepsis risk (78%) for patient Claudine Uwera based on recent vitals.',
    type: 'critical',
    category: 'vitals',
    timestamp: new Date(Date.now() - 1000 * 60 * 38),
    read: true,
    patientName: 'Claudine Uwera',
    patientId: 'PT-004',
  },
  {
    id: '5',
    title: 'Triage Completed',
    message: 'Patient Patrick Niyonzima has been triaged as YELLOW (Urgent). Assigned to Bay 3.',
    type: 'success',
    category: 'triage',
    timestamp: new Date(Date.now() - 1000 * 60 * 52),
    read: true,
    patientName: 'Patrick Niyonzima',
    patientId: 'PT-005',
  },
  {
    id: '6',
    title: 'System Maintenance Scheduled',
    message: 'SmartTriage system maintenance scheduled for tonight at 02:00 AM. Expected downtime: 30 minutes.',
    type: 'info',
    category: 'system',
    timestamp: new Date(Date.now() - 1000 * 60 * 60 * 2),
    read: true,
  },
  {
    id: '7',
    title: 'Abnormal Heart Rate Detected',
    message: 'Patient Diane Mukamana shows persistent tachycardia (HR: 128 bpm) over the last 15 minutes.',
    type: 'warning',
    category: 'vitals',
    timestamp: new Date(Date.now() - 1000 * 60 * 60 * 3),
    read: true,
    patientName: 'Diane Mukamana',
    patientId: 'PT-006',
  },
  {
    id: '8',
    title: 'Patient Discharged',
    message: 'Patient Théogène Nsengimana has been discharged. All vitals stable at time of discharge.',
    type: 'success',
    category: 'patient',
    timestamp: new Date(Date.now() - 1000 * 60 * 60 * 4),
    read: true,
    patientName: 'Théogène Nsengimana',
    patientId: 'PT-007',
  },
  {
    id: '9',
    title: 'Shift Report Available',
    message: 'The morning shift summary report is now available for review. 23 patients seen, 2 critical cases.',
    type: 'info',
    category: 'system',
    timestamp: new Date(Date.now() - 1000 * 60 * 60 * 6),
    read: true,
  },
  {
    id: '10',
    title: 'Temperature Alert',
    message: 'Patient Aimée Ingabire has a temperature of 39.8°C. Fever protocol has been initiated.',
    type: 'warning',
    category: 'vitals',
    timestamp: new Date(Date.now() - 1000 * 60 * 60 * 8),
    read: true,
    patientName: 'Aimée Ingabire',
    patientId: 'PT-008',
  },
];

function formatTimeAgo(date: Date): string {
  const now = Date.now();
  const diff = now - date.getTime();
  const minutes = Math.floor(diff / (1000 * 60));
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));

  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  if (hours < 24) return `${hours}h ago`;
  return `${days}d ago`;
}

const typeConfig: Record<NotificationType, { icon: any; color: string; bg: string; badge: string; label: string }> = {
  critical: { icon: AlertTriangle, color: 'text-red-600', bg: 'bg-red-50', badge: 'bg-red-100 text-red-700', label: 'Critical' },
  warning: { icon: AlertCircle, color: 'text-amber-600', bg: 'bg-amber-50', badge: 'bg-amber-100 text-amber-700', label: 'Warning' },
  info: { icon: Info, color: 'text-blue-600', bg: 'bg-blue-50', badge: 'bg-blue-100 text-blue-700', label: 'Info' },
  success: { icon: CheckCircle, color: 'text-emerald-600', bg: 'bg-emerald-50', badge: 'bg-emerald-100 text-emerald-700', label: 'Success' },
};

const categoryConfig: Record<NotificationCategory, { icon: any; label: string }> = {
  all: { icon: Bell, label: 'All' },
  triage: { icon: Shield, label: 'Triage' },
  vitals: { icon: Heart, label: 'Vitals' },
  system: { icon: Settings, label: 'System' },
  patient: { icon: UserCheck, label: 'Patient' },
};

export function NotificationsPage() {
  const [notifications, setNotifications] = useState<Notification[]>(sampleNotifications);
  const [searchQuery, setSearchQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState<NotificationType | 'all'>('all');
  const [categoryFilter, setCategoryFilter] = useState<NotificationCategory>('all');
  const [readFilter, setReadFilter] = useState<'all' | 'unread' | 'read'>('all');

  const unreadCount = notifications.filter((n) => !n.read).length;
  const criticalCount = notifications.filter((n) => n.type === 'critical').length;
  const recentCount = notifications.filter((n) => Date.now() - n.timestamp.getTime() < 1000 * 60 * 60 * 24).length;
  const totalCount = notifications.length;

  const filteredNotifications = notifications.filter((n) => {
    if (typeFilter !== 'all' && n.type !== typeFilter) return false;
    if (categoryFilter !== 'all' && n.category !== categoryFilter) return false;
    if (readFilter === 'unread' && n.read) return false;
    if (readFilter === 'read' && !n.read) return false;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      return (
        n.title.toLowerCase().includes(q) ||
        n.message.toLowerCase().includes(q) ||
        (n.patientName && n.patientName.toLowerCase().includes(q))
      );
    }
    return true;
  });

  const markAllRead = () => {
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
  };

  const markAsRead = (id: string) => {
    setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, read: true } : n)));
  };

  const deleteNotification = (id: string) => {
    setNotifications((prev) => prev.filter((n) => n.id !== id));
  };

  const clearAll = () => {
    setNotifications([]);
  };

  return (
    <div className="p-5 max-w-6xl mx-auto space-y-5">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-xl font-extrabold text-gray-900 flex items-center gap-3 tracking-tight">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-800 to-slate-700 flex items-center justify-center shadow-sm">
              <Bell className="w-5 h-5 text-white" />
            </div>
            Notifications
          </h1>
          <p className="text-sm text-gray-600 mt-1 font-medium">
            Stay updated on patient alerts, triage events, and system updates
          </p>
        </div>

        <div className="flex items-center gap-2">
          {unreadCount > 0 && (
            <span className="px-3 py-1 rounded-full bg-red-100 text-red-700 text-xs font-semibold">
              {unreadCount} unread
            </span>
          )}
          <button
            onClick={markAllRead}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-medium text-cyan-700 bg-cyan-50 hover:bg-cyan-100 border border-cyan-200 rounded-xl transition-colors"
          >
            <CheckCheck className="w-3.5 h-3.5" />
            Mark all read
          </button>
          <button
            onClick={clearAll}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-medium text-red-600 bg-red-50 hover:bg-red-100 border border-red-200 rounded-xl transition-colors"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Clear all
          </button>
        </div>
      </div>

      {/* Notification Management Badge */}
      <div className="bg-cyan-50 border border-cyan-200 rounded-xl p-4 animate-fade-up" style={{animationDelay: '0.1s'}}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-cyan-100 flex items-center justify-center">
              <Bell className="w-5 h-5 text-cyan-600" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-gray-900">Notification Center</h3>
              <p className="text-xs text-gray-600">Stay updated with real-time alerts</p>
            </div>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-lg font-bold text-gray-900">{totalCount}</span>
            <span className="text-sm font-medium text-cyan-600">{unreadCount} unread</span>
            {criticalCount > 0 && (
              <span className="text-sm font-medium text-red-600">{criticalCount} critical</span>
            )}
          </div>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-4">
        <div className="flex flex-col lg:flex-row lg:items-center gap-4">
          {/* Search */}
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search notifications..."
              className="w-full pl-10 pr-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
            />
          </div>

          {/* Category Pills */}
          <div className="flex items-center gap-2 flex-wrap">
            {(Object.keys(categoryConfig) as NotificationCategory[]).map((cat) => {
              const config = categoryConfig[cat];
              const Icon = config.icon;
              const isActive = categoryFilter === cat;
              return (
                <button
                  key={cat}
                  onClick={() => setCategoryFilter(cat)}
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-all ${
                    isActive
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-sm'
                      : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                  }`}
                >
                  <Icon className="w-3.5 h-3.5" />
                  {config.label}
                </button>
              );
            })}
          </div>

          {/* Type & Read Filters */}
          <div className="flex items-center gap-2">
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value as NotificationType | 'all')}
              className="px-3 py-2 text-xs font-medium bg-gray-50 border border-gray-200 rounded-xl text-gray-700 focus:outline-none focus:ring-2 focus:ring-cyan-500/20"
            >
              <option value="all">All Types</option>
              <option value="critical">Critical</option>
              <option value="warning">Warning</option>
              <option value="info">Info</option>
              <option value="success">Success</option>
            </select>

            <select
              value={readFilter}
              onChange={(e) => setReadFilter(e.target.value as 'all' | 'unread' | 'read')}
              className="px-3 py-2 text-xs font-medium bg-gray-50 border border-gray-200 rounded-xl text-gray-700 focus:outline-none focus:ring-2 focus:ring-cyan-500/20"
            >
              <option value="all">All</option>
              <option value="unread">Unread</option>
              <option value="read">Read</option>
            </select>
          </div>
        </div>
      </div>



      {/* Notification List */}
      <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-5">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-sm font-bold text-gray-900">Notifications</h3>
            <p className="text-sm text-gray-500">{filteredNotifications.length} notification{filteredNotifications.length !== 1 ? 's' : ''}</p>
          </div>
          <Clock className="w-5 h-5 text-gray-400" />
        </div>

        {filteredNotifications.length > 0 ? (
          <div className="space-y-3">
            {filteredNotifications.map((n) => {
              const config = typeConfig[n.type];
              const Icon = config.icon;
              return (
                <div
                  key={n.id}
                  className={`bg-white rounded-2xl p-4 hover:shadow-xl hover:-translate-y-0.5 transition-all duration-300 cursor-pointer border border-gray-200/60 flex items-start gap-3 group backdrop-blur-sm ${
                    !n.read ? 'border-red-200 bg-red-50/20' : ''
                  }`}
                >
                  {/* Icon */}
                  <div
                    className={`w-10 h-10 rounded-xl ${config.bg} flex items-center justify-center flex-shrink-0 shadow-sm`}
                  >
                    <Icon className={`w-5 h-5 ${config.color}`} />
                  </div>

                  {/* Content */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <h3 className="text-sm font-semibold text-gray-900">
                        {n.title}
                      </h3>
                      {!n.read && (
                        <span className="px-2 py-0.5 rounded-md bg-red-100 text-red-700 text-[10px] font-bold border border-red-200">
                          UNREAD
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-gray-600 leading-relaxed mb-3">{n.message}</p>
                    <div className="flex items-center gap-3 flex-wrap">
                      <span className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg border ${config.badge} border-current/20`}>
                        {config.label}
                      </span>
                      {n.patientName && (
                        <span className="text-[11px] text-gray-500 flex items-center gap-1 font-medium">
                          <UserCheck className="w-3 h-3" />
                          {n.patientName}
                          {n.patientId && (
                            <span className="text-gray-400">({n.patientId})</span>
                          )}
                        </span>
                      )}
                      <span className="text-[11px] text-gray-400 flex items-center gap-1 font-medium">
                        <Clock className="w-3 h-3" />
                        {formatTimeAgo(n.timestamp)}
                      </span>
                    </div>
                  </div>

                  {/* Actions */}
                  <div className="flex items-center gap-1 flex-shrink-0">
                    {!n.read && (
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          markAsRead(n.id);
                        }}
                        className="px-3 py-1.5 rounded-lg bg-cyan-50 text-cyan-700 hover:bg-cyan-100 transition-colors border border-cyan-200 text-xs font-semibold"
                        title="Mark as read"
                      >
                        <Eye className="w-4 h-4" />
                      </button>
                    )}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        deleteNotification(n.id);
                      }}
                      className="px-3 py-1.5 rounded-lg bg-red-50 text-red-600 hover:bg-red-100 transition-colors border border-red-200 text-xs font-semibold"
                      title="Delete"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center py-12 text-gray-400">
            <div className="w-16 h-16 rounded-2xl bg-gray-100 flex items-center justify-center mb-4">
              <Bell className="w-8 h-8 opacity-40" />
            </div>
            <p className="text-sm font-medium text-gray-500">No notifications found</p>
            <p className="text-xs text-gray-400 mt-1">Adjust your filters or check back later</p>
          </div>
        )}
      </div>
    </div>
  );
}
