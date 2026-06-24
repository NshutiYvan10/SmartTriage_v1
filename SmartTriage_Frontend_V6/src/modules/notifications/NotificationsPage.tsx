import { useState, useEffect } from 'react';
import {
  Bell, AlertTriangle, AlertCircle, CheckCircle, Info,
  Search, Clock, Trash2, CheckCheck, Eye,
  Shield, Heart, UserCheck, Settings, Inbox,
} from 'lucide-react';
import { useAlertStore } from '@/store/alertStore';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';

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
  critical: { icon: AlertTriangle, color: 'text-red-600', bg: 'bg-red-50', iconBg: 'bg-gradient-to-br from-red-500 to-red-600', badge: 'bg-red-500/20 text-red-300 border-red-500/30', label: 'Critical' },
  warning: { icon: AlertCircle, color: 'text-amber-600', bg: 'bg-amber-50', iconBg: 'bg-gradient-to-br from-amber-500 to-amber-600', badge: 'bg-amber-500/20 text-amber-300 border-amber-500/30', label: 'Warning' },
  info: { icon: Info, color: 'text-blue-600', bg: 'bg-blue-50', iconBg: 'bg-gradient-to-br from-blue-500 to-blue-600', badge: 'bg-blue-500/20 text-blue-300 border-blue-500/30', label: 'Info' },
  success: { icon: CheckCircle, color: 'text-emerald-600', bg: 'bg-emerald-50', iconBg: 'bg-gradient-to-br from-emerald-500 to-emerald-600', badge: 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30', label: 'Success' },
};

const categoryConfig: Record<NotificationCategory, { icon: any; label: string }> = {
  all: { icon: Bell, label: 'All' },
  triage: { icon: Shield, label: 'Triage' },
  vitals: { icon: Heart, label: 'Vitals' },
  system: { icon: Settings, label: 'System' },
  patient: { icon: UserCheck, label: 'Patient' },
};

export function NotificationsPage() {
  const { glassCard, glassInner, isDark, text } = useTheme();
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
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-10 h-10 bg-cyan-500/20 rounded-xl flex items-center justify-center shadow-lg relative">
                  <Bell className="w-5 h-5 text-white" />
                  {unreadCount > 0 && (
                    <span className="absolute -top-1.5 -right-1.5 min-w-[20px] h-5 bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center px-1 shadow-lg animate-pulse">
                      {unreadCount}
                    </span>
                  )}
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Notifications</h1>
                  <p className="text-white/50 text-xs font-medium">Stay updated on patient alerts, triage events, and system updates</p>
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
              className="relative rounded-3xl hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-500 overflow-hidden animate-fade-up"
              style={{ ...glassCard, animationDelay: `${0.05 + idx * 0.05}s` }}
            >
              <div className="p-4">
                <div className={`w-10 h-10 rounded-full ${s.iconBg} flex items-center justify-center mb-3 shadow-sm`}>
                  <s.icon className="w-5 h-5 text-white" />
                </div>
                <p className={`text-lg font-bold mb-1 ${text.heading}`}>{s.value}</p>
                <p className={`text-xs font-semibold uppercase tracking-wide ${text.muted}`}>{s.label}</p>
              </div>
            </div>
          ))}
        </div>

        {/* ── Filter Bar ── */}
        <div className="rounded-3xl p-5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.25s' }}>
          <div className="flex flex-col lg:flex-row lg:items-center gap-4">
            {/* Search */}
            <div className="relative flex-1">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search notifications..."
                className={`w-full pl-11 pr-4 py-2.5 rounded-2xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
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
                        : isDark
                          ? 'text-slate-400 hover:text-white hover:bg-white/5'
                          : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
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
                className={`px-3 py-2.5 text-xs font-semibold rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 ${
                  isDark ? 'text-white' : 'text-slate-700'
                }`}
                style={glassInner}
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
                className={`px-3 py-2.5 text-xs font-semibold rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 ${
                  isDark ? 'text-white' : 'text-slate-700'
                }`}
                style={glassInner}
              >
                <option value="all">All</option>
                <option value="unread">Unread</option>
                <option value="read">Read</option>
              </select>
            </div>
          </div>
        </div>

        {/* ── Notification List ── */}
        <div className="rounded-3xl p-5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.3s' }}>
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className={`text-xl font-extrabold tracking-tight ${text.heading}`}>Notifications</h3>
              <p className={`text-sm ${text.muted}`}>{filteredNotifications.length} notification{filteredNotifications.length !== 1 ? 's' : ''}</p>
            </div>
            <Bell className={`w-5 h-5 ${text.accent}`} />
          </div>

          {filteredNotifications.length > 0 ? (
            <div className="space-y-3">
              {filteredNotifications.map((n, idx) => {
                const config = typeConfig[n.type];
                const Icon = config.icon;
                return (
                  <div
                    key={n.id}
                    className={`rounded-2xl p-4 hover:-translate-y-0.5 transition-all duration-500 cursor-pointer flex items-start gap-4 group animate-fade-up ${
                      !n.read ? 'animate-critical-border' : ''
                    }`}
                    style={{ ...glassInner, animationDelay: `${0.35 + idx * 0.04}s` }}
                  >
                    {/* Icon */}
                    <div className={`w-10 h-10 rounded-xl ${config.iconBg} flex items-center justify-center flex-shrink-0 shadow-sm`}>
                      <Icon className="w-5 h-5 text-white" />
                    </div>

                    {/* Content */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1 flex-wrap">
                        <h3 className={`text-sm font-bold ${text.heading}`}>{n.title}</h3>
                        {!n.read && (
                          <span className="px-2 py-0.5 rounded-lg bg-red-500/20 text-red-300 text-[10px] font-bold border border-red-500/30">
                            NEW
                          </span>
                        )}
                      </div>
                      <p className={`text-xs leading-relaxed mb-3 ${text.body}`}>{n.message}</p>
                      <div className="flex items-center gap-3 flex-wrap">
                        <span className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg border ${config.badge}`}>
                          {config.label}
                        </span>
                        {n.patientName && (
                          <span className={`text-[11px] flex items-center gap-1 font-semibold ${text.body}`}>
                            <UserCheck className="w-3 h-3" />
                            {n.patientName}
                            {n.patientId && (
                              <span className={`font-medium ${text.muted}`}>({n.patientId})</span>
                            )}
                          </span>
                        )}
                        <span className={`text-[11px] flex items-center gap-1 font-medium ${text.muted}`}>
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
                          className="w-9 h-9 rounded-xl bg-cyan-500/20 hover:bg-cyan-500/30 flex items-center justify-center transition-all duration-300 border border-cyan-500/30 shadow-sm hover:-translate-y-0.5"
                          title="Mark as read"
                        >
                          <Eye className="w-4 h-4 text-cyan-400" />
                        </button>
                      )}
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          deleteNotification(n.id);
                        }}
                        className="w-9 h-9 rounded-xl bg-red-500/20 hover:bg-red-500/30 flex items-center justify-center transition-all duration-300 border border-red-500/30 shadow-sm hover:-translate-y-0.5"
                        title="Delete"
                      >
                        <Trash2 className="w-4 h-4 text-red-400" />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className={`flex flex-col items-center justify-center py-16 ${text.muted}`}>
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mb-4" style={glassInner}>
                <Bell className="w-8 h-8 opacity-40" />
              </div>
              <p className={`text-sm font-bold ${text.body}`}>No notifications found</p>
              <p className={`text-xs mt-1 ${text.muted}`}>Adjust your filters or check back later</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
