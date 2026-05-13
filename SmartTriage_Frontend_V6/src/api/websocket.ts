/* ── WebSocket Client for Real-Time Features ── */
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const WS_URL = '/ws/smarttriage';

let stompClient: Client | null = null;
const subscriptions = new Map<string, { unsubscribe: () => void }>();

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

function flushPendingSubscriptions() {
  if (!stompClient?.connected) return;
  // Drain the queue. Take a snapshot first so a synchronous re-subscribe
  // (extremely unlikely but defensive) doesn't mutate the list under us.
  const pending = pendingSubscriptions.splice(0);
  for (const p of pending) {
    if (p.cancelled) continue;
    try {
      const sub = stompClient.subscribe(p.topic, p.rawCallback);
      subscriptions.set(p.topic, sub);
    } catch (err) {
      console.warn('[WS] Failed to flush pending subscription to', p.topic, err);
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
    onConnect: () => {
      console.log('[WS] Connected to SmartTriage WebSocket');
      // Flush BEFORE the caller's onConnect so any callbacks they
      // register against existing topics see the right state.
      flushPendingSubscriptions();
      onConnect?.();
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
  // resurrect them against the new client.
  for (const p of pendingSubscriptions) p.cancelled = true;
  pendingSubscriptions.length = 0;
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

  // Drop any prior subscription to this topic — same de-duplication as
  // before, applied across both the live-sub map and the pending queue.
  if (subscriptions.has(topic)) {
    subscriptions.get(topic)!.unsubscribe();
    subscriptions.delete(topic);
  }
  for (const p of pendingSubscriptions) {
    if (p.topic === topic) p.cancelled = true;
  }

  // Not connected yet — queue and return an unsubscribe that works
  // whether or not the subscribe has flushed when it's called.
  if (!stompClient?.connected) {
    const pending: PendingSubscription = { topic, rawCallback, cancelled: false };
    pendingSubscriptions.push(pending);
    return () => {
      pending.cancelled = true;
      const live = subscriptions.get(topic);
      if (live) {
        live.unsubscribe();
        subscriptions.delete(topic);
      }
    };
  }

  // Live path. Wrap in try/catch as a last-resort guard — STOMP can
  // throw if the connection drops between the `connected` check and
  // the subscribe call. Better a missed live channel than a blank UI.
  try {
    const subscription = stompClient.subscribe(topic, rawCallback);
    subscriptions.set(topic, subscription);
    return () => {
      subscription.unsubscribe();
      subscriptions.delete(topic);
    };
  } catch (err) {
    console.warn('[WS] subscribe threw for', topic, '— deferring until reconnect:', err);
    const pending: PendingSubscription = { topic, rawCallback, cancelled: false };
    pendingSubscriptions.push(pending);
    return () => {
      pending.cancelled = true;
      const live = subscriptions.get(topic);
      if (live) {
        live.unsubscribe();
        subscriptions.delete(topic);
      }
    };
  }
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

/** Subscribe to lab-order events for a hospital. Payload is LabOrder. */
export function subscribeToLabOrders(
  hospitalId: string,
  callback: (labOrder: any) => void
): () => void {
  return subscribeToTopic(`/topic/lab/${hospitalId}`, callback);
}

/** Subscribe to medication events for a hospital (Workflow 3).
 *  Payload is a MedicationResponse — emitted on prescribe and every
 *  workflow transition (administer/countersign/hold/refuse/cancel). */
export function subscribeToMedications(
  hospitalId: string,
  callback: (med: any) => void
): () => void {
  return subscribeToTopic(`/topic/medications/${hospitalId}`, callback);
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
  previousTrendStatus: string;
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
