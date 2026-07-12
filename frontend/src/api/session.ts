const TOKEN_KEY = 'gateshield.adminToken';
const BASE_URL_KEY = 'gateshield.apiBaseUrl';

const shouldPersist = import.meta.env.VITE_PERSIST_ADMIN_SESSION === 'true';

const storage = () => (shouldPersist ? window.localStorage : window.sessionStorage);

export function getConfiguredBaseUrl() {
  return (import.meta.env.VITE_GATESHIELD_API_BASE_URL || '').replace(/\/$/, '');
}

export function getApiBaseUrl() {
  return (storage().getItem(BASE_URL_KEY) || getConfiguredBaseUrl() || '').replace(/\/$/, '');
}

export function getAdminToken() {
  return storage().getItem(TOKEN_KEY) || '';
}

export function saveSession(token: string, baseUrl: string) {
  storage().setItem(TOKEN_KEY, token);
  storage().setItem(BASE_URL_KEY, baseUrl.replace(/\/$/, ''));
}

export function clearSession() {
  window.sessionStorage.removeItem(TOKEN_KEY);
  window.sessionStorage.removeItem(BASE_URL_KEY);
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(BASE_URL_KEY);
}

export function isSignedIn() {
  return Boolean(getAdminToken());
}
