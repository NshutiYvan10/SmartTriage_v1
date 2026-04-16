import { create } from 'zustand';
import { AuditLogEntry, AuditAction } from '@/types';

interface AuditState {
  entries: AuditLogEntry[];
  addEntry: (entry: Omit<AuditLogEntry, 'id' | 'timestamp'>) => void;
  getEntriesByPatient: (patientId: string) => AuditLogEntry[];
  getEntriesByAction: (action: AuditAction) => AuditLogEntry[];
  getRecentEntries: (count?: number) => AuditLogEntry[];
  getEntriesByDateRange: (start: Date, end: Date) => AuditLogEntry[];
  searchEntries: (query: string) => AuditLogEntry[];
  getFilteredEntries: (filters: AuditFilters) => AuditLogEntry[];
  exportToCSV: (entries?: AuditLogEntry[]) => string;
  getAuditStats: () => AuditStats;
}

export interface AuditFilters {
  search?: string;
  actions?: AuditAction[];
  startDate?: Date;
  endDate?: Date;
  performedBy?: string;
  patientId?: string;
}

export interface AuditStats {
  totalEntries: number;
  todayEntries: number;
  actionBreakdown: Record<string, number>;
  topPerformers: { name: string; count: number }[];
  recentActivity: AuditLogEntry[];
}

export const useAuditStore = create<AuditState>((set, get) => ({
  entries: [],

  addEntry: (entryData) => {
    const entry: AuditLogEntry = {
      ...entryData,
      id: `AUD${Date.now()}${Math.random().toString(36).substr(2, 6).toUpperCase()}`,
      timestamp: new Date(),
    };
    set((state) => ({ entries: [entry, ...state.entries] }));
  },

  getEntriesByPatient: (patientId) => {
    return get().entries.filter((e) => e.patientId === patientId);
  },

  getEntriesByAction: (action) => {
    return get().entries.filter((e) => e.action === action);
  },

  getRecentEntries: (count = 50) => {
    return get().entries.slice(0, count);
  },

  getEntriesByDateRange: (start, end) => {
    return get().entries.filter((e) => {
      const t = new Date(e.timestamp).getTime();
      return t >= start.getTime() && t <= end.getTime();
    });
  },

  searchEntries: (query) => {
    const q = query.toLowerCase();
    return get().entries.filter(
      (e) =>
        e.details.toLowerCase().includes(q) ||
        e.performedByName.toLowerCase().includes(q) ||
        e.action.toLowerCase().includes(q) ||
        (e.patientId && e.patientId.toLowerCase().includes(q)) ||
        (e.previousValue && e.previousValue.toLowerCase().includes(q)) ||
        (e.newValue && e.newValue.toLowerCase().includes(q))
    );
  },

  getFilteredEntries: (filters) => {
    let result = get().entries;

    if (filters.search) {
      const q = filters.search.toLowerCase();
      result = result.filter(
        (e) =>
          e.details.toLowerCase().includes(q) ||
          e.performedByName.toLowerCase().includes(q) ||
          e.action.toLowerCase().includes(q)
      );
    }

    if (filters.actions && filters.actions.length > 0) {
      result = result.filter((e) => filters.actions!.includes(e.action));
    }

    if (filters.startDate) {
      result = result.filter(
        (e) => new Date(e.timestamp).getTime() >= filters.startDate!.getTime()
      );
    }

    if (filters.endDate) {
      result = result.filter(
        (e) => new Date(e.timestamp).getTime() <= filters.endDate!.getTime()
      );
    }

    if (filters.performedBy) {
      result = result.filter(
        (e) => e.performedByName.toLowerCase().includes(filters.performedBy!.toLowerCase())
      );
    }

    if (filters.patientId) {
      result = result.filter((e) => e.patientId === filters.patientId);
    }

    return result;
  },

  exportToCSV: (entries?) => {
    const data = entries || get().entries;
    const headers = [
      'ID',
      'Timestamp',
      'Action',
      'Performed By',
      'Performer Name',
      'Patient ID',
      'Details',
      'Previous Value',
      'New Value',
    ];

    const rows = data.map((e) => [
      e.id,
      new Date(e.timestamp).toISOString(),
      e.action,
      e.performedBy,
      e.performedByName,
      e.patientId || '',
      `"${e.details.replace(/"/g, '""')}"`,
      e.previousValue || '',
      e.newValue || '',
    ]);

    return [headers.join(','), ...rows.map((r) => r.join(','))].join('\n');
  },

  getAuditStats: () => {
    const entries = get().entries;
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const todayEntries = entries.filter(
      (e) => new Date(e.timestamp).getTime() >= today.getTime()
    );

    // Action breakdown
    const actionBreakdown: Record<string, number> = {};
    entries.forEach((e) => {
      actionBreakdown[e.action] = (actionBreakdown[e.action] || 0) + 1;
    });

    // Top performers
    const performerCounts: Record<string, number> = {};
    entries.forEach((e) => {
      performerCounts[e.performedByName] = (performerCounts[e.performedByName] || 0) + 1;
    });
    const topPerformers = Object.entries(performerCounts)
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 5);

    return {
      totalEntries: entries.length,
      todayEntries: todayEntries.length,
      actionBreakdown,
      topPerformers,
      recentActivity: entries.slice(0, 10),
    };
  },
}));
