/* ── WebSocket Client for Real-Time Features ── */
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getAccessToken } from './client';

const WS_URL = '/ws/smarttriage';

let stompClient: Client | null = null;
const subscriptions = new Map<string, { unsubscribe: () => void }>();

/**
 * Connection "generation" — incremented on every successful (re)connect.
 *
 * The app-level alert hook (useWebSocket) tears down and rebuilds the single
 * shared client whenever the user's covered zones change. That drops EVERY
 * ad-hoc subscription in the map — including feature subscriptions like the
 * sepsis dashboard's — and only re-subscribes the alert topics. Feature hooks
 * therefore include this generation in their effect deps (via
 * useWebSocketGeneration) so they RE-establish their subscription after any
 * reconnect instead of silently going dead.
 */
let connectionGeneration = 0;
const connectionListeners = new Set<(gen: number) => void>();

export function getConnectionGeneration(): number {
  return connectionGeneration;
}

export function subscribeConnectionState(listener: (gen: number) => void): () => void {
  connectionListeners.add(listener);
  return () => { connectionListeners.delete(listener); };
}

function notifyConnectionListeners() {
  connectionGeneration += 1;
  connectionListeners.forEach((l) => {
    try { l(connectionGeneration); } catch { /* a listener error must not break the socket */ }
  });
}

/**
 * Pending subscriptions queued while the STOMP client is activating.
 *
 * Why this exists: `Client.active` flips to `true` the moment we call
 * `activate()`, but the underlying TCP/SockJS handshake hasn't completed
 * yet — `Client.connected` is still false. Any code that calls
 * `stompClient.subscribe(...)` in that gap throws synchronously with
 * "There is no underlying STOMP connection." On the dashboard,
 * `InboundEmsBoard` and `CriticalLabBanner` mount and try to subscribe
 * before the handshake finishes, so the throw escapes their useEffects
 * and unmounts the React tree — the exact "blank page" failure the
 * ErrorBoundary now catches.
 *
 * The fix: when called before connected, stash the (topic, callback)
 * and let the onConnect handler flush the queue. Returns an unsubscribe
 * function that works whether the subscribe has flushed yet or not.
 */
interface PendingSubscription {
  topic: string;
  rawCallback: (message: IMessage) => void;
  cancelled: boolean;
}
const pendingSubscriptions: PendingSubscription[] = [];

/**
 * RECONNECT-DURABLE subscription registry.
 *
 * STOMP subscriptions live on the CONNECTION: when the client's
 * auto-reconnect kicks in (heartbeat blip, backend restart, WiFi drop),
 * every server-side subscription is gone. The previous implementation
 * only flushed the pre-connect queue on (re)connect — subscriptions that
 * had been established while live were left pointing at the dead
 * connection and silently received nothing forever. That is exactly the
 * "monitoring page freezes until I switch pages and come back" bug:
 * remounting the page re-subscribed by accident.
 *
 * Every subscribeToTopic call now registers here for the lifetime of its
 * unsubscribe function, and onConnect re-subscribes EVERY active record —
 * queued or previously live — so a reconnect is invisible to features.
 */
const durableSubs: PendingSubscription[] = [];

function flushPendingSubscriptions() {
  if (!stompClient?.connected) return;
  // Adopt anything still queued into the durable registry, then
  // (re)subscribe every active record against the fresh connection.
  const pending = pendingSubscriptions.splice(0);
  for (const p of pending) {
    if (!p.cancelled) durableSubs.push(p);
  }
  // Drop cancelled records so the registry can't grow unbounded.
  for (let i = durableSubs.length - 1; i >= 0; i--) {
    if (durableSubs[i].cancelled) durableSubs.splice(i, 1);
  }
  for (const p of durableSubs) {
    try {
      const stale = subscriptions.get(p.topic);
      if (stale) {
        try { stale.unsubscribe(); } catch { /* dead connection — expected */ }
        subscriptions.delete(p.topic);
      }
      const sub = stompClient.subscribe(p.topic, p.rawCallback);
      subscriptions.set(p.topic, sub);
    } catch (err) {
      console.warn('[WS] Failed to (re)subscribe to', p.topic, err);
    }
  }
}

