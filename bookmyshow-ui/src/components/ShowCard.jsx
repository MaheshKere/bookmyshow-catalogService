import { Button, Card } from 'react-bootstrap';
import { Link } from 'react-router';
import { dateTime } from '../services/format.js';

export default function ShowCard({ show }) {
  return <Card className="h-100 border-0 shadow-sm"><Card.Body>
    <div className="text-uppercase small text-secondary mb-2">{show.city}</div>
    <h3 className="h5">{show.theaterName}</h3>
    <p className="text-secondary small">{show.screenName}</p>
    <p className="fw-semibold mb-1">{dateTime(show.startTime)}</p>
    <p className="small text-secondary">Ends {dateTime(show.endTime)}</p>
    {show.active ? <Button as={Link} to={`/shows/${show.id}/seats`} variant="outline-primary">Choose seats</Button>
      : <span className="text-secondary">This show is inactive.</span>}
  </Card.Body></Card>;
}
