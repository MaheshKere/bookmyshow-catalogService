import { Button, Container, Nav, Navbar } from 'react-bootstrap';
import { Link, NavLink } from 'react-router';
import { useDispatch, useSelector } from 'react-redux';
import { logout } from '../features/auth/authSlice.js';

export default function AppNavbar() {
  const dispatch = useDispatch();
  const { accessToken, user } = useSelector((state) => state.auth);
  return <Navbar expand="md" variant="dark" className="app-navbar" collapseOnSelect>
    <Container>
      <Navbar.Brand as={Link} to="/movies" className="fw-bold">book<span className="brand-accent">my</span>show <small className="brand-label">LEARN</small></Navbar.Brand>
      <Navbar.Toggle aria-controls="main-navigation" />
      <Navbar.Collapse id="main-navigation">
        <Nav className="me-auto ms-md-4"><Nav.Link as={NavLink} to="/movies" eventKey="movies">Movies</Nav.Link>
          <Nav.Link as={NavLink} to="/booking" eventKey="booking">Find booking</Nav.Link></Nav>
        {accessToken ? <div className="d-flex gap-3 align-items-center py-2">
          <span className="text-light small">Hello, {user?.firstName}</span>
          <Button variant="outline-light" size="sm" onClick={() => dispatch(logout())}>Sign out</Button>
        </div> : <Button as={Link} to="/login" size="sm">Sign in</Button>}
      </Navbar.Collapse>
    </Container>
  </Navbar>;
}
