import { Button } from 'react-bootstrap';
import { Link } from 'react-router';
export default function NotFoundPage() {
  return <section className="py-5 text-center"><p className="eyebrow">404</p><h1>That page isn't in the programme.</h1>
    <Button as={Link} to="/movies" className="mt-3">Explore movies</Button></section>;
}
