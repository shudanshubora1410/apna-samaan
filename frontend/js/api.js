/**
 * Apna Samaan — Centralized API Client (`api.js`)
 * All network communication, JWT Bearer headers, transparent 401 refresh-token rotation,
 * structured validation error parsing, and authenticated file downloads go through this module.
 */

const STORAGE_KEY = 'apna_samaan_auth_v1';

function detectApiBaseUrl() {
  const metaOverride = document.querySelector('meta[name="apna-api-base"]')?.content?.trim();
  if (metaOverride) {
    return metaOverride.replace(/\/+$/, '');
  }
  const { protocol, hostname, port } = window.location;
  // When opened via VS Code Live Server / static server on 5500, route calls to Spring Boot on 8080
  if (port === '5500' || protocol === 'file:') {
    const host = hostname || 'localhost';
    return `http://${host}:8080/api/v1`;
  }
  return `${window.location.origin}/api/v1`;
}

export const API_BASE_URL = detectApiBaseUrl();

export class ApiError extends Error {
  constructor(status, message, fieldErrors = {}, raw = null) {
    super(message || 'Request failed');
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors || {};
    this.raw = raw;
  }
}

export function getAuthSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function saveAuthSession(authResponse) {
  if (!authResponse || !authResponse.accessToken) return;
  const payload = {
    accessToken: authResponse.accessToken,
    refreshToken: authResponse.refreshToken,
    user: authResponse.user,
    savedAt: Date.now()
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
}

export function updateSessionUser(partialUser) {
  const current = getAuthSession();
  if (!current || !current.user) return;
  current.user = { ...current.user, ...partialUser };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
}

export function clearAuthSession() {
  localStorage.removeItem(STORAGE_KEY);
}

let activeRefreshPromise = null;

async function tryRotateTokens() {
  const session = getAuthSession();
  if (!session?.refreshToken) {
    clearAuthSession();
    return false;
  }

  if (activeRefreshPromise) {
    return activeRefreshPromise;
  }

  activeRefreshPromise = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify({ refreshToken: session.refreshToken })
      });

      if (!response.ok) {
        clearAuthSession();
        return false;
      }

      const rotated = await response.json();
      saveAuthSession(rotated);
      return true;
    } catch {
      clearAuthSession();
      return false;
    } finally {
      activeRefreshPromise = null;
    }
  })();

  return activeRefreshPromise;
}

async function parseErrorResponse(response) {
  try {
    const body = await response.json();
    const msg = body?.message || body?.error || `Request failed (${response.status})`;
    return new ApiError(response.status, msg, body?.fieldErrors || {}, body);
  } catch {
    return new ApiError(response.status, `Request failed with status ${response.status}`);
  }
}

async function rawRequest(path, options = {}, retryOnUnauthorized = true) {
  const {
    method = 'GET',
    body = undefined,
    isFormData = false,
    auth = true,
    expectBlob = false
  } = options;

  const headers = {
    Accept: expectBlob ? '*/*' : 'application/json'
  };

  if (body !== undefined && !isFormData) {
    headers['Content-Type'] = 'application/json';
  }

  const session = getAuthSession();
  if (auth && session?.accessToken) {
    headers.Authorization = `Bearer ${session.accessToken}`;
  }

  const url = path.startsWith('http') ? path : `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;

  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : isFormData ? body : JSON.stringify(body)
    });
  } catch {
    throw new ApiError(0, 'Unable to reach Apna Samaan server. Ensure the backend is running on port 8080.');
  }

  // Transparent single-flight refresh retry when 15-minute access token expires
  if (
    response.status === 401 &&
    auth &&
    retryOnUnauthorized &&
    !path.includes('/auth/login') &&
    !path.includes('/auth/refresh')
  ) {
    const refreshed = await tryRotateTokens();
    if (refreshed) {
      return rawRequest(path, options, false);
    }
  }

  if (!response.ok) {
    throw await parseErrorResponse(response);
  }

  if (expectBlob) {
    return response.blob();
  }

  if (response.status === 204) {
    return null;
  }

  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  get: (path, auth = true) => rawRequest(path, { method: 'GET', auth }),
  post: (path, body, auth = true) => rawRequest(path, { method: 'POST', body, auth }),
  put: (path, body, auth = true) => rawRequest(path, { method: 'PUT', body, auth }),
  patch: (path, body, auth = true) => rawRequest(path, { method: 'PATCH', body, auth }),
  postForm: (path, formData, auth = true) =>
    rawRequest(path, { method: 'POST', body: formData, isFormData: true, auth }),
  putForm: (path, formData, auth = true) =>
    rawRequest(path, { method: 'PUT', body: formData, isFormData: true, auth }),
  fetchDocumentBlob: (downloadPath) => {
    // downloadPath from backend starts with `/api/v1/driver/documents/...`
    const normalized = downloadPath.replace(/^\/api\/v1/, '');
    return rawRequest(normalized, { method: 'GET', auth: true, expectBlob: true });
  },
  async logout() {
    const session = getAuthSession();
    try {
      if (session?.refreshToken) {
        await rawRequest('/auth/logout', {
          method: 'POST',
          body: { refreshToken: session.refreshToken },
          auth: false
        }, false);
      }
    } catch {
      // Clear local session regardless of network outcome
    } finally {
      clearAuthSession();
    }
  }
};
