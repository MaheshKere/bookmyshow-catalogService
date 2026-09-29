import { useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { Alert, Button, Card, Form } from 'react-bootstrap';
import { login } from '../features/auth/authSlice.js';
import { ErrorAlert } from '../components/Feedback.jsx';

export default function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const dispatch = useDispatch();
  const auth = useSelector((state) => state.auth);
  const [email, setEmail] = useState(location.state?.email || '');
  const [password, setPassword] = useState('');
  const next = location.state?.next?.startsWith('/') && !location.state.next.startsWith('//') ? location.state.next : '/movies';
  if (auth.accessToken) return <Navigate to={next} replace />;
  async function submit(event) {
    event.preventDefault();
    try {
      await dispatch(login({ email, password })).unwrap();
      setPassword(''); navigate(next, { replace: true });
    } catch { setPassword(''); /* The slice exposes the rejected request's error below. */ }
  }
  return <Card className="form-card border-0 shadow-sm"><Card.Body className="p-4 p-md-5">
    <p className="eyebrow">WELCOME BACK</p><h1 className="h3">Sign in for your next show</h1>
    <p className="text-secondary">Keep your movie night moving.</p>
    {location.state?.message && <Alert variant="success">{location.state.message}</Alert>}
    <ErrorAlert error={auth.error} />
    <Form onSubmit={submit}>
      <Form.Group controlId="login-email" className="mb-3"><Form.Label>Email</Form.Label>
        <Form.Control type="email" autoComplete="username" required maxLength={254} value={email} onChange={(event) => setEmail(event.target.value)} /></Form.Group>
      <Form.Group controlId="login-password" className="mb-4"><Form.Label>Password</Form.Label>
        <Form.Control type="password" autoComplete="current-password" required maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} /></Form.Group>
      <Button type="submit" className="w-100" disabled={auth.status === 'loading'}>{auth.status === 'loading' ? 'Signing in...' : 'Sign in'}</Button>
    </Form>
    <p className="small text-secondary mt-3 mb-0">New here? <Link to="/register">Create an account</Link></p>
    <p className="small text-secondary mt-3 mb-0">For this learning app, refreshing the page signs you out.</p>
  </Card.Body></Card>;
}
