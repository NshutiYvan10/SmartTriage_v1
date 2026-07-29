/* ── Operational report catalog — server-generated PDFs ──
   Every function downloads a REAL report rendered by the backend from
   authoritative queries (branded, parameterised, paginated, audit-logged).
   Nothing here snapshots the UI. */
import { downloadBlob } from './client';

export const operationalReportApi = {
  /** One day's ED operational return (arrivals, mix, dispositions, module activity, census). */
  dailyActivity: (hospitalId: string, date: string) =>
    downloadBlob(`/reports/operational/daily?hospitalId=${hospitalId}&date=${date}`,
      `daily-activity-${date}.pdf`),

  /** Department state + open work for a shift change, with signature block. */
  shiftHandoverSummary: (hospitalId: string, date: string, period: 'DAY' | 'NIGHT') =>
    downloadBlob(`/reports/operational/shift-summary?hospitalId=${hospitalId}&date=${date}&period=${period}`,
      `shift-handover-${date}-${period.toLowerCase()}.pdf`),

  /** Date-range totals + per-day trend table (≤ 92 days). */
  periodActivity: (hospitalId: string, from: string, to: string) =>
    downloadBlob(`/reports/operational/period?hospitalId=${hospitalId}&from=${from}&to=${to}`,
      `period-activity-${from}-to-${to}.pdf`),

  /** Period activity per-day breakdown as CSV (one row per day). */
  periodActivityCsv: (hospitalId: string, from: string, to: string) =>
    downloadBlob(`/reports/operational/period/csv?hospitalId=${hospitalId}&from=${from}&to=${to}`,
      `period-activity-${from}-to-${to}.csv`),

  /** The authenticated clinician's own workload — self-scoped server-side. */
  myActivity: (from: string, to: string) =>
    downloadBlob(`/reports/operational/my-activity?from=${from}&to=${to}`,
      `my-activity-${from}-to-${to}.pdf`),

  /** Daily quality KPI snapshots over a period. */
  qualityMetrics: (hospitalId: string, from: string, to: string) =>
    downloadBlob(`/reports/operational/quality?hospitalId=${hospitalId}&from=${from}&to=${to}`,
      `quality-metrics-${from}-to-${to}.pdf`),

  /** Daily quality KPI snapshots as CSV (one row per captured day). */
  qualityMetricsCsv: (hospitalId: string, from: string, to: string) =>
    downloadBlob(`/reports/operational/quality/csv?hospitalId=${hospitalId}&from=${from}&to=${to}`,
      `quality-metrics-${from}-to-${to}.csv`),
};
