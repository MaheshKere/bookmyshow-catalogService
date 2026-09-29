import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link, useNavigate, useParams } from 'react-router';
import { Alert, Button, Card, Col, Row } from 'react-bootstrap';
import { apiClient, apiError } from '../services/apiClient.js';
import { dateTime, validId } from '../services/format.js';
import { reserveSeats } from '../features/booking/bookingSlice.js';
import { ErrorAlert, Loading } from '../components/Feedback.jsx';
import SeatGrid from '../components/SeatGrid.jsx';
import PageControls from '../components/PageControls.jsx';

export default function SeatSelectionPage() {
  const { showId } = useParams();
  const dispatch = useDispatch();
  const navigate = useNavigate();
  const busy = useSelector((state) => state.booking.reservation.status === 'loading');
  const [show, setShow] = useState(null);
  const [seats, setSeats] = useState(null);
  const [selected, setSelected] = useState([]);
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState(null);
  const [loadError, setLoadError] = useState(null);
  useEffect(() => { setSelected([]); setPage(0); }, [showId]);
  useEffect(() => {
    if (!validId(showId)) return;
    const controller = new AbortController();
    setSeats(null); setLoadError(null);
    async function load() {
      try {
        const [showResponse, seatResponse] = await Promise.all([
          apiClient.get(`/v1/shows/${showId}`, { signal: controller.signal }),
          apiClient.get(`/v1/shows/${showId}/seats`, { params: { page, size: 100 }, signal: controller.signal }),
        ]);
        if (controller.signal.aborted) return;
        setShow(showResponse.data); setSeats(seatResponse.data);
        setSelected((previous) => previous.filter((seat) => {
          const fresh = seatResponse.data.content.find((item) => item.id === seat.id);
          return !fresh || fresh.status === 'AVAILABLE';
        }));
      } catch (failure) { if (!controller.signal.aborted) setLoadError(apiError(failure)); }
    }
    load();
    return () => controller.abort();
  }, [showId, page, refresh]);

  function toggle(seat) {
    setError(null);
    setSelected((previous) => previous.some((item) => item.id === seat.id)
      ? previous.filter((item) => item.id !== seat.id)
      : previous.length < 20 ? [...previous, seat] : previous);
  }
  async function reserve() {
    setError(null);
    try {
      const reservation = await dispatch(reserveSeats({ showId: Number(showId), seatIds: selected.map((seat) => seat.id) })).unwrap();
      navigate(`/reservations/${reservation.reservationReference}`);
    } catch (failure) {
      setError(failure); setSelected([]); setRefresh((value) => value + 1);
    }
  }
  if (!validId(showId)) return <Alert variant="warning">Invalid show ID.</Alert>;
  return <>
    {show && <><Link to={`/movies/${show.movieId}`} className="back-link">&larr; Back to shows</Link>
      <p className="eyebrow">CHOOSE YOUR SPOT</p><h1 className="h2">{show.movieTitle}</h1>
      <p className="text-secondary">{show.theaterName} &middot; {show.screenName} &middot; {show.city}<br />{dateTime(show.startTime)}</p></>}
    <ErrorAlert error={loadError} /><ErrorAlert error={error} />
    {error?.status === 0 && <Alert variant="warning">The reservation outcome is uncertain. A request may have reached the server. Refresh seats before another attempt; unseen holds expire on the backend.</Alert>}
    <Row className="g-4"><Col lg={8}><Card className="border-0 shadow-sm"><Card.Body className="p-4">
      <div className="d-flex justify-content-between align-items-center mb-3"><h2 className="h5 mb-0">Seat availability</h2>
        <Button size="sm" variant="outline-secondary" disabled={busy} onClick={() => setRefresh((value) => value + 1)}>Refresh seats</Button></div>
      <p className="small text-secondary">Flat seat list, ordered by ID. A screen layout is not provided by the backend.</p>
      <div className="d-flex gap-3 small mb-4 flex-wrap"><span className="legend available">Available</span><span className="legend selected">Selected</span><span className="legend held">Held</span><span className="legend booked">Booked</span></div>
      {!seats && !loadError && <Loading>Loading seats...</Loading>}
      {seats && <>
        {seats.content.length === 0 ? <Alert variant="light">No seats have been initialized for this show.</Alert>
          : <SeatGrid seats={seats.content} selectedIds={selected.map((seat) => seat.id)} onToggle={toggle} disabled={busy || !show?.active} />}
        <PageControls page={page} totalPages={seats.totalPages} onChange={setPage} disabled={busy} />
      </>}
    </Card.Body></Card></Col><Col lg={4}><Card className="border-0 shadow-sm"><Card.Body className="p-4">
      <h2 className="h5">Your selection</h2><p>{selected.length ? selected.map((seat) => seat.seatNumber).join(', ') : 'Choose an available seat to begin.'}</p>
      <p className="small text-secondary">{selected.length} / 20 seats selected. Selections can span pages. Prices are determined by the server.</p>
      <Button className="w-100" disabled={busy || !seats || loadError || !show?.active || selected.length === 0} onClick={reserve}>
        {busy ? 'Reserving...' : 'Reserve selected seats'}</Button>
      <p className="small text-secondary mt-3 mb-0">Seats are held only after the reservation succeeds. Availability can change until then.</p>
    </Card.Body></Card></Col></Row>
  </>;
}
