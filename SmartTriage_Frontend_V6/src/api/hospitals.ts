/* ── Hospitals API ── */
import { get, post, put, del } from './client';
import type { CreateHospitalRequest, HospitalResponse, Page } from './types';

export const hospitalApi = {
  create: (data: Partial<CreateHospitalRequest>) =>
    post<HospitalResponse>('/hospitals', data),

  update: (id: string, data: Partial<CreateHospitalRequest>) =>
    put<HospitalResponse>(`/hospitals/${id}`, data),

  getById: (id: string) =>
    get<HospitalResponse>(`/hospitals/${id}`),

  getByCode: (code: string) =>
    get<HospitalResponse>(`/hospitals/code/${code}`),

  getAll: (page = 0, size = 20) =>
    get<Page<HospitalResponse>>(`/hospitals?page=${page}&size=${size}`),

  delete: (id: string) =>
    del<void>(`/hospitals/${id}`),
};
