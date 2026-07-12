import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { MetricCard, Panel } from '../components/ui';
import { ms, number } from '../utils/format';

export function UsagePage() {
  const usage = useQuery({ queryKey: ['usage'], queryFn: api.usageSummary });
  const total = usage.data?.totalRequests || 0;
  const bars = [
    { label: 'Allowed', value: usage.data?.allowedRequests || 0 },
    { label: 'Rate limited', value: usage.data?.rateLimitedRequests || 0 },
    { label: 'Unauthorized', value: usage.data?.unauthorizedRequests || 0 },
  ];

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Usage</h1>
          <p>Aggregated usage from persisted GateShield request logs.</p>
        </div>
      </header>
      <section className="metric-grid">
        <MetricCard label="Total requests" value={number(total)} />
        <MetricCard label="Allowed" value={number(usage.data?.allowedRequests)} />
        <MetricCard label="429 responses" value={number(usage.data?.rateLimitedRequests)} />
        <MetricCard label="401 responses" value={number(usage.data?.unauthorizedRequests)} />
        <MetricCard label="Gateway latency" value={ms(usage.data?.avgGatewayLatencyMs)} />
        <MetricCard label="Backend latency" value={ms(usage.data?.avgBackendLatencyMs)} />
      </section>
      <Panel title="Status distribution">
        <div className="bar-list">
          {bars.map((bar) => (
            <div className="bar-row" key={bar.label}>
              <span>{bar.label}</span>
              <div className="bar-track"><div style={{ width: `${total ? (bar.value / total) * 100 : 0}%` }} /></div>
              <strong>{number(bar.value)}</strong>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  );
}
