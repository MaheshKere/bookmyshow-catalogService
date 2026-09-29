import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link, useParams } from 'react-router';
import { Alert, Badge, Button, Col, Form, Row } from 'react-bootstrap';
import { fetchMovie } from '../features/movies/moviesSlice.js';
import { apiClient, apiError } from '../services/apiClient.js';
import { validId } from '../services/format.js';
import { ErrorAlert, Loading } from '../components/Feedback.jsx';
import ShowCard from '../components/ShowCard.jsx';
import PageControls from '../components/PageControls.jsx';

export default function MovieDetailsPage() {
  const { movieId } = useParams();
  const dispatch = useDispatch();
  const { detail: movie, detailStatus, detailError } = useSelector((state) => state.movies);
  const [city, setCity] = useState('');
  const [date, setDate] = useState('');
  const [filters, setFilters] = useState({});
  const [page, setPage] = useState(0);
  const [shows, setShows] = useState(null);
  const [showError, setShowError] = useState(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!validId(movieId)) return;
    const request = dispatch(fetchMovie(movieId));
    return () => request.abort();
  }, [dispatch, movieId, retry]);
  useEffect(() => {
    if (!validId(movieId)) return;
    const controller = new AbortController();
    setShows(null); setShowError(null);
    // Page-specific show search remains local; shared movie data lives in Redux.
    async function load() {
      try {
        const { data } = await apiClient.get('/v1/shows', { params: { movieId, page, size: 6, ...filters }, signal: controller.signal });
        if (!controller.signal.aborted) setShows(data);
      } catch (error) { if (!controller.signal.aborted) setShowError(apiError(error)); }
    }
    load();
    return () => controller.abort();
  }, [movieId, page, filters, retry]);
  if (!validId(movieId)) return <Alert variant="warning">Invalid movie ID.</Alert>;
  return <>
    <Link to="/movies" className="back-link">&larr; All movies</Link>
    <ErrorAlert error={detailError} />
    {detailStatus === 'loading' && <Loading />}
    {movie && String(movie.id) === movieId && <section className="detail-header p-4 p-md-5 mb-4">
      <Badge bg="light" text="dark" className="mb-3">{movie.genre}</Badge>
      <h1 className="display-5 fw-bold">{movie.title}</h1>
      <p className="text-secondary">{movie.language} &middot; {movie.durationMinutes} min &middot; Released {movie.releaseDate}</p>
      <p className="mb-0 description">{movie.description}</p>
      {!movie.active && <Alert variant="warning" className="mt-3 mb-0">This movie is marked inactive in the Catalog.</Alert>}
    </section>}
    <h2 className="h4">Choose your show</h2>
    <Form className="my-3" onSubmit={(event) => { event.preventDefault(); setPage(0); setFilters({ city: city.trim() || undefined, date: date || undefined }); }}>
      <Row className="g-3 align-items-end"><Col sm={5}><Form.Group controlId="show-city"><Form.Label>City</Form.Label>
        <Form.Control value={city} maxLength={100} placeholder="All cities" onChange={(event) => setCity(event.target.value)} /></Form.Group></Col>
        <Col sm={4}><Form.Group controlId="show-date"><Form.Label>Show date (UTC)</Form.Label><Form.Control type="date" value={date} onChange={(event) => setDate(event.target.value)} /></Form.Group></Col>
        <Col sm={3}><Button type="submit" className="w-100">Find shows</Button></Col></Row>
    </Form>
    <p className="small text-secondary">Show times below use your browser's local timezone.</p>
    <ErrorAlert error={showError} />
    {!shows && !showError && <Loading>Loading shows...</Loading>}
    {(showError || detailError) && <Button variant="outline-primary" className="mb-3" onClick={() => setRetry((value) => value + 1)}>Retry</Button>}
    {shows && <>
      {shows.content.length === 0 && <Alert variant="light">No shows match these filters. Try another date or city.</Alert>}
      <Row xs={1} md={2} lg={3} className="g-3">{shows.content.map((show) => <Col key={show.id}><ShowCard show={show} /></Col>)}</Row>
      <PageControls page={page} totalPages={shows.totalPages} onChange={setPage} />
    </>}
  </>;
}
