import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SignInPage } from './SignInPage';

const tenantsMock = vi.fn();

vi.mock('../api/client', () => ({
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
  api: {
    verifyAdmin: () => tenantsMock(),
  },
}));

describe('SignInPage', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    tenantsMock.mockReset();
  });

  it('stores the admin token in session storage after a valid sign-in', async () => {
    tenantsMock.mockResolvedValue([]);
    render(<MemoryRouter><SignInPage /></MemoryRouter>);

    await userEvent.type(screen.getByLabelText(/admin token/i), 'admin-token');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(window.sessionStorage.getItem('gateshield.adminToken')).toBe('admin-token');
  });

  it('shows a clear error when the token is rejected', async () => {
    tenantsMock.mockRejectedValue(new Error('Admin token was rejected by GateShield.'));
    render(<MemoryRouter><SignInPage /></MemoryRouter>);

    await userEvent.type(screen.getByLabelText(/admin token/i), 'bad-token');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/rejected/i);
  });
});
