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

export interface PathwayActivation {
  id: string;
  visitId: string;
  pathwayId: string;
  pathwayName: string;
  activatedAt: string;
  activatedByName: string;
  completedAt: string | null;
  status: string;
  steps: PathwayStepCompletion[];
}

export interface PathwayStepCompletion {
  id: string;
  stepId: string;
  stepTitle: string;
  completedAt: string | null;
  completedByName: string | null;
  wasSkipped: boolean;
  skipReason: string | null;
  timeToCompleteMinutes: number | null;
}

export interface PathwayRecommendation {
  pathwayId: string;
  pathwayName: string;
  pathwayCode: string;
  reason: string;
  confidence: string;
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
  getProgress: (activationId: string) => get<PathwayActivation>(`/pathways/activation/${activationId}/progress`),
};
