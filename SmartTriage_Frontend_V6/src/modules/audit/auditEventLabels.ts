import { AuditLogEntry } from '@/api/audit';

/**
 * Presentation-layer humanization of audit actions.
 *
 * The stored `action` is deliberately raw and stable ("PUT /isolation/{id}/assign-room",
 * or a producer-supplied label like "CROSS_HOSPITAL_SAFETY_SUMMARY_READ nid=***2780").
 * Auditors read EVENTS, not endpoints — so display names are mapped here, at read time,
 * from a dictionary curated against the real controller inventory. The raw action/path
 * stays visible in the expanded technical detail; anything unmapped falls back to a
 * neutral breadcrumb (never invented semantics).
 */

export type AuditCategory =
  | 'Authentication'
  | 'Clinical'
  | 'Medication'
  | 'Lab & Investigations'
  | 'Patient records'
  | 'Bed & Flow'
  | 'Devices'
  | 'Administration'
  | 'Safety & Governance'
  | 'Other';

export interface AuditEventDescription {
  label: string;
  category: AuditCategory;
}

/** True for session housekeeping (login/refresh/activate) — foldable noise. */
export function isSessionEvent(e: AuditLogEntry): boolean {
  return (e.path || '').startsWith('/api/v1/auth/');
}

/** Exact action → label. Keys are the stored action strings (UUIDs already masked as {id}). */
const EXACT: Record<string, [string, AuditCategory]> = {
  // ── Authentication ──────────────────────────────────────────────────
  'POST /auth/login': ['Signed in', 'Authentication'],
  'POST /auth/refresh': ['Session renewed', 'Authentication'],
  'POST /auth/activate': ['Activated account', 'Authentication'],

  // ── Triage / visits / vitals ────────────────────────────────────────
  'POST /triage': ['Recorded triage assessment', 'Clinical'],
  'POST /triage/visit/{id}/confirm-field': ['Confirmed field triage', 'Clinical'],
  'POST /vitals': ['Recorded vital signs', 'Clinical'],
  'POST /visits': ['Created visit', 'Clinical'],
  'POST /visits/{id}/disposition': ['Recorded disposition', 'Clinical'],
  'PATCH /visits/{id}/status': ['Updated visit status', 'Clinical'],
  'POST /clinical-signs': ['Recorded clinical signs', 'Clinical'],
  'POST /clinical-notes': ['Wrote clinical note', 'Clinical'],
  'POST /clinical-notes/{id}/supersede': ['Superseded clinical note', 'Clinical'],
  'POST /diagnoses': ['Recorded diagnosis', 'Clinical'],
  'DELETE /diagnoses/{id}': ['Removed diagnosis', 'Clinical'],
  'DELETE /clinical-notes/{id}': ['Removed clinical note', 'Clinical'],
  'PATCH /alerts/{id}/acknowledge': ['Acknowledged clinical alert', 'Clinical'],

  // ── Sepsis ──────────────────────────────────────────────────────────
  'POST /sepsis/screen/{id}': ['Ran sepsis screening', 'Clinical'],
  'PUT /sepsis/bundle/{id}/start': ['Started sepsis bundle', 'Clinical'],

  // ── Fast track ──────────────────────────────────────────────────────
  'POST /fast-track/activate': ['Activated fast-track pathway', 'Clinical'],
  'PUT /fast-track/{id}/ecg': ['Recorded ECG result', 'Clinical'],
  'PUT /fast-track/{id}/ct': ['Recorded CT result', 'Clinical'],
  'PUT /fast-track/{id}/status': ['Updated fast-track status', 'Clinical'],
  'PUT /fast-track/{id}/complete': ['Completed fast-track', 'Clinical'],
  'PUT /fast-track/{id}/cancel': ['Cancelled fast-track', 'Clinical'],
  'PUT /fast-track/{id}/acknowledge': ['Acknowledged fast-track', 'Clinical'],

  // ── Hypoglycemia ────────────────────────────────────────────────────
  'POST /hypoglycemia/check/{id}': ['Ran hypoglycemia check', 'Clinical'],
  'PUT /hypoglycemia/{id}/treatment': ['Recorded hypoglycemia treatment', 'Clinical'],
  'PUT /hypoglycemia/{id}/repeat-glucose': ['Recorded repeat glucose', 'Clinical'],
  'PUT /hypoglycemia/{id}/resolve': ['Resolved hypoglycemia event', 'Clinical'],

  // ── Isolation ───────────────────────────────────────────────────────
  'POST /isolation/screen/{id}': ['Ran infection screening', 'Clinical'],
  'PUT /isolation/{id}/assign-room': ['Assigned isolation room', 'Clinical'],
  'PUT /isolation/{id}/end': ['Ended isolation', 'Clinical'],
  'PUT /isolation/{id}/notify-public-health': ['Recorded public-health notification', 'Clinical'],

  // ── Pathways ────────────────────────────────────────────────────────
  'POST /pathways': ['Created clinical pathway', 'Clinical'],
  'POST /pathways/activate': ['Activated clinical pathway', 'Clinical'],
  'POST /pathways/recommend/{id}': ['Ran pathway recommendation', 'Clinical'],
  'PUT /pathways/activation/{id}/complete': ['Completed clinical pathway', 'Clinical'],
  'PUT /pathways/activation/{id}/abandon': ['Abandoned clinical pathway', 'Clinical'],
  'PUT /pathways/activation/{id}/step/{id}/complete': ['Completed pathway step', 'Clinical'],
  'PUT /pathways/activation/{id}/step/{id}/skip': ['Skipped pathway step', 'Clinical'],

  // ── ICU / handover / referral / consent / EMS ───────────────────────
  'POST /icu/request': ['Requested ICU escalation', 'Clinical'],
  'POST /icu/auto-evaluate/{id}': ['Ran ICU auto-evaluation', 'Clinical'],
  'PUT /icu/{id}/assign-bed': ['Assigned ICU bed', 'Clinical'],
  'PUT /icu/{id}/response': ['Recorded ICU response', 'Clinical'],
  'PUT /icu/{id}/transfer': ['Transferred to ICU', 'Clinical'],
  'PUT /icu/{id}/cancel': ['Cancelled ICU request', 'Clinical'],
  'PUT /icu/{id}/notify-team': ['Notified ICU team', 'Clinical'],
  'POST /handover/generate/{id}': ['Generated handover report', 'Clinical'],
  'POST /handover/generate-bulk/{id}': ['Generated bulk handover', 'Clinical'],
  'PUT /handover/{id}/acknowledge': ['Acknowledged handover', 'Clinical'],
  'POST /referrals/visit/{id}': ['Created referral', 'Clinical'],
  'PUT /referrals/{id}/respond': ['Responded to referral', 'Clinical'],
  'PUT /referrals/{id}/cancel': ['Cancelled referral', 'Clinical'],
  'POST /consents/visit/{id}': ['Recorded informed consent', 'Clinical'],
  'POST /admissions/direct-resus': ['Direct-to-resus admission', 'Clinical'],
  'POST /admissions/{id}/confirm-arrival': ['Confirmed arrival', 'Clinical'],
  'POST /ems/runs': ['Created EMS run', 'Clinical'],
  'POST /ems/runs/{id}/field-triage': ['Recorded field triage', 'Clinical'],
  'POST /ems/runs/{id}/transfer-of-care': ['Completed transfer of care', 'Clinical'],
  'POST /ems/runs/{id}/interventions': ['Recorded EMS intervention', 'Clinical'],

  // ── Medication ──────────────────────────────────────────────────────
  'POST /medications': ['Prescribed medication', 'Medication'],
  'PATCH /medications/{id}/administer': ['Administered medication', 'Medication'],
  'POST /medications/doses/{id}/administer': ['Administered scheduled dose', 'Medication'],
  'POST /medications/doses/{id}/refuse': ['Recorded dose refusal', 'Medication'],
  'POST /medications/doses/{id}/delay': ['Delayed scheduled dose', 'Medication'],
  'PATCH /medications/{id}/cancel': ['Cancelled medication order', 'Medication'],
  'PATCH /medications/{id}/hold': ['Held medication', 'Medication'],
  'PATCH /medications/{id}/refuse': ['Recorded medication refusal', 'Medication'],
  'PATCH /medications/{id}/countersign': ['Countersigned administration', 'Medication'],
  'POST /medications/{id}/approve': ['Approved medication order', 'Medication'],
  'POST /medications/{id}/discontinue': ['Discontinued medication', 'Medication'],
  'POST /medications/{id}/modify': ['Modified medication order', 'Medication'],
  'POST /medications/{id}/resume': ['Resumed medication', 'Medication'],
  'POST /medications/{id}/prn-dose': ['Recorded PRN dose', 'Medication'],
  'POST /medications/{id}/infusion/start': ['Started infusion', 'Medication'],
  'POST /medications/{id}/infusion/stop': ['Stopped infusion', 'Medication'],
  'POST /medications/{id}/infusion/rate': ['Changed infusion rate', 'Medication'],
  'POST /med-safety/validate': ['Ran medication safety check', 'Medication'],
  'PUT /med-safety/{id}/override': ['Overrode medication safety check', 'Safety & Governance'],
  'PATCH /alerts/{id}/safety-override/acknowledge': ['Acknowledged safety override', 'Safety & Governance'],

  // ── Lab & investigations ────────────────────────────────────────────
  'POST /lab/order': ['Ordered lab test', 'Lab & Investigations'],
  'PUT /lab/{id}/collect-specimen': ['Collected specimen', 'Lab & Investigations'],
  'PUT /lab/{id}/receive': ['Received specimen in lab', 'Lab & Investigations'],
  'POST /lab/{id}/start-processing': ['Started lab processing', 'Lab & Investigations'],
  'PUT /lab/{id}/result': ['Entered lab result', 'Lab & Investigations'],
  'PUT /lab/{id}/result/panel': ['Entered panel results', 'Lab & Investigations'],
  'POST /lab/{id}/verify': ['Verified lab result', 'Lab & Investigations'],
  'POST /lab/{id}/verify-reject': ['Rejected result at verification', 'Lab & Investigations'],
  'POST /lab/{id}/release-without-verification': ['Released result without verification', 'Lab & Investigations'],
  'PUT /lab/{id}/acknowledge-critical': ['Acknowledged critical result', 'Lab & Investigations'],
  'PUT /lab/{id}/acknowledge': ['Acknowledged lab order', 'Lab & Investigations'],
  'PUT /lab/{id}/cancel': ['Cancelled lab order', 'Lab & Investigations'],
  'POST /lab/{id}/reject': ['Rejected specimen', 'Lab & Investigations'],
  'POST /investigations': ['Ordered investigation', 'Lab & Investigations'],
  'PATCH /investigations/{id}/result': ['Entered investigation result', 'Lab & Investigations'],
  'PATCH /investigations/{id}/specimen-collected': ['Collected specimen', 'Lab & Investigations'],
  'PATCH /investigations/{id}/in-progress': ['Started investigation', 'Lab & Investigations'],
  'PATCH /investigations/{id}/cancel': ['Cancelled investigation', 'Lab & Investigations'],

  // ── Patient records ─────────────────────────────────────────────────
  'POST /patients/register': ['Registered patient', 'Patient records'],
  'POST /patients': ['Created patient record', 'Patient records'],
  'PUT /patients/{id}': ['Updated patient record', 'Patient records'],
  'PATCH /patients/{id}/allergies': ['Updated allergies', 'Patient records'],
  'PATCH /patients/{id}/chronic-conditions': ['Updated chronic conditions', 'Patient records'],
  'PATCH /patients/{id}/pregnancy-status': ['Updated pregnancy status', 'Patient records'],
  'POST /patients/{id}/open-visit-here': ['Opened visit for patient', 'Patient records'],
  'POST /patients/{id}/resolve-identity': ['Resolved patient identity', 'Patient records'],
  'POST /patients/{id}/structured-allergies': ['Recorded structured allergy', 'Patient records'],
  'POST /patients/{id}/structured-conditions': ['Recorded chronic condition', 'Patient records'],
  'POST /patient-allergies/{id}/refute': ['Refuted allergy', 'Patient records'],
  'POST /patient-chronic-conditions/{id}/resolve': ['Resolved chronic condition', 'Patient records'],

  // ── Bed & flow ──────────────────────────────────────────────────────
  'POST /beds': ['Created bed', 'Bed & Flow'],
  'PATCH /beds/{id}': ['Updated bed', 'Bed & Flow'],
  'DELETE /beds/{id}': ['Removed bed', 'Bed & Flow'],
  'POST /beds/{id}/place': ['Placed patient in bed', 'Bed & Flow'],
  'POST /beds/{id}/transfer': ['Transferred patient to bed', 'Bed & Flow'],
  'POST /beds/{id}/discharge': ['Discharged from bed', 'Bed & Flow'],
  'POST /beds/{id}/mark-available': ['Marked bed available', 'Bed & Flow'],
  'POST /beds/{id}/mark-cleaned': ['Marked bed cleaned', 'Bed & Flow'],
  'POST /beds/{id}/mark-out-of-service': ['Marked bed out of service', 'Bed & Flow'],
  'POST /beds/{id}/assign-device': ['Assigned device to bed', 'Bed & Flow'],
  'POST /zone-transfers/{id}/accept': ['Accepted zone transfer', 'Bed & Flow'],

  // ── Devices ─────────────────────────────────────────────────────────
  'POST /iot/devices': ['Registered IoT device', 'Devices'],
  'POST /iot/devices/{id}/regenerate-key': ['Re-issued device API key', 'Devices'],
  'POST /iot/devices/{id}/power-on': ['Powered device on', 'Devices'],
  'POST /iot/devices/{id}/power-off': ['Powered device off', 'Devices'],
  'POST /iot/monitoring/start': ['Started monitoring session', 'Devices'],
  'POST /iot/monitoring/start-for-visit/{id}': ['Started monitoring for visit', 'Devices'],
  'POST /iot/monitoring/stop/{id}': ['Stopped monitoring session', 'Devices'],
  'POST /iot/monitoring/pause/{id}': ['Paused monitoring session', 'Devices'],
  'POST /iot/monitoring/resume/{id}': ['Resumed monitoring session', 'Devices'],
  'POST /iot/rfid/devices/{id}/bind-mode': ['Armed card-bind mode', 'Devices'],
  'PATCH /iot/rfid/devices/{id}/assign-registrar': ['Assigned reader to registrar', 'Devices'],
  'PUT /iot/rfid/replace-card': ['Replaced patient card', 'Devices'],
  'POST /iot/rfid/open-visit': ['Opened visit from card tap', 'Devices'],
  'POST /iot/rfid/tap': ['Card tap', 'Devices'],

  // ── Administration ──────────────────────────────────────────────────
  'POST /users': ['Created user', 'Administration'],
  'POST /users/invite': ['Invited user', 'Administration'],
  'PUT /users/{id}': ['Updated user', 'Administration'],
  'DELETE /users/{id}': ['Deactivated user', 'Administration'],
  'POST /users/{id}/reactivate': ['Reactivated user', 'Administration'],
  'POST /users/{id}/resend-invite': ['Re-sent invitation', 'Administration'],
  'DELETE /users/{id}/invite': ['Revoked invitation', 'Administration'],
  'PATCH /users/{id}/designation': ['Changed user designation', 'Administration'],
  'PUT /users/me/password': ['Changed own password', 'Authentication'],
  'PUT /users/me/profile': ['Updated own profile', 'Administration'],
  'POST /hospitals': ['Created hospital', 'Administration'],
  'PUT /hospitals/{id}': ['Updated hospital', 'Administration'],
  'DELETE /hospitals/{id}': ['Deactivated hospital', 'Administration'],
  'POST /hospitals/{id}/reactivate': ['Reactivated hospital', 'Administration'],
  'POST /offline/sync': ['Synced offline data', 'Administration'],
  'PUT /offline/conflict/{id}/resolve': ['Resolved sync conflict', 'Administration'],
  'POST /moh-reports/generate': ['Generated MoH report', 'Administration'],
  'PUT /moh-reports/{id}/submit': ['Submitted MoH report', 'Administration'],
  'PUT /moh-reports/{id}/accept': ['Accepted MoH report', 'Administration'],
  'PUT /moh-reports/{id}/reject': ['Rejected MoH report', 'Administration'],

  // ── Safety & governance ─────────────────────────────────────────────
  'POST /safety/incidents': ['Reported safety incident', 'Safety & Governance'],
  'PUT /safety/incidents/{id}': ['Updated safety incident', 'Safety & Governance'],
  'PUT /safety/incidents/{id}/investigate': ['Started incident investigation', 'Safety & Governance'],
  'PUT /safety/incidents/{id}/root-cause': ['Recorded root cause', 'Safety & Governance'],
  'PUT /safety/incidents/{id}/corrective-action': ['Added corrective action', 'Safety & Governance'],
  'PUT /safety/incidents/{id}/complete-action': ['Completed corrective action', 'Safety & Governance'],
  'PUT /safety/incidents/{id}/close': ['Closed safety incident', 'Safety & Governance'],
  'POST /governance/policies': ['Created policy', 'Safety & Governance'],
  'PUT /governance/policies/{id}': ['Updated policy', 'Safety & Governance'],
  'PUT /governance/policies/{id}/submit': ['Submitted policy for approval', 'Safety & Governance'],
  'PUT /governance/policies/{id}/approve': ['Approved policy', 'Safety & Governance'],
  'PUT /governance/policies/{id}/activate': ['Activated policy', 'Safety & Governance'],
  'PUT /governance/policies/{id}/suspend': ['Suspended policy', 'Safety & Governance'],
  'PUT /governance/policies/{id}/archive': ['Archived policy', 'Safety & Governance'],
};

