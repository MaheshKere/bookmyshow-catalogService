import axios from 'axios';

const env = import.meta.env || {};
const gateway = (env.VITE_API_BASE_URL || '').replace(/\/$/, '');
const useProxy = env.DEV && env.VITE_USE_DEV_PROXY !== 'false';

// Every path goes to Gateway, either directly or through Vite's same-origin proxy.
export const apiClient = axios.create({
  baseURL: useProxy ? '/api' : `${gateway}/api`,
  timeout: 10000,
  headers: { Accept: 'application/json' },
});

// Called once at bootstrap. Callbacks avoid a circular import between Axios and the store.
export function configureApiAuth(getToken, onUnauthorized) {
  apiClient.interceptors.request.use((config) => {
    const token = getToken();
    if (!config.skipAuth && token && !config.headers.has('Authorization')) {
      config.headers.set('Authorization', `Bearer ${token}`);
    }
    return config;
  });
  apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
      const sentToken = error.config?.headers?.get?.('Authorization');
      // An old request's 401 must not sign out a newly authenticated user.
      if (error.response?.status === 401 && getToken() && sentToken === `Bearer ${getToken()}`) {
        onUnauthorized();
      }
      return Promise.reject(error);
    },
  );
}

export function apiError(error) {
  const status = error.response?.status || 0;
  const problem = error.response?.data;
  const fallback = {
    401: 'Sign in again. Your credentials or session were not accepted.',
    403: 'You do not have permission to perform this action.',
    404: 'The requested record was not found.',
    409: 'The data changed. Refresh and try again.',
    429: 'Too many requests. Wait before trying again.',
  };
  const message = typeof problem?.detail === 'string' ? problem.detail
    : fallback[status] || (status ? 'The server could not complete this request.'
      : 'Cannot reach the API Gateway. Check the backend, connection, and proxy configuration.');
  return { status, message, fields: problem?.errors || {}, retryAfter: error.response?.headers?.['retry-after'] || null };
}
