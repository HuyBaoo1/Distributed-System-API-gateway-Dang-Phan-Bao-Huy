import { Panel } from '../components/ui';

export function DeveloperGuidePage() {
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Developer Guide</h1>
          <p>Quick reference for calling APIs protected by GateShield.</p>
        </div>
      </header>
      <Panel title="Protected request">
        <pre><code>{`curl -i https://api.example.com/api/v1/hello \\
  -H "X-API-Key: gs_live_your_key"`}</code></pre>
      </Panel>
      <Panel title="Common responses">
        <div className="guide-grid">
          <div><strong>200</strong><p>Request authenticated, route matched, backend responded.</p></div>
          <div><strong>401</strong><p>Missing, invalid, or disabled API key.</p></div>
          <div><strong>404</strong><p>No enabled route matched the request path.</p></div>
          <div><strong>429</strong><p>Tenant and route exceeded the configured rate limit.</p></div>
          <div><strong>502/504</strong><p>Backend request failed or timed out.</p></div>
        </div>
      </Panel>
      <Panel title="Rate limit headers">
        <ul className="compact-list">
          <li><code>X-RateLimit-Limit</code> configured limit</li>
          <li><code>X-RateLimit-Remaining</code> remaining requests</li>
          <li><code>X-RateLimit-Reset</code> seconds until reset</li>
          <li><code>Retry-After</code> retry delay after a 429</li>
          <li><code>X-Gateway-Latency-Ms</code> total gateway latency</li>
          <li><code>X-Backend-Latency-Ms</code> backend call latency</li>
          <li><code>X-RateLimit-Latency-Ms</code> limiter decision latency</li>
        </ul>
      </Panel>
    </div>
  );
}
