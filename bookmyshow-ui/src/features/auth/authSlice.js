import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { apiClient, apiError } from '../../services/apiClient.js';

const initialState = { accessToken: null, expiresAt: null, user: null, status: 'idle', error: null, requestId: null };

export const login = createAsyncThunk('auth/login', async (credentials, { signal, rejectWithValue }) => {
  try {
    const { data: token } = await apiClient.post('/v1/auth/login', credentials, { signal, skipAuth: true });
    const expiresAt = Date.now() + token.expiresIn * 1000;
    // Login returns only token metadata. User details come from the actual /me endpoint.
    const { data: user } = await apiClient.get('/v1/users/me', {
      signal, headers: { Authorization: `${token.tokenType} ${token.accessToken}` },
    });
    return { accessToken: token.accessToken, expiresAt, user };
  } catch (error) { return rejectWithValue(apiError(error)); }
});

const authSlice = createSlice({
  name: 'auth', initialState,
  reducers: {
    logout: () => ({ ...initialState }),
    sessionExpired: () => ({ ...initialState, error: { message: 'Your session expired. Please sign in again.' } }),
  },
  extraReducers: (builder) => {
    builder.addCase(login.pending, (state, action) => {
      state.accessToken = null; state.user = null; state.expiresAt = null;
      state.status = 'loading'; state.error = null; state.requestId = action.meta.requestId;
    });
    builder.addCase(login.fulfilled, (state, action) => {
      if (state.requestId !== action.meta.requestId) return;
      Object.assign(state, action.payload, { status: 'succeeded', requestId: null });
    });
    builder.addCase(login.rejected, (state, action) => {
      if (state.requestId !== action.meta.requestId) return;
      state.status = 'failed'; state.error = action.payload; state.requestId = null;
    });
  },
});

export const { logout, sessionExpired } = authSlice.actions;
export default authSlice.reducer;
