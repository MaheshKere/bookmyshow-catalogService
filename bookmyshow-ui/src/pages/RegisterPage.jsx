import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Button, Card, Form } from 'react-bootstrap';
import { apiClient, apiError } from '../services/apiClient.js';
import { ErrorAlert } from '../components/Feedback.jsx';

export default function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', password: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  function change(event) { setForm({ ...form, [event.target.name]: event.target.value }); }
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      await apiClient.post('/v1/auth/register', form, { skipAuth: true });
      navigate('/login', { replace: true, state: { email: form.email, message: 'Account created. Sign in to continue.' } });
    } catch (failure) { setError(apiError(failure)); }
    finally { setBusy(false); }
  }
  return <Card className="form-card border-0 shadow-sm"><Card.Body className="p-4 p-md-5">
    <p className="eyebrow">YOUR NEXT MOVIE NIGHT</p><h1 className="h3 mb-4">Create an account</h1>
    <ErrorAlert error={error} /><Form onSubmit={submit}>
      <Form.Group controlId="first-name" className="mb-3"><Form.Label>First name</Form.Label><Form.Control name="firstName" autoComplete="given-name" value={form.firstName} onChange={change} maxLength={100} required /></Form.Group>
      <Form.Group controlId="last-name" className="mb-3"><Form.Label>Last name</Form.Label><Form.Control name="lastName" autoComplete="family-name" value={form.lastName} onChange={change} maxLength={100} required /></Form.Group>
      <Form.Group controlId="register-email" className="mb-3"><Form.Label>Email</Form.Label><Form.Control name="email" type="email" autoComplete="email" value={form.email} onChange={change} maxLength={254} required /></Form.Group>
      <Form.Group controlId="register-password" className="mb-4"><Form.Label>Password</Form.Label><Form.Control name="password" type="password" autoComplete="new-password" value={form.password} onChange={change} minLength={8} maxLength={72} required />
        <Form.Text>At least 8 characters. The server also enforces a 72-byte UTF-8 limit.</Form.Text></Form.Group>
      <Button type="submit" className="w-100" disabled={busy}>{busy ? 'Creating account...' : 'Create account'}</Button>
    </Form><p className="small mt-3 mb-0">Already registered? <Link to="/login">Sign in</Link></p>
  </Card.Body></Card>;
}
