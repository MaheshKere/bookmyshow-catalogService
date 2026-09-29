import { Button } from 'react-bootstrap';

export default function PageControls({ page, totalPages, onChange, disabled = false }) {
  if (totalPages < 2) return null;
  return <nav aria-label="Result pages" className="d-flex align-items-center justify-content-center gap-3 my-4">
    <Button variant="outline-secondary" disabled={disabled || page === 0} onClick={() => onChange(page - 1)}>Previous</Button>
    <span>Page {page + 1} of {totalPages}</span>
    <Button variant="outline-secondary" disabled={disabled || page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</Button>
  </nav>;
}
