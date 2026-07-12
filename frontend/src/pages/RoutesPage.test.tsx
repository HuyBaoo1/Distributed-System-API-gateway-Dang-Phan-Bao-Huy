import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoutesPage } from './RoutesPage';

const routesMock = vi.fn();
const createRouteMock = vi.fn();

vi.mock('../api/client', () => ({
  api: {
    routes: () => routesMock(),
    createRoute: (payload: unknown) => createRouteMock(payload),
    updateRoute: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><RoutesPage /></QueryClientProvider>);
}

describe('RoutesPage', () => {
  beforeEach(() => {
    routesMock.mockResolvedValue([]);
    createRouteMock.mockReset();
  });

  it('renders route rate limit configuration fields', () => {
    renderPage();
    expect(screen.getByLabelText(/limit/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/window seconds/i)).toBeInTheDocument();
  });

  it('submits only valid HTTP methods from the methods input', async () => {
    createRouteMock.mockResolvedValue({});
    renderPage();

    await userEvent.type(screen.getByLabelText(/route id/i), 'mock-api');
    await userEvent.clear(screen.getByLabelText(/methods/i));
    await userEvent.type(screen.getByLabelText(/methods/i), 'GET,NOPE,POST');
    await userEvent.click(screen.getByRole('button', { name: /create route/i }));

    expect(createRouteMock).toHaveBeenCalledWith(expect.objectContaining({ allowedMethods: ['GET', 'POST'] }));
  });
});
