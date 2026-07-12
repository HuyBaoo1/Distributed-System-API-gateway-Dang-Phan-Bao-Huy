import { beforeEach, describe, expect, it } from 'vitest';
import { clearSession, getAdminToken, saveSession } from './session';

describe('admin session storage', () => {
  beforeEach(() => {
    clearSession();
  });

  it('stores admin token in session storage by default', () => {
    saveSession('admin-token', 'http://localhost:8080');

    expect(getAdminToken()).toBe('admin-token');
    expect(window.sessionStorage.getItem('gateshield.adminToken')).toBe('admin-token');
    expect(window.localStorage.getItem('gateshield.adminToken')).toBeNull();
  });
});