/** Prefix rules for families where the tail varies (checked after EXACT). */
const PREFIX: Array<[string, string, AuditCategory]> = [
  ['PUT /sepsis/bundle/', 'Completed sepsis bundle item', 'Clinical'],
  ['POST /shifts', 'Shift roster change', 'Administration'],
  ['PUT /shifts', 'Shift roster change', 'Administration'],
  ['DELETE /shifts', 'Shift roster change', 'Administration'],
  ['POST /shift-templates', 'Shift template change', 'Administration'],
  ['PUT /shift-templates', 'Shift template change', 'Administration'],
  ['DELETE /shift-templates', 'Shift template change', 'Administration'],
  ['PATCH /break-the-glass-events/', 'Acknowledged break-the-glass event', 'Safety & Governance'],
  ['POST /ems/runs/', 'EMS run update', 'Clinical'],
  ['PATCH /ems/runs/', 'EMS run update', 'Clinical'],
  ['POST /med-safety/formulary', 'Formulary change', 'Medication'],
  ['PUT /med-safety/formulary', 'Formulary change', 'Medication'],
  ['POST /documents/visit/{id}/discharge-summary', 'Generated discharge summary', 'Clinical'],
  ['POST /documents/visit/{id}/handover', 'Generated handover document', 'Clinical'],
  ['POST /documents/create', 'Created document', 'Clinical'],
  ['POST /documents/{id}/amend', 'Amended document', 'Clinical'],
  ['PUT /documents/{id}/sign', 'Signed document', 'Clinical'],
  ['POST /quality/', 'Quality metrics computation', 'Administration'],
  ['POST /system-health/', 'System health check', 'Administration'],
  ['POST /data-sharing-consents/', 'Recorded data-sharing consent', 'Patient records'],
];

