import { get, post } from './client';
import type {
  CreateShiftSwapRequest,
  ShiftSwapResponse,
  SwapDecisionRequest,
} from './types';

export const swapApi = {
  /** Propose a swap. Requester assignment must belong to the authenticated user. */
  propose: (body: CreateShiftSwapRequest) =>
    post<ShiftSwapResponse>('/shifts/swaps', body),

  /** Partner accepts; status moves to PENDING_CHARGE_APPROVAL. */
  partnerAccept: (swapId: string, body?: SwapDecisionRequest) =>
    post<ShiftSwapResponse>(`/shifts/swaps/${swapId}/partner-accept`, body ?? {}),

  /** Partner declines; terminal REJECTED. */
  partnerReject: (swapId: string, body?: SwapDecisionRequest) =>
    post<ShiftSwapResponse>(`/shifts/swaps/${swapId}/partner-reject`, body ?? {}),

  /** Either participant cancels; terminal CANCELLED. */
  cancel: (swapId: string) =>
    post<ShiftSwapResponse>(`/shifts/swaps/${swapId}/cancel`, {}),

  /** CN approves — applies the user-exchange to both ShiftAssignment rows. */
  chargeApprove: (swapId: string, body?: SwapDecisionRequest) =>
    post<ShiftSwapResponse>(`/shifts/swaps/${swapId}/charge-approve`, body ?? {}),

  /** CN declines (note required). */
  chargeReject: (swapId: string, body: SwapDecisionRequest) =>
    post<ShiftSwapResponse>(`/shifts/swaps/${swapId}/charge-reject`, body),

  /** Open swaps the authenticated user is involved in (either side). */
  myOpen: () => get<ShiftSwapResponse[]>('/shifts/swaps/me/open'),

  /** Full swap history for the authenticated user. */
  myHistory: () => get<ShiftSwapResponse[]>('/shifts/swaps/me/history'),

  /** CN approval queue at a hospital. */
  chargeQueue: (hospitalId: string) =>
    get<ShiftSwapResponse[]>(
      `/shifts/swaps/hospital/${hospitalId}/charge-queue`,
    ),
};
