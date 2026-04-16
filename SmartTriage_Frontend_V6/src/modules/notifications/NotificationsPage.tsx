import { useState, useEffect } from 'react';
import {
  Bell, AlertTriangle, AlertCircle, CheckCircle, Info,
  Search, Clock, Trash2, CheckCheck, Eye,
  Shield, Heart, UserCheck, Settings, Inbox,
} from 'lucide-react';
import { useAlertStore } from '@/store/alertStore';
import { useAuthStore } from '@/store/authStore';

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

const typeConfig: Record<NotificationType, { icon: any; color: string; bg: string; iconBg: string; badge: string; label: string }> = {
  critical: { icon: AlertTriangle, color: 'text-red-600', bg: 'bg-red-50', iconBg: 'bg-gradient-to-br from-red-500 to-red-600', badge: 'bg-red-100 text-red-700 border-red-200', label: 'Critical' },
  warning: { icon: AlertCircle, color: 'text-amber-600', bg: 'bg-amber-50', iconBg: 'bg-gradient-to-br from-amber-500 to-amber-600', badge: 'bg-amber-100 text-amber-700 border-amber-200', label: 'Warning' },
  info: { icon: Info, color: 'text-blue-600', bg: 'bg-blue-50', iconBg: 'bg-gradient-to-br from-blue-500 to-blue-600', badge: 'bg-blue-100 text-blue-700 border-blue-200', label: 'Info' },
  success: { icon: CheckCircle, color: 'text-emerald-600', bg: 'bg-emerald-50', iconBg: 'bg-gradient-to-br from-emerald-500 to-emerald-600', badge: 'bg-emerald-100 text-emerald-700 border-emerald-200', label: 'Success' },
};

const categoryConfig: Record<NotificationCategory, { icon: any; label: string }> = {
  all: { icon: Bell, label: 'All' },
  triage: { icon: Shield, label: 'Triage' },
  vitals: { icon: Heart, label: 'Vitals' },
  system: { icon: Settings, label: 'System' },
  patient: { icon: UserCheck, label: 'Patient' },
};