export function connectWebSocket(onConnect?: () => void): Client {
  if (stompClient?.active) return stompClient;

  stompClient = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    // Authenticate the STOMP CONNECT with the current access token. Set fresh on EVERY
    // (re)connect so a token refreshed since the last attempt is used — the backend rejects
    // a CONNECT without a valid bearer token (per-tenant WebSocket security), so a stale
    // token here would otherwise wedge the reconnect loop.
    beforeConnect: () => {
      const token = getAccessToken();
      stompClient!.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    },
    onConnect: () => {
      console.log('[WS] Connected to SmartTriage WebSocket');
      // Flush BEFORE the caller's onConnect so any callbacks they
      // register against existing topics see the right state.
      flushPendingSubscriptions();
      onConnect?.();
      // Tell feature hooks the (re)connection happened so they can re-establish
      // any ad-hoc subscription a prior teardown dropped.
      notifyConnectionListeners();
    },
    onDisconnect: () => {
      console.log('[WS] Disconnected');
    },
    onStompError: (frame) => {
      console.error('[WS] STOMP error:', frame.headers.message);
    },
  });

  stompClient.activate();
  return stompClient;
}

export function disconnectWebSocket() {
  subscriptions.forEach((sub) => sub.unsubscribe());
  subscriptions.clear();
  // Cancel any still-queued subscribes so a late connect doesn't
  // resurrect them against the new client — and clear the durable
  // registry (feature hooks re-establish via the generation listener
  // after the app-level hook rebuilds the client).
  for (const p of pendingSubscriptions) p.cancelled = true;
  pendingSubscriptions.length = 0;
  for (const p of durableSubs) p.cancelled = true;
  durableSubs.length = 0;
  if (stompClient?.active) {
    stompClient.deactivate();
  }
  stompClient = null;
}

export function subscribeToTopic<T>(
  topic: string,
  callback: (data: T) => void
): () => void {
  // Shared raw handler so the pre-connect queue and post-connect path
  // dispatch identically. Parse failures are logged, never thrown.
  const rawCallback = (message: IMessage) => {
    try {
      const data = JSON.parse(message.body) as T;
      callback(data);
    } catch (e) {
      console.error('[WS] Failed to parse message on', topic, e);
    }
  };

  // Drop any prior subscription to this topic — de-duplication across
  // the live-sub map, the pending queue, and the durable registry.
  if (subscriptions.has(topic)) {
    try { subscriptions.get(topic)!.unsubscribe(); } catch { /* dead connection */ }
    subscriptions.delete(topic);
  }
  for (const p of pendingSubscriptions) {
    if (p.topic === topic) p.cancelled = true;
  }
  for (const p of durableSubs) {
    if (p.topic === topic) p.cancelled = true;
  }

  // One record per subscription, registered for reconnect durability.
  // Its unsubscribe works whether the subscribe has flushed yet or not,
  // and cancelling it removes the record from every future reconnect.
  const record: PendingSubscription = { topic, rawCallback, cancelled: false };
  const unsubscribe = () => {
    record.cancelled = true;
    const live = subscriptions.get(topic);
    if (live) {
      try { live.unsubscribe(); } catch { /* client may already be torn down */ }
      subscriptions.delete(topic);
    }
  };

  // Not connected yet — queue; the connect handler adopts the record
  // into the durable registry and subscribes it.
  if (!stompClient?.connected) {
    pendingSubscriptions.push(record);
    return unsubscribe;
  }

  // Live path: register durably AND subscribe now. Wrap in try/catch —
  // STOMP can throw if the connection drops between the `connected`
  // check and the subscribe call; the durable registry picks the
  // record up on the next reconnect either way.
  durableSubs.push(record);
  try {
    const subscription = stompClient.subscribe(topic, rawCallback);
    subscriptions.set(topic, subscription);
  } catch (err) {
    console.warn('[WS] subscribe threw for', topic, '— will retry on reconnect:', err);
  }
  return unsubscribe;
}

