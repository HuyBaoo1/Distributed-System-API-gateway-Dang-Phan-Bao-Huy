import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { getConfiguredBaseUrl, saveSession } from '../api/session';
import { Button, Field, Input } from '../components/ui';

export function SignInPage() {
  const [baseUrl, setBaseUrl] = useState(getConfiguredBaseUrl());
  const [token, setToken] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setLoading(true);
    saveSession(token.trim(), baseUrl.trim());
    try {
      await api.verifyAdmin();
      navigate('/');
    } catch (err) {
      const message = err instanceof ApiError && (err.status === 401 || err.status === 403)
        ? 'Admin token was rejected by GateShield.'
        : err instanceof Error ? err.message : 'Unable to sign in.';
      setError(message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="signin-page">
      <section className="signin-card">
        <div className="brand signin-brand">
          <span className="brand-mark">GS</span>
          <div>
            <strong>GateShield Console</strong>
            <small>Operator access</small>
          </div>
        </div>
        <p>Connect to a self-hosted GateShield gateway and manage tenants, routes, rate limits, and usage.</p>
        <form onSubmit={submit} className="form-stack">
          <Field label="Gateway base URL" hint="Leave blank when served by the same origin.">
            <Input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="http://localhost:8080" />
          </Field>
          <Field label="Admin token">
            <Input
              value={token}
              onChange={(event) => setToken(event.target.value)}
              type="password"
              autoComplete="current-password"
              required
              placeholder="Enter GATESHIELD_ADMIN_TOKEN"
            />
          </Field>
          {error ? <div className="error-state" role="alert">{error}</div> : null}
          <Button type="submit" disabled={!token.trim() || loading}>
            {loading ? 'Checking access...' : 'Sign in'}
          </Button>
        </form>
      </section>
    </main>
  );
}
