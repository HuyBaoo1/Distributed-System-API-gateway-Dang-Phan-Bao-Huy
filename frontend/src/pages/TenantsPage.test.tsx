import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TenantsPage } from './TenantsPage';

const tenantsMock = vi.fn();
const createTenantMock = vi.fn();

vi.mock('../api/client', () => ({
  api: {
    tenants: () => tenantsMock(),
    createTenant: (payload: unknown) => createTenantMock(payload),
    updateTenant: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><TenantsPage /></QueryClientProvider>);
}

describe('TenantsPage', () => {
  beforeEach(() => {
    tenantsMock.mockResolvedValue([]);
    createTenantMock.mockReset();
  });

  it('requires tenant id and name before creation', async () => {
    renderPage();
    expect(screen.getByRole('button', { name: /create tenant/i })).toBeInTheDocument();
  });

  it('shows a newly generated API key once after tenant creation', async () => {
    createTenantMock.mockResolvedValue({
      id: 'tenant-a',
      name: 'Tenant A',
      planName: 'free',
      enabled: true,
      createdAt: new Date().toISOString(),
      apiKey: 'gs_live_created_once',
    });
    renderPage();

    await userEvent.type(screen.getByLabelText(/tenant id/i), 'tenant-a');
    await userEvent.type(screen.getByLabelText(/^name$/i), 'Tenant A');
    await userEvent.click(screen.getByRole('button', { name: /create tenant/i }));

    expect(await screen.findByText('gs_live_created_once')).toBeInTheDocument();
    expect(screen.getByText(/will not show it again/i)).toBeInTheDocument();
  });
});