// ── Typed subscription helpers ──

import type { VitalStreamResponse, ClinicalAlertResponse, ClinicalNoteResponse, EdZone } from './types';

export function subscribeToVitals(
  visitId: string,
  callback: (vital: VitalStreamResponse) => void
): () => void {
  return subscribeToTopic(`/topic/vitals/${visitId}`, callback);
}

export function subscribeToAlerts(
  hospitalId: string,
  callback: (alert: ClinicalAlertResponse) => void
): () => void {
  return subscribeToTopic(`/topic/alerts/${hospitalId}`, callback);
}

/** Subscribe to EMS / paramedic-run events for a hospital. Payload is EmsRun. */
export function subscribeToEmsRuns(
  hospitalId: string,
  callback: (run: any) => void
): () => void {
  return subscribeToTopic(`/topic/ems/${hospitalId}`, callback);
}

/**
 * B4 — subscribe to visit/admission events for a hospital. Payload is a
 * lightweight { type, visitId, hospitalId }; subscribers re-fetch the
 * active-visit list so a new admission appears live.
 */
export function subscribeToVisits(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/visits/${hospitalId}`, callback);
}

/** Subscribe to lab-order events for a hospital. Payload is LabOrder. */
export function subscribeToLabOrders(
  hospitalId: string,
  callback: (labOrder: any) => void
): () => void {
  return subscribeToTopic(`/topic/lab/${hospitalId}`, callback);
}

/**
 * Subscribe to imaging/ECG diagnostics-worklist events for a hospital.
 * Dedicated topic (/topic/diagnostics/{hospitalId}) — the imaging-worklist
 * equivalent of the lab inbox stream. Payload is a compact hint
 * ({type, investigationId, status, …}); the view re-fetches on any message.
 */
export function subscribeToDiagnostics(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/diagnostics/${hospitalId}`, callback);
}

/**
 * Subscribe to sepsis events for a hospital. Uses a DEDICATED topic
 * (/topic/sepsis/{hospitalId}) rather than the alert topics, because the
 * subscription map allows only one subscriber per topic string and the
 * app-wide alert hook (useWebSocket) already owns /topic/alerts/* — reusing
 * those would clobber the global alert toasts. Payload is a small
 * { eventType, visitId, sepsisStatus } map; subscribers refetch on any event.
 */
export function subscribeToSepsis(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/sepsis/${hospitalId}`, callback);
}

/**
 * Subscribe to fast-track (stroke / STEMI) events for a hospital. Dedicated
 * topic (same rationale as subscribeToSepsis — avoids clobbering the app-wide
 * alert subscription). Payload is a small { eventType, visitId } map; refetch
 * on any event.
 */
export function subscribeToFastTrack(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/fasttrack/${hospitalId}`, callback);
}

/**
 * Subscribe to governance events for a hospital — fires when one of its clinicians breaks the
 * glass to access a cross-hospital deep record. Dedicated topic (same rationale as the others);
 * payload is a small { eventType, eventId, actorName, accessedAt } map. The Override Audit page's
 * break-the-glass feed refetches on any event.
 */
