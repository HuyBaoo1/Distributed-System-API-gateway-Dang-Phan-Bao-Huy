export type Tenant = {
  id: string;
  name: string;
  planName: string;
  enabled: boolean;
  createdAt: string;
  apiKey?: string | null;
};

export type Route = {
  routeId: string;
  pathPattern: string;
  targetUrl: string;
  allowedMethods: string[];
  enabled: boolean;
  rateLimitRequests: number;
  rateLimitWindowSeconds: number;
  createdAt: string;
  updatedAt: string;
};

export type UsageSummary = {
  totalRequests: number;
  allowedRequests: number;
  rateLimitedRequests: number;
  unauthorizedRequests: number;
  avgGatewayLatencyMs: number;
  avgBackendLatencyMs: number;
};

export type Health = {
  status: string;
  service?: string;
  timestamp?: string;
};

export type RequestLog = {
  timestamp: string;
  tenantId?: string | null;
  routeId?: string | null;
  method: string;
  path: string;
  statusCode: number;
  gatewayLatencyMs: number;
  backendLatencyMs: number;
  rateLimitDecision: string;
  clientIp?: string | null;
  requestId?: string | null;
};

export type RequestLogPage = {
  page: number;
  size: number;
  total: number;
  items: RequestLog[];
};

export type SystemStatus = {
  generatedAt: string;
  health: Health;
  tenants: number;
  routes: number;
  usage: UsageSummary;
  latency?: unknown;
};

export type TenantPayload = {
  id?: string;
  name?: string;
  apiKey?: string;
  planName?: string;
  enabled?: boolean;
};

export type RoutePayload = {
  routeId?: string;
  pathPattern?: string;
  targetUrl?: string;
  allowedMethods?: string[];
  enabled?: boolean;
  rateLimitRequests?: number;
  rateLimitWindowSeconds?: number;
};