export function NotificationsPage() {
  const storeAlerts = useAlertStore((s) => s.alerts);
  const fetchAllAlerts = useAlertStore((s) => s.fetchAllAlerts);
  const user = useAuthStore((s) => s.user);

  const mappedNotifications: Notification[] = storeAlerts.map((a) => ({
    id: a.id,
    title: a.title || a.type.replace(/_/g, ' '),
    message: a.message,
    type: a.severity === 'CRITICAL' ? 'critical' as const : a.severity === 'HIGH' ? 'warning' as const : 'info' as const,
    category: (a.type === 'DETERIORATION' || a.type === 'THRESHOLD_BREACH') ? 'vitals' as const : a.type === 'DOCTOR_NOTIFICATION' ? 'triage' as const : 'patient' as const,
    timestamp: a.timestamp instanceof Date ? a.timestamp : new Date(a.timestamp),
    read: a.acknowledged,
    patientName: a.patientName || undefined,
    patientId: a.patientId || undefined,
  }));

  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    if (user?.hospitalId) {
      fetchAllAlerts(user.hospitalId);
    }
  }, [user?.hospitalId, fetchAllAlerts]);

  useEffect(() => {
    setNotifications(mappedNotifications);
  }, [storeAlerts]);
  const [typeFilter, setTypeFilter] = useState<NotificationType | 'all'>('all');
  const [categoryFilter, setCategoryFilter] = useState<NotificationCategory>('all');
  const [readFilter, setReadFilter] = useState<'all' | 'unread' | 'read'>('all');

  const unreadCount = notifications.filter((n) => !n.read).length;
  const criticalCount = notifications.filter((n) => n.type === 'critical' && !n.read).length;
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
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Dark Header Banner ── */}
        <div className="glass-card-dark rounded-3xl overflow-hidden animate-fade-up">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg relative">
                  <Bell className="w-6 h-6 text-white" />
                  {unreadCount > 0 && (
                    <span className="absolute -top-1.5 -right-1.5 min-w-[20px] h-5 bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center px-1 shadow-lg animate-pulse">
                      {unreadCount}
                    </span>
                  )}
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Notifications</h1>
                  <p className="text-white/70 text-xs font-medium">Stay updated on patient alerts, triage events, and system updates</p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={markAllRead}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-white/15 hover:bg-white/25 backdrop-blur-sm rounded-xl transition-all duration-300 border border-white/20"
                >
                  <CheckCheck className="w-3.5 h-3.5" />
                  Mark all read
                </button>
                <button
                  onClick={clearAll}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-red-300 bg-red-500/15 hover:bg-red-500/25 backdrop-blur-sm rounded-xl transition-all duration-300 border border-red-400/20"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  Clear all
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Stats Ribbon ── */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {[
            { label: 'Total', value: totalCount, icon: Inbox, iconBg: 'bg-gradient-to-br from-slate-800 to-slate-700' },
            { label: 'Unread', value: unreadCount, icon: Bell, iconBg: 'bg-gradient-to-br from-cyan-600 to-cyan-500' },
            { label: 'Critical', value: criticalCount, icon: AlertTriangle, iconBg: 'bg-gradient-to-br from-red-500 to-red-600' },
            { label: 'Today', value: notifications.filter((n) => Date.now() - n.timestamp.getTime() < 1000 * 60 * 60 * 24).length, icon: Clock, iconBg: 'bg-gradient-to-br from-emerald-500 to-emerald-600' },
          ].map((s, idx) => (
            <div
              key={s.label}
              className="glass-card relative rounded-3xl hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-500 overflow-hidden animate-fade-up"
              style={{ animationDelay: `${0.05 + idx * 0.05}s` }}
            >
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

        {/* ── Filter Bar ── */}
        <div className="glass-card rounded-3xl p-5 shadow-md shadow-gray-200/30 animate-fade-up" style={{ animationDelay: '0.25s' }}>
          <div className="flex flex-col lg:flex-row lg:items-center gap-4">
            {/* Search */}
            <div className="relative flex-1">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search notifications..."
                className="w-full pl-11 pr-4 py-2.5 bg-white/80 backdrop-blur-sm border border-gray-200/60 rounded-2xl text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm"
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
                    className={`inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-xl transition-all duration-300 ${
                      isActive
                        ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-lg shadow-slate-800/20'
                        : 'bg-white/60 backdrop-blur-sm text-gray-600 hover:bg-white/80 border border-white/60 shadow-sm'
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
                className="px-3 py-2.5 text-xs font-semibold bg-white/80 backdrop-blur-sm border border-gray-200/60 rounded-xl text-gray-700 focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm"
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
                className="px-3 py-2.5 text-xs font-semibold bg-white/80 backdrop-blur-sm border border-gray-200/60 rounded-xl text-gray-700 focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm"
              >
                <option value="all">All</option>
                <option value="unread">Unread</option>
                <option value="read">Read</option>
              </select>
            </div>
          </div>
        </div>

        {/* ── Notification List ── */}
        <div className="glass-card rounded-3xl p-5 shadow-lg shadow-gray-200/40 animate-fade-up" style={{ animationDelay: '0.3s' }}>
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className="text-xl font-extrabold text-gray-900 tracking-tight">Notifications</h3>
              <p className="text-sm text-gray-500">{filteredNotifications.length} notification{filteredNotifications.length !== 1 ? 's' : ''}</p>
            </div>
            <Bell className="w-5 h-5 text-cyan-600" />
          </div>

          {filteredNotifications.length > 0 ? (
            <div className="space-y-3">
              {filteredNotifications.map((n, idx) => {
                const config = typeConfig[n.type];
                const Icon = config.icon;
                return (
                  <div
                    key={n.id}
                    className={`bg-white/70 backdrop-blur-xl rounded-2xl p-4 shadow-lg shadow-gray-200/50 hover:shadow-xl hover:shadow-gray-300/40 hover:-translate-y-0.5 transition-all duration-500 cursor-pointer flex items-start gap-4 group animate-fade-up ${
                      !n.read ? 'animate-critical-border' : 'border border-white/80'
                    }`}
                    style={{ animationDelay: `${0.35 + idx * 0.04}s` }}
                  >
                    {/* Icon */}
                    <div className={`w-10 h-10 rounded-xl ${config.iconBg} flex items-center justify-center flex-shrink-0 shadow-sm`}>
                      <Icon className="w-5 h-5 text-white" />
                    </div>

                    {/* Content */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1 flex-wrap">
                        <h3 className="text-sm font-bold text-gray-900">{n.title}</h3>
                        {!n.read && (
                          <span className="px-2 py-0.5 rounded-lg bg-red-100 text-red-700 text-[10px] font-bold border border-red-200">
                            NEW
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-gray-600 leading-relaxed mb-3">{n.message}</p>
                      <div className="flex items-center gap-3 flex-wrap">
                        <span className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg border ${config.badge}`}>
                          {config.label}
                        </span>
                        {n.patientName && (
                          <span className="text-[11px] text-gray-500 flex items-center gap-1 font-semibold">
                            <UserCheck className="w-3 h-3" />
                            {n.patientName}
                            {n.patientId && (
                              <span className="text-gray-400 font-medium">({n.patientId})</span>
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
                    <div className="flex items-center gap-1.5 flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity duration-300">
                      {!n.read && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            markAsRead(n.id);
                          }}
                          className="w-9 h-9 rounded-xl bg-cyan-50 hover:bg-cyan-100 flex items-center justify-center transition-all duration-300 border border-cyan-200 shadow-sm hover:-translate-y-0.5"
                          title="Mark as read"
                        >
                          <Eye className="w-4 h-4 text-cyan-700" />
                        </button>
                      )}
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          deleteNotification(n.id);
                        }}
                        className="w-9 h-9 rounded-xl bg-red-50 hover:bg-red-100 flex items-center justify-center transition-all duration-300 border border-red-200 shadow-sm hover:-translate-y-0.5"
                        title="Delete"
                      >
                        <Trash2 className="w-4 h-4 text-red-600" />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-16 text-gray-400">
              <div className="w-16 h-16 rounded-2xl bg-gray-100 flex items-center justify-center mb-4">
                <Bell className="w-8 h-8 opacity-40" />
              </div>
              <p className="text-sm font-bold text-gray-600">No notifications found</p>
              <p className="text-xs text-gray-400 mt-1">Adjust your filters or check back later</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