export function subscribeToGovernance(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/governance/${hospitalId}`, callback);
}

/**
 * Subscribe to RFID registration-reader events for a hospital (V95): a card tap that FOUND /
 * did NOT find a patient, or a tap-to-capture CARD_BIND for the registration form. Dedicated
 * topic, hospital-scoped (SUBSCRIBE authz = canAccessHospital). Payload is a small
 * { type, cardId, patientName?, identityId?, nationalId?, linkedHospitalCount? } map.
 *
 * ── Fan-out multiplexer ──
 * The RFID topic uniquely has MANY concurrent UI consumers: the always-on desk listener
 * mounted at the app root, PLUS the page/modal card-capture flows (registration form,
 * identity resolution, replace-card). The generic subscribeToTopic enforces
 * one-subscriber-per-topic — a new subscribe drops the prior one — so a naive shared
 * subscription would let whichever component mounted last silently steal the channel, and
 * unmounting it would leave the topic with NO subscriber (the desk goes deaf). We multiplex
 * here instead: ONE underlying topic subscription fanned out to every registered listener.
 * First listener opens it; last listener closes it; a reconnect (connection-generation bump)
 * re-establishes it centrally, so no consumer has to track the generation itself.
 */
let rfidTopic: string | null = null;
let rfidUnsub: (() => void) | null = null;
let rfidConnUnsub: (() => void) | null = null;
const rfidListeners = new Set<(event: any) => void>();

function openRfidSubscription() {
  if (!rfidTopic) return;
  rfidUnsub = subscribeToTopic(rfidTopic, (event: any) => {
    // Snapshot the set — a listener may unsubscribe during dispatch.
    for (const listener of [...rfidListeners]) {
      try { listener(event); } catch (e) { console.error('[WS] rfid listener threw', e); }
    }
  });
}

export function subscribeToRfidEvents(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  const topic = `/topic/rfid/${hospitalId}`;
  // Hospital changed (e.g. re-login as a different hospital's user): tear the whole
  // fan-out down before rebuilding for the new topic.
  if (rfidTopic && rfidTopic !== topic) {
    rfidUnsub?.(); rfidUnsub = null;
    rfidConnUnsub?.(); rfidConnUnsub = null;
    rfidListeners.clear();
    rfidTopic = null;
  }
  rfidListeners.add(callback);
  if (!rfidTopic) {
    rfidTopic = topic;
    openRfidSubscription();
    // Re-establish the single underlying subscription after any reconnect / client rebuild
    // (the generic layer drops live subs then), so every fan-out consumer survives it.
    rfidConnUnsub = subscribeConnectionState(() => {
      rfidUnsub?.();
      openRfidSubscription();
    });
  }
  return () => {
    rfidListeners.delete(callback);
    if (rfidListeners.size === 0) {
      rfidUnsub?.(); rfidUnsub = null;
      rfidConnUnsub?.(); rfidConnUnsub = null;
      rfidTopic = null;
    }
  };
}

/**
 * Subscribe to hypoglycemia events for a hospital. Dedicated topic (same
 * one-subscriber-per-topic rationale as sepsis/fast-track). Payload is a small
 * { eventType, visitId } map; refetch on any event.
 */
export function subscribeToHypoglycemia(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/hypoglycemia/${hospitalId}`, callback);
}

/**
 * Subscribe to infection-isolation events for a hospital. Dedicated topic (same
 * one-subscriber-per-topic rationale as sepsis/fast-track/hypoglycemia). Payload
 * is a small { eventType, visitId } map (SCREENED / ROOM_ASSIGNED / CLEARED /
 * NOTIFIED / PLACEMENT_OVERDUE); refetch on any event.
 */
export function subscribeToIsolation(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/isolation/${hospitalId}`, callback);
}

/**
 * Subscribe to clinical-pathway events for a hospital. Dedicated topic (same
 * one-subscriber-per-topic rationale as the other tools). Payload is a small
 * { eventType, visitId } map (ACTIVATED / STEP_COMPLETED / STEP_SKIPPED /
 * STEP_OVERDUE / COMPLETED / ABANDONED); refetch on any event.
 */
export function subscribeToPathway(
  hospitalId: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/pathway/${hospitalId}`, callback);
}

/** Subscribe to medication events for a hospital (Workflow 3).
 *  Payload is a MedicationResponse — emitted on prescribe and every
 *  workflow transition (administer/countersign/hold/refuse/cancel).
 *  V67 additionally emits small {eventType: ...} maps on dose-level
 *  transitions; consumers should detect the shape and refetch. */
export function subscribeToMedications(
  hospitalId: string,
  callback: (med: any) => void
): () => void {
  return subscribeToTopic(`/topic/medications/${hospitalId}`, callback);
}

