import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { api } from '../api/client';
import { Badge, Button, EmptyState, ErrorState, Field, Input, LoadingState, Panel, Select } from '../components/ui';
import { Route, RoutePayload } from '../types/api';
import { dateTime } from '../utils/format';

const methods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'];

const emptyRoute: Required<RoutePayload> = {
  routeId: '',
  pathPattern: '/api/v1/**',
  targetUrl: 'http://mock-backend:8081',
  allowedMethods: ['GET'],
  enabled: true,
  rateLimitRequests: 60,
  rateLimitWindowSeconds: 60,
};

export function RoutesPage() {
  const queryClient = useQueryClient();
  const routes = useQuery({ queryKey: ['routes'], queryFn: api.routes });
  const [form, setForm] = useState(emptyRoute);
  const [methodInput, setMethodInput] = useState('GET');
  const [editing, setEditing] = useState<Route | null>(null);
  const [editingMethods, setEditingMethods] = useState('');

  const create = useMutation({
    mutationFn: api.createRoute,
    onSuccess: () => {
      setForm(emptyRoute);
      setMethodInput('GET');
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    },
  });

  const update = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: RoutePayload }) => api.updateRoute(id, payload),
    onSuccess: () => {
      setEditing(null);
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.routeId.trim() || !form.pathPattern.trim() || !form.targetUrl.trim()) return;
    create.mutate({ ...form, allowedMethods: splitMethods(methodInput) });
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Routes</h1>
          <p>Map public paths to backend targets and define route-level rate limits.</p>
        </div>
      </header>

      {routes.isPending && !routes.data ? <LoadingState title="Loading routes" detail="Preparing the current route table." /> : null}
      <Panel title="Create route">
        <form className="form-grid" onSubmit={submit}>
          <Field label="Route ID"><Input value={form.routeId} onChange={(e) => setForm({ ...form, routeId: e.target.value })} required /></Field>
          <Field label="Path pattern"><Input value={form.pathPattern} onChange={(e) => setForm({ ...form, pathPattern: e.target.value })} required /></Field>
          <Field label="Target URL"><Input value={form.targetUrl} onChange={(e) => setForm({ ...form, targetUrl: e.target.value })} required /></Field>
          <Field label="Methods"><Input value={methodInput} onChange={(e) => setMethodInput(e.target.value)} /></Field>
          <Field label="Limit"><Input type="number" min={1} value={form.rateLimitRequests} onChange={(e) => setForm({ ...form, rateLimitRequests: Number(e.target.value) })} /></Field>
          <Field label="Window seconds"><Input type="number" min={1} value={form.rateLimitWindowSeconds} onChange={(e) => setForm({ ...form, rateLimitWindowSeconds: Number(e.target.value) })} /></Field>
          <Field label="Status">
            <Select value={String(form.enabled)} onChange={(e) => setForm({ ...form, enabled: e.target.value === 'true' })}>
              <option value="true">Enabled</option>
              <option value="false">Disabled</option>
            </Select>
          </Field>
          <div className="form-actions"><Button type="submit">Create route</Button></div>
        </form>
        {create.error ? <ErrorState message={create.error.message} /> : null}
      </Panel>

      <Panel title="Route table">
        {routes.error ? <ErrorState message={routes.error.message} /> : null}
        {routes.data?.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Route</th><th>Pattern</th><th>Target</th><th>Methods</th><th>Rate limit</th><th>Status</th><th>Updated</th><th>Actions</th></tr>
              </thead>
              <tbody>
                {routes.data.map((route) => (
                  <tr key={route.routeId}>
                    <td><code>{route.routeId}</code></td>
                    <td><code>{route.pathPattern}</code></td>
                    <td className="truncate">{route.targetUrl}</td>
                    <td>{route.allowedMethods.join(', ')}</td>
                    <td>{route.rateLimitRequests}/{route.rateLimitWindowSeconds}s</td>
                    <td><Badge tone={route.enabled ? 'good' : 'bad'}>{route.enabled ? 'enabled' : 'disabled'}</Badge></td>
                    <td>{dateTime(route.updatedAt)}</td>
                    <td><Button variant="secondary" type="button" onClick={() => { setEditing(route); setEditingMethods(route.allowedMethods.join(',')); }}>Edit</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <EmptyState title="No routes" detail="Create a route to proxy traffic through GateShield." />}
      </Panel>

      <Panel title="Rate limit strategy">
        <p className="muted">Strategies are selected by gateway configuration. Route policies currently support request limit and window seconds.</p>
        <div className="strategy-grid">
          <Badge>redis-sliding-window</Badge>
          <Badge>redis-fixed-window</Badge>
          <Badge>redis-token-bucket</Badge>
          <Badge>in-memory fallback</Badge>
        </div>
      </Panel>

      {editing ? (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="edit-route-title">
          <form className="modal wide-modal" onSubmit={(event) => {
            event.preventDefault();
            update.mutate({
              id: editing.routeId,
              payload: {
                pathPattern: editing.pathPattern,
                targetUrl: editing.targetUrl,
                allowedMethods: splitMethods(editingMethods),
                enabled: editing.enabled,
                rateLimitRequests: editing.rateLimitRequests,
                rateLimitWindowSeconds: editing.rateLimitWindowSeconds,
              },
            });
          }}>
            <h2 id="edit-route-title">Edit route</h2>
            <Field label="Path pattern"><Input value={editing.pathPattern} onChange={(e) => setEditing({ ...editing, pathPattern: e.target.value })} /></Field>
            <Field label="Target URL"><Input value={editing.targetUrl} onChange={(e) => setEditing({ ...editing, targetUrl: e.target.value })} /></Field>
            <Field label="Methods"><Input value={editingMethods} onChange={(e) => setEditingMethods(e.target.value)} /></Field>
            <Field label="Limit"><Input type="number" min={1} value={editing.rateLimitRequests} onChange={(e) => setEditing({ ...editing, rateLimitRequests: Number(e.target.value) })} /></Field>
            <Field label="Window seconds"><Input type="number" min={1} value={editing.rateLimitWindowSeconds} onChange={(e) => setEditing({ ...editing, rateLimitWindowSeconds: Number(e.target.value) })} /></Field>
            <Field label="Status">
              <Select value={String(editing.enabled)} onChange={(e) => setEditing({ ...editing, enabled: e.target.value === 'true' })}>
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </Select>
            </Field>
            <div className="modal-actions">
              <Button variant="secondary" type="button" onClick={() => setEditing(null)}>Cancel</Button>
              <Button type="submit">Save route</Button>
            </div>
          </form>
        </div>
      ) : null}
    </div>
  );
}

function splitMethods(value: string) {
  const parsed = value.split(',').map((item) => item.trim().toUpperCase()).filter(Boolean);
  return parsed.filter((method) => methods.includes(method));
}
