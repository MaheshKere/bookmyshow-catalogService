import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button, Card, Form } from 'react-bootstrap';

export default function BookingLookupPage() {
  const [reference, setReference] = useState('');
  const navigate = useNavigate();
  return <Card className="form-card border-0 shadow-sm"><Card.Body className="p-4 p-md-5">
    <p className="eyebrow">PICK UP WHERE YOU LEFT OFF</p><h1 className="h3">Find your booking</h1>
    <p className="text-secondary">Enter a saved reference. The backend does not provide a booking-history list.</p>
    <Form onSubmit={(event) => { event.preventDefault(); if (reference.trim()) navigate(`/booking/${encodeURIComponent(reference.trim())}`); }}>
      <Form.Group controlId="booking-reference" className="mb-3"><Form.Label>Booking reference</Form.Label>
        <Form.Control value={reference} onChange={(event) => setReference(event.target.value)} required maxLength={36} autoComplete="off" /></Form.Group>
      <Button type="submit">View booking</Button>
    </Form>
  </Card.Body></Card>;
}
