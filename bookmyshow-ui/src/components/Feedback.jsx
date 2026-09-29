import { Alert, Spinner } from 'react-bootstrap';

export function Loading({ children = 'Loading...' }) {
  return <div className="py-4 text-secondary" role="status"><Spinner size="sm" className="me-2" />{children}</div>;
}
export function ErrorAlert({ error }) {
  if (!error) return null;
  return <Alert variant="danger" role="alert">
    <div>{error.message || 'The request could not be completed.'}</div>
    {error.retryAfter && <div>Please wait {error.retryAfter} seconds before retrying.</div>}
    {Object.keys(error.fields || {}).length > 0 && <ul className="mb-0 mt-2">
      {Object.entries(error.fields).map(([field, message]) => <li key={field}>{field}: {String(message)}</li>)}
    </ul>}
  </Alert>;
}