/** Category by first path segment — used by the generic fallback. */
const SEGMENT_CATEGORY: Record<string, AuditCategory> = {
  auth: 'Authentication',
  triage: 'Clinical', visits: 'Clinical', vitals: 'Clinical', sepsis: 'Clinical',
  'fast-track': 'Clinical', hypoglycemia: 'Clinical', isolation: 'Clinical',
  pathways: 'Clinical', icu: 'Clinical', handover: 'Clinical', referrals: 'Clinical',
  consents: 'Clinical', admissions: 'Clinical', ems: 'Clinical', alerts: 'Clinical',
  'clinical-notes': 'Clinical', 'clinical-signs': 'Clinical', diagnoses: 'Clinical',
  documents: 'Clinical', retriage: 'Clinical', 'zone-transfers': 'Bed & Flow',
  medications: 'Medication', 'med-safety': 'Medication',
  lab: 'Lab & Investigations', investigations: 'Lab & Investigations',
  patients: 'Patient records', 'patient-allergies': 'Patient records',
  'patient-chronic-conditions': 'Patient records', 'patient-identity': 'Patient records',
  'data-sharing-consents': 'Patient records',
  beds: 'Bed & Flow',
  iot: 'Devices',
  users: 'Administration', hospitals: 'Administration', shifts: 'Administration',
  'shift-templates': 'Administration', 'moh-reports': 'Administration',
  offline: 'Administration', quality: 'Administration', 'system-health': 'Administration',
  safety: 'Safety & Governance', governance: 'Safety & Governance',
  'break-the-glass-events': 'Safety & Governance',
};

