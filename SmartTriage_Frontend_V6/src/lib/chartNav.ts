/* ════════════════════════════════════════════════════════════════════
   chartNav — the ONE canonical way to open a patient chart.

   Background: navigation to the patient chart was hand-written and
   copy-pasted across the app, which propagated two wrong-target bugs —
   `/patients/${visitId}` (a visitId sent into the /patients/:patientId
   route — wrong entity) and `/visits/${id}` (plural — no such route).
   The real chart route is `/visit/:visitId` (singular).

   Always route through `chartPath(visitId)` (or `openChart`) so the
   path lives in exactly one place and the wrong-entity / wrong-path
   class of bug cannot reappear.
   ════════════════════════════════════════════════════════════════════ */

/** Canonical patient-chart path for a visit id. */
export function chartPath(visitId: string): string {
  return `/visit/${visitId}`;
}

/** Canonical patient-detail path for a patient id (cross-visit history). */
export function patientPath(patientId: string): string {
  return `/patients/${patientId}`;
}

/**
 * Bind a react-router navigate fn into a one-call chart opener:
 *   const openChart = makeOpenChart(navigate);
 *   <button onClick={() => openChart(visitId)} />
 */
export function makeOpenChart(navigate: (path: string) => void) {
  return (visitId: string) => navigate(chartPath(visitId));
}
