import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { apiClient, apiError } from '../../services/apiClient.js';
import { logout, sessionExpired, login } from '../auth/authSlice.js';

export const reserveSeats = createAsyncThunk('booking/reserveSeats', async (request, { signal, rejectWithValue }) => {
  try { return (await apiClient.post('/v1/reservations', request, { signal })).data; }
  catch (error) { return rejectWithValue(apiError(error)); }
}, { condition: (_, { getState }) => getState().booking.reservation.status !== 'loading' });

export const fetchReservation = createAsyncThunk('booking/fetchReservation', async (reference, { signal, rejectWithValue }) => {
  try { return (await apiClient.get(`/v1/reservations/${encodeURIComponent(reference)}`, { signal })).data; }
  catch (error) { return rejectWithValue(apiError(error)); }
});

export const cancelReservation = createAsyncThunk('booking/cancelReservation', async (reference, { signal, rejectWithValue }) => {
  try { return (await apiClient.post(`/v1/reservations/${encodeURIComponent(reference)}/cancel`, null, { signal })).data; }
  catch (error) { return rejectWithValue(apiError(error)); }
});

export const createBooking = createAsyncThunk('booking/createBooking', async (reservationReference, { signal, rejectWithValue }) => {
  try { return (await apiClient.post('/v1/bookings', { reservationReference }, { signal })).data; }
  catch (error) { return rejectWithValue(apiError(error)); }
}, { condition: (_, { getState }) => getState().booking.booking.status !== 'loading' });

export const fetchBookingStatus = createAsyncThunk('booking/fetchStatus', async (reference, { signal, rejectWithValue }) => {
  try {
    const { data: booking } = await apiClient.get(`/v1/bookings/${encodeURIComponent(reference)}`, { signal });
    let payment = null;
    let paymentError = null;
    let paymentMissing = false;
    try {
      payment = (await apiClient.get(`/v1/payments/booking/${encodeURIComponent(reference)}`, { signal })).data;
    } catch (error) {
      if (signal.aborted) throw error;
      // The async payment may not exist yet. Payment also uses 404 for another owner's record.
      if (error.response?.status === 404) paymentMissing = true;
      else paymentError = apiError(error);
    }
    return { booking, payment, paymentError, paymentMissing };
  } catch (error) { return rejectWithValue(apiError(error)); }
});

const resource = () => ({ data: null, status: 'idle', error: null, requestId: null });
const initialState = () => ({ reservation: resource(), booking: resource(), payment: null, paymentError: null, paymentMissing: false });

const bookingSlice = createSlice({
  name: 'booking', initialState: initialState(), reducers: {},
  extraReducers: (builder) => {
    // These three operations return the same ReservationResponse, so they share a small reducer pattern.
    for (const thunk of [reserveSeats, fetchReservation, cancelReservation]) {
      builder.addCase(thunk.pending, (state, action) => {
        state.reservation.status = 'loading'; state.reservation.error = null;
        state.reservation.requestId = action.meta.requestId;
      });
      builder.addCase(thunk.fulfilled, (state, action) => {
        if (state.reservation.requestId !== action.meta.requestId) return;
        state.reservation.data = action.payload; state.reservation.status = 'succeeded';
      });
      builder.addCase(thunk.rejected, (state, action) => {
        if (state.reservation.requestId !== action.meta.requestId) return;
        state.reservation.status = action.meta.aborted ? 'idle' : 'failed';
        state.reservation.error = action.meta.aborted ? null : action.payload;
      });
    }
    for (const thunk of [createBooking, fetchBookingStatus]) {
      builder.addCase(thunk.pending, (state, action) => {
        state.booking.status = 'loading'; state.booking.error = null; state.booking.requestId = action.meta.requestId;
      });
      builder.addCase(thunk.rejected, (state, action) => {
        if (state.booking.requestId !== action.meta.requestId) return;
        state.booking.status = action.meta.aborted ? 'idle' : 'failed'; state.booking.error = action.meta.aborted ? null : action.payload;
      });
    }
    builder.addCase(createBooking.fulfilled, (state, action) => {
      if (state.booking.requestId !== action.meta.requestId) return;
      state.booking.data = action.payload; state.booking.status = 'succeeded';
      state.payment = null; state.paymentError = null; state.paymentMissing = false;
    });
    builder.addCase(fetchBookingStatus.fulfilled, (state, action) => {
      if (state.booking.requestId !== action.meta.requestId) return;
      state.booking.data = action.payload.booking; state.booking.status = 'succeeded';
      state.payment = action.payload.payment; state.paymentError = action.payload.paymentError;
      state.paymentMissing = action.payload.paymentMissing;
    });
    // Clear private flow state; late responses are ignored because their request IDs no longer match.
    builder.addCase(logout, initialState);
    builder.addCase(sessionExpired, initialState);
    builder.addCase(login.pending, initialState);
  },
});
export default bookingSlice.reducer;
