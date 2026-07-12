import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { Badge, Button, EmptyState, ErrorState, Field, Input, LoadingState, Panel } from '../components/ui';
import { dateTime, ms, statusTone } from '../utils/format';

export function RequestLogsPage() {
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const logs = useQuery({ queryKey: ['logs', page], queryFn: () => api.requestLogs(page, 25) });
  const filtered = useMemo(() => {
    const value = query.toLowerCase();
    return (logs.data?.items || []).filter((log) =>
      [log.requestId, log.tenantId, log.routeId, log.path, log.clientIp, String(log.statusCode)]
        .filter(Boolean)
        .some((item) => String(item).toLowerCase().includes(value))
    );
  }, [logs.data?.items, query]);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Request Logs</h1>
          <p>Recent gateway requests. Secrets, headers, and bodies are not stored or displayed.</p>
        </div>
      </header>
      <Panel title="Recent logs" action={<Field label="Search"><Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="tenant, route, status..." /></Field>}>
        {logs.isPending ? <LoadingState title="Loading request logs" detail="Fetching the latest request history." /> : null}
        {logs.error ? <ErrorState message={logs.error instanceof Error ? logs.error.message : 'Unable to load request logs.'} /> : null}
        {filtered.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Timestamp</th><th>Request ID</th><th>Tenant</th><th>Route</th><th>Method</th><th>Path</th><th>Status</th><th>Decision</th><th>Gateway</th><th>Backend</th><th>Client IP</th></tr>
              </thead>
              <tbody>
                {filtered.map((log) => (
                  <tr key={`${log.requestId}-${log.timestamp}`}>
                    <td>{dateTime(log.timestamp)}</td>
                    <td><code>{log.requestId || '-'}</code></td>
                    <td><code>{log.tenantId || '-'}</code></td>
                    <td><code>{log.routeId || '-'}</code></td>
                    <td>{log.method}</td>
                    <td><code>{log.path}</code></td>
                    <td><Badge tone={statusTone(log.statusCode)}>{log.statusCode}</Badge></td>
                    <td>{log.rateLimitDecision}</td>
                    <td>{ms(log.gatewayLatencyMs)}</td>
                    <td>{ms(log.backendLatencyMs)}</td>
                    <td>{log.clientIp || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <EmptyState title="No matching logs" detail="Try another filter or generate traffic through the gateway." />}
        <div className="pagination">
          <Button variant="secondary" type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
          <span>Page {page + 1} of {Math.max(1, Math.ceil((logs.data?.total || 0) / 25))}</span>
          <Button variant="secondary" type="button" disabled={(page + 1) * 25 >= (logs.data?.total || 0)} onClick={() => setPage(page + 1)}>Next</Button>
        </div>
      </Panel>
    </div>
  );
}
