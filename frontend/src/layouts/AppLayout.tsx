import { Activity, BookOpen, FileText, Home, LogOut, Menu, Route, Server, Users } from 'lucide-react';
import { ReactNode, useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { clearSession, getApiBaseUrl } from '../api/session';
import { Badge, Button } from '../components/ui';

const navItems = [
  { to: '/', label: 'Overview', icon: Home },
  { to: '/tenants', label: 'Tenants', icon: Users },
  { to: '/routes', label: 'Routes', icon: Route },
  { to: '/usage', label: 'Usage', icon: Activity },
  { to: '/request-logs', label: 'Request Logs', icon: FileText },
  { to: '/system', label: 'System Status', icon: Server },
  { to: '/guide', label: 'Developer Guide', icon: BookOpen },
];

export function AppLayout({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const health = useQuery({ queryKey: ['health'], queryFn: api.health, retry: false, refetchInterval: 30000 });
  const current = navItems.find((item) => item.to === location.pathname);

  function logout() {
    clearSession();
    navigate('/signin');
  }

  return (
    <div className="app-shell">
      <aside className={`sidebar ${open ? 'sidebar-open' : ''}`}>
        <div className="brand">
          <span className="brand-mark">GS</span>
          <div>
            <strong>GateShield</strong>
            <small>Console</small>
          </div>
        </div>
        <nav aria-label="Main navigation">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink key={item.to} to={item.to} onClick={() => setOpen(false)}>
                <Icon size={17} aria-hidden="true" />
                {item.label}
              </NavLink>
            );
          })}
        </nav>
      </aside>

      <div className="main">
        <header className="topbar">
          <button className="icon-button mobile-only" type="button" onClick={() => setOpen(true)} aria-label="Open navigation">
            <Menu size={20} />
          </button>
          <div>
            <span className="breadcrumb">GateShield / {current?.label || 'Console'}</span>
            <strong>{current?.label || 'Console'}</strong>
          </div>
          <div className="topbar-actions">
            <span className="env-label">{getApiBaseUrl() || 'same-origin'}</span>
            <Badge tone={health.data?.status === 'ok' ? 'good' : health.isError ? 'bad' : 'neutral'}>
              {health.data?.status || (health.isError ? 'unreachable' : 'checking')}
            </Badge>
            <Button variant="secondary" type="button" onClick={logout}>
              <LogOut size={15} /> Logout
            </Button>
          </div>
        </header>
        <main>{children}</main>
      </div>
    </div>
  );
}
