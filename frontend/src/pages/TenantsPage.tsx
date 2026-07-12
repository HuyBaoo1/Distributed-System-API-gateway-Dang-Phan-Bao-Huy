import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { api } from '../api/client';
import { Badge, Button, CopyButton, EmptyState, ErrorState, Field, Input, LoadingState, Panel, Select } from '../components/ui';
import { Tenant, TenantPayload } from '../types/api';
import { dateTime } from '../utils/format';

export function TenantsPage() {
  const queryClient = useQueryClient();
  const tenants = useQuery({ queryKey: ['tenants'], queryFn: api.tenants });
  const [createdKey, setCreatedKey] = useState('');
  const [editing, setEditing] = useState<Tenant | null>(null);
  const [form, setForm] = useState({ id: '', name: '', planName: 'free', enabled: true });

  const create = useMutation({
    mutationFn: api.createTenant,
    onSuccess: (tenant) => {
      setCreatedKey(tenant.apiKey || '');
      setForm({ id: '', name: '', planName: 'free', enabled: true });
      queryClient.invalidateQueries({ queryKey: ['tenants'] });
    },
  });

  const update = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: TenantPayload }) => api.updateTenant(id, payload),
    onSuccess: () => {
      setEditing(null);
      queryClient.invalidateQueries({ queryKey: ['tenants'] });
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.id.trim() || !form.name.trim()) return;
    create.mutate(form);
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Tenants</h1>
          <p>Create and manage API key owners. Existing plaintext keys are never displayed.</p>
        </div>
      </header>

      {tenants.isPending && !tenants.data ? <LoadingState title="Loading tenants" detail="Fetching the current tenant directory." /> : null}
      <Panel title="Create tenant">
        <form className="form-grid" onSubmit={submit}>
          <Field label="Tenant ID"><Input value={form.id} onChange={(e) => setForm({ ...form, id: e.target.value })} required /></Field>
          <Field label="Name"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></Field>
          <Field label="Plan"><Input value={form.planName} onChange={(e) => setForm({ ...form, planName: e.target.value })} /></Field>
          <Field label="Status">
            <Select value={String(form.enabled)} onChange={(e) => setForm({ ...form, enabled: e.target.value === 'true' })}>
              <option value="true">Enabled</option>
              <option value="false">Disabled</option>
            </Select>
          </Field>
          <div className="form-actions"><Button type="submit">Create tenant</Button></div>
        </form>
        {create.error ? <ErrorState message={create.error.message} /> : null}
      </Panel>

      {createdKey ? (
        <Panel title="New API key">
          <div className="key-result">
            <code>{createdKey}</code>
            <CopyButton value={createdKey} />
          </div>
          <p className="warning-text">Save this key securely. GateShield will not show it again.</p>
          <Button variant="secondary" type="button" onClick={() => setCreatedKey('')}>I saved this key</Button>
        </Panel>
      ) : null}

      <Panel title="Tenant directory">
        {tenants.error ? <ErrorState message={tenants.error.message} /> : null}
        {tenants.data?.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Name</th><th>Tenant ID</th><th>Plan</th><th>Status</th><th>API key</th><th>Created</th><th>Actions</th></tr>
              </thead>
              <tbody>
                {tenants.data.map((tenant) => (
                  <tr key={tenant.id}>
                    <td>{tenant.name}</td>
                    <td><code>{tenant.id}</code></td>
                    <td>{tenant.planName}</td>
                    <td><Badge tone={tenant.enabled ? 'good' : 'bad'}>{tenant.enabled ? 'enabled' : 'disabled'}</Badge></td>
                    <td><Badge>stored as hash</Badge></td>
                    <td>{dateTime(tenant.createdAt)}</td>
                    <td>
                      <Button variant="secondary" type="button" onClick={() => setEditing(tenant)}>Edit</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <EmptyState title="No tenants" detail="Create a tenant to issue an API key." />}
      </Panel>

      {editing ? (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="edit-tenant-title">
          <form className="modal" onSubmit={(event) => {
            event.preventDefault();
            update.mutate({
              id: editing.id,
              payload: {
                name: editing.name,
                planName: editing.planName,
                enabled: editing.enabled,
              },
            });
          }}>
            <h2 id="edit-tenant-title">Edit tenant</h2>
            <Field label="Name"><Input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} /></Field>
            <Field label="Plan"><Input value={editing.planName} onChange={(e) => setEditing({ ...editing, planName: e.target.value })} /></Field>
            <Field label="Status">
              <Select value={String(editing.enabled)} onChange={(e) => setEditing({ ...editing, enabled: e.target.value === 'true' })}>
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </Select>
            </Field>
            <div className="modal-actions">
              <Button variant="secondary" type="button" onClick={() => setEditing(null)}>Cancel</Button>
              <Button type="submit">Save changes</Button>
            </div>
          </form>
        </div>
      ) : null}
    </div>
  );
}
