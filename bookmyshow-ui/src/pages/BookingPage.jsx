import { useParams } from 'react-router';
import { useSelector } from 'react-redux';
import { Alert, Button, Card, Col, Row } from 'react-bootstrap';
import useBookingPolling from '../hooks/useBookingPolling.js';
import StatusBadge from '../components/StatusBadge.jsx';
import { ErrorAlert, Loading } from '../components/Feedback.jsx';
import { dateTime, money } from '../services/format.js';

export default function BookingPage() {
  const { reference } = useParams();
  const flow = useSelector((state) => state.booking);
  const { polling, message, checkAgain } = useBookingPolling(reference);
  const booking = flow.booking.data?.bookingReference === reference ? flow.booking.data : null;
  const payment = flow.payment?.bookingReference === reference ? flow.payment : null;
  return <>
    <p className="eyebrow">YOUR MOVIE NIGHT</p><h1 className="h2">Booking status</h1>
    <p className="text-secondary">Keep your booking reference to return to this page later.</p>
    <ErrorAlert error={flow.booking.error} />
    {polling && !booking && <Loading>Checking your booking...</Loading>}
    {booking?.status === 'CONFIRMED' && <Alert variant="success">You're booked! Your seats have been confirmed.</Alert>}
    {booking?.status === 'CANCELLED' && <Alert variant="warning">This booking was cancelled. Your seats are not confirmed.</Alert>}
    {booking?.status === 'PENDING' && <Alert variant="info">Your booking is pending. Payment success alone does not mean that your seats are confirmed.</Alert>}
    <Row className="g-4"><Col md={7}><Card className="border-0 shadow-sm h-100"><Card.Body className="p-4">
      <h2 className="h5">Booking</h2>
      {booking ? <><StatusBadge status={booking.status} /><dl className="detail-list mt-3">
        <dt>Booking reference</dt><dd className="reference">{booking.bookingReference}</dd>
        <dt>Reservation reference</dt><dd className="reference">{booking.reservationReference}</dd>
        <dt>Show</dt><dd>{booking.showId}</dd><dt>Created</dt><dd>{dateTime(booking.createdAt)}</dd>
        <dt>Last updated</dt><dd>{dateTime(booking.updatedAt)}</dd>
      </dl></> : <p className="text-secondary">No booking response yet.</p>}
    </Card.Body></Card></Col><Col md={5}><Card className="border-0 shadow-sm h-100"><Card.Body className="p-4">
      <h2 className="h5">Payment</h2><ErrorAlert error={booking ? flow.paymentError : null} />
      {payment ? <><StatusBadge status={payment.status} /><dl className="detail-list mt-3">
        <dt>Amount</dt><dd className="h4">{money(payment.amount, payment.currency)}</dd>
        <dt>Payment reference</dt><dd className="reference">{payment.paymentReference}</dd>
        <dt>Last updated</dt><dd>{dateTime(payment.updatedAt)}</dd>
      </dl>{payment.status === 'FAILED' && <Alert variant="danger">Mock payment failed. Booking status is displayed separately.</Alert>}</>
        : <p className="text-secondary">{booking && flow.paymentMissing ? 'Payment is not available yet, or is not visible to this account (404).' : 'Waiting for payment information.'}</p>}
      <p className="small text-secondary mb-0">Payment is processed by the backend. This UI never initiates a separate charge or confirms a booking itself.</p>
    </Card.Body></Card></Col></Row>
    <div className="d-flex flex-wrap align-items-center gap-3 mt-4"><Button variant="outline-primary" disabled={polling} onClick={checkAgain}>{polling ? 'Checking automatically...' : 'Check status again'}</Button>
      <span className="small text-secondary" role="status">{message}</span></div>
    <p className="small text-secondary mt-2">Checks run 3 seconds after the previous response, for at most 60 seconds. Leaving this page stops checks, not your booking.</p>
  </>;
}
