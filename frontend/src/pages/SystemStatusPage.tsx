import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { Badge, MetricCard, Panel } from '../components/ui';
import { dateTime, number } from '../utils/format';

export function SystemStatusPage() {
  const status = useQuery({ queryKey: ['system-status'], queryFn: api.systemStatus, refetchInterval: 30000 });
  const actuator = useQuery({ queryKey: ['actuator-health'], queryFn: api.actuatorHealth, refetchInterval: 30000 });

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>System Status</h1>
          <p>Service health and runtime counts exposed by GateShield.</p>
        </div>
      </header>
      <section className="metric-grid">
        <MetricCard label="Admin health" value={<Badge tone={status.data?.health.status === 'ok' ? 'good' : 'bad'}>{status.data?.health.status || 'unknown'}</Badge>} />
        <MetricCard label="Actuator health" value={<Badge tone={actuator.isError ? 'bad' : 'good'}>{actuator.isError ? 'unreachable' : 'available'}</Badge>} />
        <MetricCard label="Tenants" value={number(status.data?.tenants)} />
        <MetricCard label="Routes" value={number(status.data?.routes)} />
      </section>
      <Panel title="Runtime details">
        <dl className="details-list">
          <div><dt>Service</dt><dd>{status.data?.health.service || 'GateShield'}</dd></div>
          <div><dt>Generated at</dt><dd>{dateTime(status.data?.generatedAt)}</dd></div>
          <div><dt>Total requests</dt><dd>{number(status.data?.usage.totalRequests)}</dd></div>
          <div><dt>Rate limited requests</dt><dd>{number(status.data?.usage.rateLimitedRequests)}</dd></div>
        </dl>
      </Panel>
    </div>
  );
}
