/* ── WebSocket Client for Real-Time Features ── */
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const WS_URL = '/ws/smarttriage';

let stompClient: Client | null = null;
const subscriptions = new Map<string, { unsubscribe: () => void }>();

export function connectWebSocket(onConnect?: () => void): Client {
  if (stompClient?.active) return stompClient;

  stompClient = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    onConnect: () => {
      console.log('[WS] Connected to SmartTriage WebSocket');
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
  if (stompClient?.active) {
    stompClient.deactivate();
  }
  stompClient = null;
}

export function subscribeToTopic<T>(
  topic: string,
  callback: (data: T) => void
): () => void {
  if (!stompClient?.active) {
    console.warn('[WS] Not connected, cannot subscribe to', topic);
    return () => {};
  }

  // Prevent duplicate subscriptions
  if (subscriptions.has(topic)) {
    subscriptions.get(topic)!.unsubscribe();
    subscriptions.delete(topic);
  }

  const subscription = stompClient.subscribe(topic, (message: IMessage) => {
    try {
      const data = JSON.parse(message.body) as T;
      callback(data);
    } catch (e) {
      console.error('[WS] Failed to parse message on', topic, e);
    }
  });

  subscriptions.set(topic, subscription);

  return () => {
    subscription.unsubscribe();
    subscriptions.delete(topic);
  };
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

/** Subscribe to lab-order events for a hospital. Payload is LabOrder. */
export function subscribeToLabOrders(
  hospitalId: string,
  callback: (labOrder: any) => void
): () => void {
  return subscribeToTopic(`/topic/lab/${hospitalId}`, callback);
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
