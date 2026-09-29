import { useSelector } from 'react-redux';
import { Navigate, Outlet, useLocation } from 'react-router';

export default function ProtectedRoute() {
  const { accessToken, expiresAt } = useSelector((state) => state.auth);
  const location = useLocation();
  if (!accessToken || expiresAt <= Date.now()) {
    return <Navigate to="/login" replace state={{ next: location.pathname + location.search }} />;
  }
  // A client route guard is a UX aid. Gateway and services still enforce authorization.
  return <Outlet />;
}
