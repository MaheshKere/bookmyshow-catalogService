import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link, useNavigate, useParams } from 'react-router';
import { Alert, Button, Card } from 'react-bootstrap';
import { cancelReservation, createBooking, fetchReservation } from '../features/booking/bookingSlice.js';
import { ErrorAlert, Loading } from '../components/Feedback.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import useCountdown from '../hooks/useCountdown.js';
import { dateTime } from '../services/format.js';

export default function ReservationPage() {
  const { reference } = useParams();
  const dispatch = useDispatch();
  const navigate = useNavigate();
  const { reservation, booking } = useSelector((state) => state.booking);
  const current = reservation.data?.reservationReference === reference ? reservation.data : null;
  const seconds = useCountdown(current?.expiresAt);
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState(null);
  const busy = reservation.status === 'loading' || booking.status === 'loading';
  useEffect(() => {
    const request = dispatch(fetchReservation(reference));
    return () => request.abort();
  }, [dispatch, reference, refresh]);
  async function book() {
    setError(null);
    try {
      const result = await dispatch(createBooking(reference)).unwrap();
      navigate(`/booking/${result.bookingReference}`);
    } catch (failure) { setError(failure); }
  }
  async function cancel() {
    setError(null);
    try { await dispatch(cancelReservation(reference)).unwrap(); }
    catch (failure) { setError(failure); }
  }
  return <section className="narrow-content"><p className="eyebrow">ONE STEP CLOSER</p><h1 className="h2">Your reservation</h1>
    <ErrorAlert error={reservation.error} /><ErrorAlert error={error} />
    {reservation.status === 'loading' && <Loading>Checking your hold...</Loading>}
    <Button variant="link" className="px-0 mb-3" disabled={busy} onClick={() => setRefresh((value) => value + 1)}>Refresh reservation</Button>
    {current && <Card className="border-0 shadow-sm"><Card.Body className="p-4">
      <StatusBadge status={current.status} /><dl className="detail-list mt-3">
        <dt>Reservation reference</dt><dd className="reference">{current.reservationReference}</dd>
        <dt>Show</dt><dd>{current.showId}</dd><dt>Seat IDs</dt><dd>{current.seatIds.join(', ')}</dd>
        <dt>Hold expires</dt><dd>{dateTime(current.expiresAt)}</dd>
      </dl>
      {current.status === 'ACTIVE' && <>
        <Alert variant={seconds > 0 ? 'info' : 'warning'}>{seconds > 0 ? `Time remaining: ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
          : 'The hold deadline has passed according to your browser clock. Refresh to check the server status.'}</Alert>
        <p>The next step starts your booking and the existing mock payment process. No card details are required.</p>
        <div className="d-flex flex-wrap gap-2"><Button disabled={busy || seconds === 0} onClick={book}>{booking.status === 'loading' ? 'Creating booking...' : 'Create booking'}</Button>
          <Button variant="outline-secondary" disabled={busy} onClick={cancel}>Cancel reservation</Button></div>
      </>}
      {current.status !== 'ACTIVE' && <Alert variant="light" className="mb-0">This reservation is {current.status.toLowerCase()}. <Link to={`/shows/${current.showId}/seats`}>View seats</Link></Alert>}
    </Card.Body></Card>}
    <p className="small text-secondary mt-3">Save this reference. The current backend has no reservation-history endpoint. A booking request can safely be retried with the same reservation reference.</p>
  </section>;
}
