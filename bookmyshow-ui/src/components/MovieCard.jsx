import { Badge, Card } from 'react-bootstrap';
import { Link } from 'react-router';

// Props are read-only input. This component needs no Redux knowledge and can be reused anywhere.
export default function MovieCard({ movie }) {
  return <Card className="movie-card h-100 border-0 shadow-sm">
    <Link to={`/movies/${movie.id}`} className={`movie-cover cover-${movie.id % 4}`} aria-label={`View ${movie.title}`}>
      <span className="cover-kicker">THE MOVIE COLLECTION</span>
      <span className="cover-title">{movie.title}</span>
      <span className="cover-footer">{movie.language} / {movie.durationMinutes} MIN</span>
    </Link>
    <Card.Body>
      <Badge bg="light" text="dark" className="mb-2">{movie.genre}</Badge>
      <Card.Title as="h2" className="h5"><Link to={`/movies/${movie.id}`} className="text-decoration-none text-dark">{movie.title}</Link></Card.Title>
      <Card.Text className="small text-secondary mb-0">{movie.language} &middot; {movie.durationMinutes} minutes</Card.Text>
      {!movie.active && <Badge bg="secondary" className="mt-2">Inactive</Badge>}
    </Card.Body>
  </Card>;
}
