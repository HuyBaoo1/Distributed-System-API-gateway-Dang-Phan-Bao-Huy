import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { Badge, EmptyState, ErrorState, LoadingState, MetricCard, Panel } from '../components/ui';
import { dateTime, ms, number, statusTone } from '../utils/format';

export function OverviewPage() {
  const tenants = useQuery({ queryKey: ['tenants'], queryFn: api.tenants });
  const routes = useQuery({ queryKey: ['routes'], queryFn: api.routes });
  const usage = useQuery({ queryKey: ['usage'], queryFn: api.usageSummary });
  const logs = useQuery({ queryKey: ['logs', 0, 8], queryFn: () => api.requestLogs(0, 8) });

  const error = tenants.error || routes.error || usage.error || logs.error;
  const errorRate = usage.data && usage.data.totalRequests > 0
    ? ((usage.data.rateLimitedRequests + usage.data.unauthorizedRequests) / usage.data.totalRequests) * 100
    : 0;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Overview</h1>
          <p>Live operational summary from GateShield admin APIs.</p>
        </div>
      </header>
      {error ? <ErrorState message={error instanceof Error ? error.message : 'Unable to load overview.'} /> : null}
      {(tenants.isPending || routes.isPending || usage.isPending || logs.isPending) && !error ? (
        <LoadingState title="Loading operations overview" detail="Refreshing tenants, routes, usage, and recent traffic." />
      ) : null}
      <section className="metric-grid">
        <MetricCard label="Tenants" value={number(tenants.data?.length)} hint={`${tenants.data?.filter((t) => t.enabled).length ?? 0} enabled`} />
        <MetricCard label="Active routes" value={number(routes.data?.filter((r) => r.enabled).length)} hint={`${routes.data?.length ?? 0} configured`} />
        <MetricCard label="Total requests" value={number(usage.data?.totalRequests)} hint="Persisted request logs" />
        <MetricCard label="Rate limited" value={number(usage.data?.rateLimitedRequests)} hint={`${errorRate.toFixed(1)}% reject/error rate`} />
        <MetricCard label="Gateway latency" value={ms(usage.data?.avgGatewayLatencyMs)} hint="Average" />
        <MetricCard label="Backend latency" value={ms(usage.data?.avgBackendLatencyMs)} hint="Average" />
      </section>

      <Panel title="Recent request activity">
        {logs.data?.items.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Route</th>
                  <th>Path</th>
                  <th>Status</th>
                  <th>Decision</th>
                  <th>Latency</th>
                </tr>
              </thead>
              <tbody>
                {logs.data.items.map((log) => (
                  <tr key={`${log.requestId}-${log.timestamp}`}>
                    <td>{dateTime(log.timestamp)}</td>
                    <td><code>{log.routeId || '-'}</code></td>
                    <td><code>{log.path}</code></td>
                    <td><Badge tone={statusTone(log.statusCode)}>{log.statusCode}</Badge></td>
                    <td>{log.rateLimitDecision}</td>
                    <td>{ms(log.gatewayLatencyMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState title="No request logs yet" detail="Call a protected API route to populate this view." />
        )}
      </Panel>
    </div>
  );
}
