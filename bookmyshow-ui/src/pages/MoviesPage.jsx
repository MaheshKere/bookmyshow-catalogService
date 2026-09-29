import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Alert, Button, Col, Row } from 'react-bootstrap';
import { fetchMovies } from '../features/movies/moviesSlice.js';
import MovieCard from '../components/MovieCard.jsx';
import PageControls from '../components/PageControls.jsx';
import { ErrorAlert, Loading } from '../components/Feedback.jsx';

export default function MoviesPage() {
  const dispatch = useDispatch();
  const movies = useSelector((state) => state.movies);
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const request = dispatch(fetchMovies(page));
    return () => request.abort();
  }, [dispatch, page, retry]);

  return <>
    <section className="hero mb-5"><div className="hero-label">MAKE TIME FOR A MOVIE</div>
      <h1>Your next story<br />starts here.</h1><p className="mb-0">Find a movie. Pick your show. Make it a movie night.</p>
      <span className="hero-number" aria-hidden="true">01 / PLAY</span>
    </section>
    <div className="d-flex justify-content-between align-items-baseline mb-3"><h2 className="h4 fw-bold">Explore movies</h2>
      {movies.status === 'succeeded' && <span className="text-secondary small">{movies.totalElements} in the collection</span>}
    </div>
    <ErrorAlert error={movies.error} />
    {movies.status === 'failed' && <Button variant="outline-primary" onClick={() => setRetry((value) => value + 1)}>Try again</Button>}
    {movies.status === 'loading' && <Loading>Finding your next movie...</Loading>}
    {movies.status === 'succeeded' && <>
      {movies.items.length === 0 && <Alert variant="light">No movies are available yet. Ask an administrator to add Catalog data.</Alert>}
      <Row xs={1} sm={2} lg={3} xl={4} className="g-4">{movies.items.map((movie) => <Col key={movie.id}><MovieCard movie={movie} /></Col>)}</Row>
      <PageControls page={page} totalPages={movies.totalPages} onChange={setPage} />
    </>}
  </>;
}
