import { get, post, put } from './client';

export interface ClinicalPathway {
  id: string;
  pathwayCode: string;
  pathwayName: string;
  category: string;
  description: string | null;
  targetPopulation: string | null;
  protocolVersion: string | null;
  sourceGuideline: string | null;
}

export interface PathwayStep {
  id: string;
  pathwayId: string;
  stepOrder: number;
  stepTitle: string;
  stepDescription: string;
  timeframeMinutes: number | null;
  isMandatory: boolean;
  category: string | null;
}

// NB: the activation endpoints (activate / getActive / complete / abandon) return the
// backend PathwayActivationResponse, which is the activation header ONLY — it carries NO
// step list. The per-step checklist comes from the dedicated progress() call below
// (PathwayProgress.steps). Do NOT add a `steps` field here: with non_null serialization a
// never-sent field arrives as `undefined`, which previously made the standalone view's
// Active tab render an empty checklist for every activation.
export interface PathwayActivation {
  id: string;
  visitId: string;
  pathwayId: string;
  pathwayName: string;
  activatedAt: string;
  activatedByName: string;
  completedAt: string | null;
  status: string;
  deviationReason: string | null;
}

export interface PathwayRecommendation {
  pathwayId: string;
  pathwayName: string;
  pathwayCode: string;
  reason: string;
  urgency: string;        // HIGH | MEDIUM | LOW (backend PathwayRecommendation.urgency)
  confidence: number;     // 0..1 (backend serializes a double)
}

/** Mirrors the backend PathwayProgressResponse.StepProgress (the full step list with live status). */
export interface PathwayStepProgress {
  stepId: string;
  stepOrder: number;
  stepTitle: string;
  category: string | null;
  isMandatory: boolean;
  timeframeMinutes: number | null;
  status: 'COMPLETED' | 'SKIPPED' | 'PENDING' | 'OVERDUE' | string;
  completedAt: string | null;
  completedByName: string | null;
  timeToCompleteMinutes: number | null;
  skipReason: string | null;
}

/** Mirrors the backend PathwayProgressResponse (full step list + counts). */
export interface PathwayProgress {
  activationId: string;
  pathwayId: string;
  pathwayName: string;
  status: string;
  activatedAt: string;
  totalSteps: number;
  completedSteps: number;
  skippedSteps: number;
  pendingSteps: number;
  completionPercentage: number;
  steps: PathwayStepProgress[];
  overdueSteps: string[];
}

export const pathwayApi = {
  getAll: () => get<ClinicalPathway[]>('/pathways'),
  getSteps: (pathwayId: string) => get<PathwayStep[]>(`/pathways/${pathwayId}/steps`),
  recommend: (visitId: string) => post<PathwayRecommendation[]>(`/pathways/recommend/${visitId}`),
  activate: (data: { visitId: string; pathwayId: string }) => post<PathwayActivation>('/pathways/activate', data),
  completeStep: (activationId: string, stepId: string, data?: { notes?: string }) => put<void>(`/pathways/activation/${activationId}/step/${stepId}/complete`, data),
  skipStep: (activationId: string, stepId: string, data: { reason: string }) => put<void>(`/pathways/activation/${activationId}/step/${stepId}/skip`, data),
  completePathway: (activationId: string) => put<PathwayActivation>(`/pathways/activation/${activationId}/complete`),
  abandonPathway: (activationId: string, reason: string) => put<PathwayActivation>(`/pathways/activation/${activationId}/abandon`, { reason }),
  getActive: (visitId: string) => get<PathwayActivation[]>(`/pathways/visit/${visitId}/active`),
  // Full step list with live PENDING / OVERDUE / COMPLETED / SKIPPED status + counts.
  progress: (activationId: string) => get<PathwayProgress>(`/pathways/activation/${activationId}/progress`),
};
