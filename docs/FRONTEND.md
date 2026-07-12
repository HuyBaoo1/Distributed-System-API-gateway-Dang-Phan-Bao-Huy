# GateShield Console

GateShield Console is the React admin UI for operating a self-hosted GateShield gateway.

## Stack

- React
- TypeScript
- Vite
- React Router
- TanStack Query
- Plain CSS
- Vitest and React Testing Library

## Local Development

```bash
cd frontend
npm install
npm run dev
```

By default, Vite runs on `http://localhost:5173` and proxies `/admin`, `/actuator`, and `/api` to `http://localhost:8080`.

Optional local env:

```env
VITE_GATESHIELD_API_BASE_URL=http://localhost:8080
VITE_PERSIST_ADMIN_SESSION=false
```

The admin token is stored in session storage by default. It is not stored permanently unless `VITE_PERSIST_ADMIN_SESSION=true`.

## Docker

The production console is served by Nginx:

```bash
docker compose up --build
```

Then open:

```text
http://localhost:3000
```

The frontend container proxies API calls to the `gateshield` service over the private Docker network.

## Sign-In

Use the same admin token configured as `GATESHIELD_ADMIN_TOKEN`.

The UI sends:

```text
X-Admin-Token: <token>
```

## Implemented Pages

- Sign in
- Overview
- Tenants
- Routes
- Usage
- Request logs
- System status
- Developer guide

## Backend Endpoints Used

- `GET /admin/health`
- `GET /admin/tenants`
- `POST /admin/tenants`
- `PUT /admin/tenants/{tenantId}`
- `GET /admin/routes`
- `POST /admin/routes`
- `PUT /admin/routes/{routeId}`
- `GET /admin/usage/summary`
- `GET /admin/request-logs`
- `GET /admin/system/status`
- `GET /actuator/health`

## Limitations

- Route deletion is not shown because the backend does not expose a delete endpoint.
- API key rotation is not shown as a separate action; updating a tenant with a new `apiKey` is supported by the backend but should be exposed carefully later.
- Usage charts are aggregate-only because the backend currently exposes summary and recent logs, not time-series buckets.
