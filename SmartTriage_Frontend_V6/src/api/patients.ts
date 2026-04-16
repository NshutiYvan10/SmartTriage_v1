/* ── Patients API ── */
import { get, post } from './client';
import type { CreatePatientRequest, PatientResponse, RegisterPatientRequest, RegisterPatientResponse, Page } from './types';

export const patientApi = {
  create: (data: CreatePatientRequest) =>
    post<PatientResponse>('/patients', data),

  /** Combined registration — creates Patient + Visit in one atomic backend transaction */
  register: (data: RegisterPatientRequest) =>
    post<RegisterPatientResponse>('/patients/register', data),

  getById: (id: string) =>
    get<PatientResponse>(`/patients/${id}`),

  listByHospital: (hospitalId: string, page = 0, size = 20) =>
    get<Page<PatientResponse>>(`/patients/hospital/${hospitalId}?page=${page}&size=${size}`),

  search: (hospitalId: string, query: string, page = 0, size = 20) =>
    get<Page<PatientResponse>>(`/patients/hospital/${hospitalId}/search?query=${encodeURIComponent(query)}&page=${page}&size=${size}`),
};