const cap = (s: string) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

export function describeAuditEntry(e: AuditLogEntry): AuditEventDescription {
  const action = (e.action || '').trim();

  // Producer-supplied labels (e.g. "CROSS_HOSPITAL_SAFETY_SUMMARY_READ nid=***2780")
  // don't start with "METHOD /" — prettify SNAKE_CASE, keep the masked detail.
  if (action && !/^[A-Z]+ \//.test(action)) {
    const space = action.indexOf(' ');
    const code = space > 0 ? action.slice(0, space) : action;
    const detail = space > 0 ? action.slice(space + 1) : '';
    const words = cap(code.toLowerCase().replace(/_/g, ' '));
    const category: AuditCategory =
      code.includes('CROSS_HOSPITAL') || code.includes('PATIENT') || code.includes('DEEP_RECORD')
        ? 'Patient records' : 'Other';
    return { label: detail ? `${words} · ${detail.replace('=', ' ')}` : words, category };
  }

  const hit = EXACT[action];
  if (hit) return { label: hit[0], category: hit[1] };

  for (const [prefix, label, category] of PREFIX) {
    if (action.startsWith(prefix)) return { label, category };
  }

  // Neutral breadcrumb fallback — never invent semantics for unknown endpoints.
  const path = action.replace(/^[A-Z]+ /, '');
  const segments = path.split('/').filter((s) => s && s !== '{id}' && !s.startsWith('{'));
  const category = SEGMENT_CATEGORY[segments[0]] ?? 'Other';
  const label = segments.length
    ? cap(segments.join(' · ').replace(/-/g, ' '))
    : action || '(unknown action)';
  return { label, category };
}

/** Stable chip colors per category (tailwind utility classes). */
export const CATEGORY_STYLE: Record<AuditCategory, string> = {
  Authentication: 'bg-slate-500/10 text-slate-400',
  Clinical: 'bg-cyan-500/10 text-cyan-400',
  Medication: 'bg-violet-500/10 text-violet-400',
  'Lab & Investigations': 'bg-amber-500/10 text-amber-500',
  'Patient records': 'bg-emerald-500/10 text-emerald-500',
  'Bed & Flow': 'bg-sky-500/10 text-sky-400',
  Devices: 'bg-teal-500/10 text-teal-400',
  Administration: 'bg-indigo-500/10 text-indigo-400',
  'Safety & Governance': 'bg-rose-500/10 text-rose-400',
  Other: 'bg-slate-500/10 text-slate-400',
};
