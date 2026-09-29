import { configureStore } from '@reduxjs/toolkit';
import auth from '../features/auth/authSlice.js';
import movies from '../features/movies/moviesSlice.js';
import booking from '../features/booking/bookingSlice.js';

export const store = configureStore({
  reducer: { auth, movies, booking },
  // Login action metadata contains credentials; keep tokens/passwords out of Redux DevTools history.
  devTools: false,
});
