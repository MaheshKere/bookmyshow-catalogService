import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { apiClient, apiError } from '../../services/apiClient.js';

export const fetchMovies = createAsyncThunk('movies/fetchMovies', async (page, { signal, rejectWithValue }) => {
  try {
    const { data } = await apiClient.get('/v1/movies', { params: { page, size: 12 }, signal });
    return data;
  } catch (error) { return rejectWithValue(apiError(error)); }
});
export const fetchMovie = createAsyncThunk('movies/fetchMovie', async (id, { signal, rejectWithValue }) => {
  try { return (await apiClient.get(`/v1/movies/${id}`, { signal })).data; }
  catch (error) { return rejectWithValue(apiError(error)); }
});

const moviesSlice = createSlice({
  name: 'movies',
  initialState: {
    items: [], page: 0, totalPages: 0, totalElements: 0, status: 'idle', error: null, requestId: null,
    detail: null, detailStatus: 'idle', detailError: null, detailRequestId: null,
  },
  reducers: {},
  extraReducers: (builder) => {
    // createAsyncThunk emits pending, fulfilled and rejected actions around the Axios promise.
    builder.addCase(fetchMovies.pending, (state, action) => {
      state.status = 'loading'; state.error = null; state.requestId = action.meta.requestId;
    });
    builder.addCase(fetchMovies.fulfilled, (state, action) => {
      if (state.requestId !== action.meta.requestId) return;
      state.items = action.payload.content; state.page = action.payload.page;
      state.totalPages = action.payload.totalPages; state.totalElements = action.payload.totalElements;
      state.status = 'succeeded';
    });
    builder.addCase(fetchMovies.rejected, (state, action) => {
      if (state.requestId !== action.meta.requestId) return;
      state.status = action.meta.aborted ? 'idle' : 'failed'; state.error = action.meta.aborted ? null : action.payload;
    });
    builder.addCase(fetchMovie.pending, (state, action) => {
      state.detail = null; state.detailStatus = 'loading'; state.detailError = null; state.detailRequestId = action.meta.requestId;
    });
    builder.addCase(fetchMovie.fulfilled, (state, action) => {
      if (state.detailRequestId !== action.meta.requestId) return;
      state.detail = action.payload; state.detailStatus = 'succeeded';
    });
    builder.addCase(fetchMovie.rejected, (state, action) => {
      if (state.detailRequestId !== action.meta.requestId) return;
      state.detailStatus = action.meta.aborted ? 'idle' : 'failed'; state.detailError = action.meta.aborted ? null : action.payload;
    });
  },
});
export default moviesSlice.reducer;
