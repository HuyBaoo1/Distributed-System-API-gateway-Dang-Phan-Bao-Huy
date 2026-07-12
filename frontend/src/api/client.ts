import {
  Health,
  RequestLogPage,
  Route,
  RoutePayload,
  SystemStatus,
  Tenant,
  TenantPayload,
  UsageSummary,
} from '../types/api';
import { clearSession, getAdminToken, getApiBaseUrl } from './session';

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  auth?: boolean;
  redirectOnAuthError?: boolean;
  signal?: AbortSignal;
};

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const baseUrl = getApiBaseUrl();
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12000);
  const headers: Record<string, string> = { Accept: 'application/json' };
  const signal = options.signal || controller.signal;

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  if (options.auth !== false) {
    const token = getAdminToken();
    if (token) {
      headers['X-Admin-Token'] = token;
    }
  }

  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal,
    });

    const text = await response.text();
    const payload = text ? safeJson(text) : null;

    if (!response.ok) {
      const message = typeof payload?.error === 'string'
        ? payload.error
        : typeof payload?.message === 'string'
          ? payload.message
          : `Request failed with HTTP ${response.status}`;
      if ((response.status === 401 || response.status === 403) && options.redirectOnAuthError !== false) {
        clearSession();
        window.location.assign('/signin');
      }
      throw new ApiError(response.status, message);
    }

    return payload as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError(408, 'Request timed out');
    }
    throw new ApiError(0, error instanceof Error ? error.message : 'Network request failed');
  } finally {
    window.clearTimeout(timeout);
  }
}

function safeJson(text: string) {
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

export const api = {
  health: () => request<Health>('/admin/health', { auth: false }),
  actuatorHealth: () => request<unknown>('/actuator/health', { auth: false }),
  verifyAdmin: () => request<Tenant[]>('/admin/tenants', { redirectOnAuthError: false }),
  tenants: () => request<Tenant[]>('/admin/tenants'),
  createTenant: (payload: TenantPayload) => request<Tenant>('/admin/tenants', { method: 'POST', body: payload }),
  updateTenant: (tenantId: string, payload: TenantPayload) =>
    request<Tenant>(`/admin/tenants/${encodeURIComponent(tenantId)}`, { method: 'PUT', body: payload }),
  routes: () => request<Route[]>('/admin/routes'),
  createRoute: (payload: RoutePayload) => request<Route>('/admin/routes', { method: 'POST', body: payload }),
  updateRoute: (routeId: string, payload: RoutePayload) =>
    request<Route>(`/admin/routes/${encodeURIComponent(routeId)}`, { method: 'PUT', body: payload }),
  usageSummary: () => request<UsageSummary>('/admin/usage/summary'),
  requestLogs: (page = 0, size = 25) => request<RequestLogPage>(`/admin/request-logs?page=${page}&size=${size}`),
  systemStatus: () => request<SystemStatus>('/admin/system/status'),
};