/**
 * V67 — zone-scoped medication events (dose due/overdue/missed/given,
 * order created/approved/discontinued, infusion events). The zone
 * nurse's medication board subscribes to its own zone; charge /
 * cross-zone roles use the hospital-wide topic above instead.
 */
export function subscribeToZoneMedications(
  hospitalId: string,
  zone: string,
  callback: (event: any) => void
): () => void {
  return subscribeToTopic(`/topic/medications/${hospitalId}/zone/${zone}`, callback);
}

/** Subscribe to alerts for a specific ED zone */
export function subscribeToZoneAlerts(
  hospitalId: string,
  zone: EdZone,
  callback: (alert: ClinicalAlertResponse) => void
): () => void {
  return subscribeToTopic(`/topic/alerts/${hospitalId}/${zone}`, callback);
}

/** Subscribe to alerts targeted at a specific user (doctor) */
export function subscribeToUserAlerts(
  userId: string,
  callback: (alert: ClinicalAlertResponse) => void
): () => void {
  return subscribeToTopic(`/topic/alerts/user/${userId}`, callback);
}

/**
 * Role-scoped alert channel (e.g. lab work-queue notices for LAB_TECHNICIAN).
 * Server-side SUBSCRIBE authz only admits callers whose OWN role matches the
 * topic role at their own hospital, so pass the logged-in user's role verbatim.
 */
export function subscribeToRoleAlerts(
  hospitalId: string,
  role: string,
  callback: (alert: ClinicalAlertResponse) => void
): () => void {
  return subscribeToTopic(`/topic/alerts/${hospitalId}/role/${role}`, callback);
}

export function subscribeToDevices(
  hospitalId: string,
  callback: (event: unknown) => void
): () => void {
  return subscribeToTopic(`/topic/devices/${hospitalId}`, callback);
}

export function subscribeToTriageChanges(
  visitId: string,
  callback: (event: unknown) => void
): () => void {
  return subscribeToTopic(`/topic/triage/${visitId}`, callback);
}

export interface TrendChangeEvent {
  visitId: string;
  sessionId: string;
  trendStatus: 'WORSENING' | 'STABLE' | 'IMPROVING' | 'UNKNOWN';
  previousTrendStatus?: string;
  /** Live detection annotation (DeteriorationPattern name, e.g. SEPSIS_PATTERN); null when clean/cleared. */
  detectedPattern?: string | null;
  detectedAt?: string | null;
  timestamp: string;
}

export function subscribeToTrendChanges(
  visitId: string,
  callback: (event: TrendChangeEvent) => void
): () => void {
  return subscribeToTopic(`/topic/trend/${visitId}`, callback);
}

/**
 * Subscribe to bed-occupancy changes hospital-wide. Payload carries
 * bedId/code/zone/status/event so every bed-grid view can decide
 * whether to re-fetch the affected zone.
 */
export interface BedChangeEvent {
  bedId: string;
  code: string;
  zone: string; // EdZone
  status: string; // BedStatus
  event: string; // CREATED | UPDATED | DELETED | PLACED | TRANSFERRED_IN | TRANSFERRED_OUT | DISCHARGED | RELEASED | CLEANED | OUT_OF_SERVICE | AVAILABLE | DEVICE_ASSIGNMENT
  hasOccupant: boolean;
  timestamp: string;
}

export function subscribeToBedChanges(
  hospitalId: string,
  callback: (event: BedChangeEvent) => void
): () => void {
  return subscribeToTopic(`/topic/beds/${hospitalId}`, callback);
}

/**
 * Subscribe to clinical-note events for a visit. Fires on both initial
 * creation and supersede (correction). Subscribers can detect a correction
 * via a non-null `supersedesId` on the payload.
 */
export function subscribeToClinicalNotes(
  visitId: string,
  callback: (note: ClinicalNoteResponse) => void
): () => void {
  return subscribeToTopic(`/topic/visit/${visitId}/notes`, callback);
}

export function getStompClient(): Client | null {
  return stompClient;
}
