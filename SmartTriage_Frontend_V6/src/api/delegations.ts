import { get, post } from './client';
import type {
  ChargeNurseDelegationResponse,
  CreateChargeNurseDelegationRequest,
  RevokeChargeNurseDelegationRequest,
} from './types';

export const delegationApi = {
  /** Create a new acting-CN delegation. CN/admin auth required. */
  create: (hospitalId: string, body: CreateChargeNurseDelegationRequest) =>
    post<ChargeNurseDelegationResponse>(
      `/shifts/delegations/hospital/${hospitalId}`,
      body,
    ),

  /** Revoke a delegation early (delegating CN, delegate, or admin). */
  revoke: (delegationId: string, body?: RevokeChargeNurseDelegationRequest) =>
    post<ChargeNurseDelegationResponse>(
      `/shifts/delegations/${delegationId}/revoke`,
      body ?? {},
    ),

  /** Currently-active delegations at a hospital. Drives the "Acting CN" badge. */
  listActive: (hospitalId: string) =>
    get<ChargeNurseDelegationResponse[]>(
      `/shifts/delegations/hospital/${hospitalId}/active`,
    ),

  /** Delegations issued by the authenticated user (their CN history). */
  listMyIssued: () =>
    get<ChargeNurseDelegationResponse[]>('/shifts/delegations/me/issued'),

  /** Times the authenticated user has been an acting CN. */
  listMyReceived: () =>
    get<ChargeNurseDelegationResponse[]>('/shifts/delegations/me/received'),
};
