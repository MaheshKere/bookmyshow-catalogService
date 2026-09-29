import { useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Navigate, Route, Routes, useLocation } from 'react-router';
import { Container } from 'react-bootstrap';
import AppNavbar from './components/AppNavbar.jsx';
import ProtectedRoute from './components/ProtectedRoute.jsx';
import { sessionExpired } from './features/auth/authSlice.js';
import MoviesPage from './pages/MoviesPage.jsx';
import MovieDetailsPage from './pages/MovieDetailsPage.jsx';
import LoginPage from './pages/LoginPage.jsx';
import RegisterPage from './pages/RegisterPage.jsx';
import SeatSelectionPage from './pages/SeatSelectionPage.jsx';
import ReservationPage from './pages/ReservationPage.jsx';
import BookingPage from './pages/BookingPage.jsx';
import BookingLookupPage from './pages/BookingLookupPage.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';

export default function App() {
  const dispatch = useDispatch();
  const expiresAt = useSelector((state) => state.auth.expiresAt);
  const { pathname } = useLocation();
  useEffect(() => {
    if (!expiresAt) return;
    const timer = setTimeout(() => dispatch(sessionExpired()), Math.max(0, expiresAt - Date.now()));
    return () => clearTimeout(timer);
  }, [expiresAt, dispatch]);
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);

  return <div className="app-shell"><a className="visually-hidden-focusable skip-link" href="#main">Skip to content</a><AppNavbar />
    <Container as="main" id="main" className="py-4 py-md-5 flex-grow-1">
      <Routes>
        <Route path="/" element={<Navigate to="/movies" replace />} />
        <Route path="/movies" element={<MoviesPage />} />
        <Route path="/movies/:movieId" element={<MovieDetailsPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="/shows/:showId/seats" element={<SeatSelectionPage />} />
          <Route path="/reservations/:reference" element={<ReservationPage />} />
          <Route path="/booking" element={<BookingLookupPage />} />
          <Route path="/booking/:reference" element={<BookingPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Container>
    <footer className="border-top py-4"><Container className="d-flex flex-wrap justify-content-between gap-2 small text-secondary">
      <span>BookMyShow Learn &middot; A React + Redux learning project</span><span>Mock payments. Real backend state.</span>
    </Container></footer>
  </div>;
}
