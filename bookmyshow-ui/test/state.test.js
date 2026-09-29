import test, { beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { configureStore } from '@reduxjs/toolkit';
import auth, { login, logout, sessionExpired } from '../src/features/auth/authSlice.js';
import movies, { fetchMovies } from '../src/features/movies/moviesSlice.js';
import booking, { createBooking, fetchBookingStatus, reserveSeats } from '../src/features/booking/bookingSlice.js';
import { apiClient, apiError, configureApiAuth } from '../src/services/apiClient.js';

let store;
beforeEach(() => { store = configureStore({ reducer: { auth, movies, booking }, devTools: false }); });
configureApiAuth(() => store.getState().auth.accessToken, () => store.dispatch(sessionExpired()));
const ok = (config, data) => ({ config, status: 200, statusText: 'OK', data, headers: {} });
const fail = (config, status, detail) => Promise.reject({ config, response: { status, data: { detail }, headers: {} } });
function authenticate() {
  store.dispatch(login.pending('login', {}));
  store.dispatch(login.fulfilled({ accessToken: 'test-token', expiresAt: Date.now() + 60000, user: { id: 1, firstName: 'Test' } }, 'login', {}));
}

test('login uses exact token contract then /me with bearer authentication', async () => {
  const calls = [];
  apiClient.defaults.adapter = async (config) => {
    calls.push(config.url);
    if (config.url === '/v1/auth/login') {
      assert.deepEqual(JSON.parse(config.data), { email: 'test@example.invalid', password: 'test-only' });
      return ok(config, { accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 900 });
    }
    assert.equal(config.headers.get('Authorization'), 'Bearer test-token');
    return ok(config, { id: 1, firstName: 'Test', role: 'USER' });
  };
  await store.dispatch(login({ email: 'test@example.invalid', password: 'test-only' })).unwrap();
  assert.deepEqual(calls, ['/v1/auth/login', '/v1/users/me']);
  assert.equal(store.getState().auth.user.firstName, 'Test');
  assert.equal(store.getState().auth.accessToken, 'test-token');
});

test('movies thunk maps actual paginated content and sends the shared bearer', async () => {
  authenticate();
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/v1/movies');
    assert.equal(config.headers.get('Authorization'), 'Bearer test-token');
    return ok(config, { content: [{ id: 1, title: 'A movie' }], page: 0, totalPages: 1, totalElements: 1 });
  };
  await store.dispatch(fetchMovies(0)).unwrap();
  assert.equal(store.getState().movies.status, 'succeeded');
  assert.equal(store.getState().movies.items[0].title, 'A movie');
});

test('a late movie response cannot replace the latest page', () => {
  store.dispatch(fetchMovies.pending('old', 0));
  store.dispatch(fetchMovies.pending('new', 1));
  store.dispatch(fetchMovies.fulfilled({ content: [{ id: 1 }], page: 0 }, 'old', 0));
  assert.deepEqual(store.getState().movies.items, []);
  store.dispatch(fetchMovies.fulfilled({ content: [{ id: 2 }], page: 1, totalPages: 2, totalElements: 2 }, 'new', 1));
  assert.equal(store.getState().movies.items[0].id, 2);
});

test('logout clears private state and ignores late booking and login responses', () => {
  authenticate();
  store.dispatch(createBooking.pending('old-booking', 'reservation'));
  store.dispatch(logout());
  store.dispatch(createBooking.fulfilled({ bookingReference: 'late' }, 'old-booking', 'reservation'));
  store.dispatch(login.fulfilled({ accessToken: 'late' }, 'login', {}));
  assert.equal(store.getState().booking.booking.data, null);
  assert.equal(store.getState().auth.accessToken, null);
});

test('reservation sends seat IDs and preserves a backend conflict error', async () => {
  authenticate();
  apiClient.defaults.adapter = (config) => {
    assert.equal(config.url, '/v1/reservations');
    assert.deepEqual(JSON.parse(config.data), { showId: 100, seatIds: [1, 2] });
    return fail(config, 409, 'Seat 2 is HELD');
  };
  const action = await store.dispatch(reserveSeats({ showId: 100, seatIds: [1, 2] }));
  assert.equal(action.payload.status, 409);
  assert.equal(store.getState().booking.reservation.error.message, 'Seat 2 is HELD');
});

test('payment 404 is unavailable, not a fabricated payment failure or booking cancellation', async () => {
  apiClient.defaults.adapter = async (config) => config.url.startsWith('/v1/payments/')
    ? fail(config, 404, 'Not Found') : ok(config, { bookingReference: 'ref', status: 'PENDING' });
  const result = await store.dispatch(fetchBookingStatus('ref')).unwrap();
  assert.equal(result.booking.status, 'PENDING');
  assert.equal(result.payment, null);
  assert.equal(result.paymentMissing, true);
});

test('booking remains confirmed when payment-status read fails', async () => {
  apiClient.defaults.adapter = async (config) => config.url.startsWith('/v1/payments/')
    ? fail(config, 503, 'Payment service unavailable') : ok(config, { bookingReference: 'ref', status: 'CONFIRMED' });
  await store.dispatch(fetchBookingStatus('ref')).unwrap();
  assert.equal(store.getState().booking.booking.data.status, 'CONFIRMED');
  assert.equal(store.getState().booking.paymentError.status, 503);
});

test('401 clears the matching session and private state', async () => {
  authenticate();
  apiClient.defaults.adapter = (config) => fail(config, 401, 'Expired');
  await store.dispatch(fetchBookingStatus('ref'));
  assert.equal(store.getState().auth.accessToken, null);
  assert.equal(store.getState().booking.booking.data, null);
});

test('ProblemDetail validation and rate-limit metadata remain visible', () => {
  const result = apiError({ response: { status: 429, data: { detail: 'Slow down', errors: { field: 'invalid' } }, headers: { 'retry-after': '5' } } });
  assert.equal(result.message, 'Slow down');
  assert.equal(result.retryAfter, '5');
  assert.equal(result.fields.field, 'invalid');
});
