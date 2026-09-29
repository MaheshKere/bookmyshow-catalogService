# BookMyShow React + Redux frontend change log

## Scope and backend protection

The repository was inspected before implementation and started with a clean Git working tree.
All delivered work is inside **bookmyshow-ui/**. This file is intentionally frontend-local: the task's hard
requirement forbids modifying any existing repository file, including the root Earlier_answer.md.

**Existing backend source code and configuration were not modified.**

No controllers, DTOs, services, repositories, tests, Maven files, application configuration, security, migrations,
Kafka, Redis or infrastructure implementation changed. No backend data was written during verification.
The existing backend was treated as a fixed external API; no endpoint or response field was invented.

## Frontend architecture

React/Vite JavaScript SPA with React Router, React Bootstrap/Bootstrap 5, Axios and Redux Toolkit.
Browser -> React -> Redux thunk/local async function -> centralized Axios -> Gateway -> existing service.
Vite forwards development /api requests to Gateway to avoid changing backend CORS. Production hosting/CORS
requirements are documented without implementing any backend change. No direct microservice-port calls exist.

* authSlice: opaque bearer token, expiry from expiresIn, /me profile, request/loading/error state.
* moviesSlice: paginated movies and selected movie detail, with visible async thunk lifecycle reducers.
* bookingSlice: reservation, booking and separate payment responses, with logout/expiry cleanup.
* Local state: forms, city/date filters, selected seats, page/retry controls, countdown and polling state.
* Request IDs guard against late responses overwriting newer requests or restoring private state after logout.
* DevTools is disabled to avoid recording login action credentials and bearer state. Tokens are memory-only;
  page refresh signs out. Backend JWT validation remains authoritative. No refresh/logout endpoint is invented.

## Dependency versions

Installed from current stable npm versions/compatible peers and pinned in package.json/package-lock.json:

| Package | Version |
|---|---|
| react / react-dom | 19.3.0 |
| @reduxjs/toolkit | 2.12.0 |
| react-redux | 9.3.0 |
| react-router | 8.4.0 |
| axios | 1.20.0 |
| bootstrap | 5.3.8 |
| react-bootstrap | 2.10.10 |
| vite | 8.3.1 |
| @vitejs/plugin-react | 6.1.1 |

Node 24.21.0 / npm 11.19.0 were used; package engines require Node >=24 and npm >=11.
No TypeScript, Tailwind, Next.js, additional state library or external test framework was added.

## Pages, components and routes

Pages: MoviesPage, MovieDetailsPage, LoginPage, RegisterPage, SeatSelectionPage, ReservationPage,
BookingPage, BookingLookupPage and NotFoundPage.

Reusable components: AppNavbar, MovieCard, ShowCard, SeatGrid, StatusBadge, PageControls, ProtectedRoute,
and Feedback's ErrorAlert/Loading. App defines the route tree/session timer; main wires Provider,
BrowserRouter, CSS and Axios's token callbacks.

Public routes: / -> /movies, /movies, /movies/:movieId, /login, /register.
Guarded routes: /shows/:showId/seats, /reservations/:reference, /booking, /booking/:reference.
Unknown paths render a not-found page.

## Actual Gateway APIs used

* POST /api/v1/auth/register and /api/v1/auth/login; GET /api/v1/users/me -> Identity.
* GET /api/v1/movies and /api/v1/movies/{id} -> Catalog.
* GET /api/v1/shows (movieId/city/date/page/size) and /api/v1/shows/{id} -> Catalog.
* GET /api/v1/shows/{showId}/seats -> Booking via the existing specific route.
* POST /api/v1/reservations, GET /api/v1/reservations/{reference},
  POST /api/v1/reservations/{reference}/cancel -> Booking.
* POST /api/v1/bookings and GET /api/v1/bookings/{reference} -> Booking.
* GET /api/v1/payments/booking/{reference} -> Payment.

API_CONTRACTS.md lists every inspected DTO field, status value, authorization rule, source location and
request body. The frontend never calls the disabled confirmation endpoint, writes Kafka messages, charges
cards or calls Notification directly.

## Authentication and Redux flow

LoginPage local controlled form -> dispatch(login({email,password})) -> Axios/Gateway/Identity
-> {accessToken,tokenType,expiresIn} -> authenticated /me -> fulfilled reducer -> Redux auth
-> useSelector updates navbar/guard. The interceptor in apiClient adds the latest bearer token thereafter.

Example movie flow:

```text
React MoviesPage
 -> dispatch(fetchMovies(page))
 -> Redux pending action/reducer -> loading display
 -> Axios -> Vite proxy -> Gateway -> Spring Boot Catalog -> MoviePageResponse
 -> Redux fulfilled action/reducer -> new movies state
 -> useSelector -> React MovieCard list
```

Booking flow:

```text
Local seat selection -> reserveSeats({showId,seatIds}) -> Gateway -> DB-validated reservation
 -> Redux -> ReservationPage -> createBooking({reservationReference})
 -> Gateway -> Booking PENDING + outbox
 -> Kafka -> Payment -> result outbox -> Kafka -> Booking CONFIRMED + outbox
 -> Kafka BookingConfirmed -> Notification mock senders
 -> UI GET booking/payment through Gateway -> Redux -> BookingPage
```

Selections are not authoritative. The server can reject a reservation with 409; the UI displays the actual
error and refreshes seats. Amount/currency appear only from PaymentView, not a frontend price invention.

## Polling, errors and limitations

Polling checks Booking then Payment, waits 3 seconds after each cycle, and stops at a hard 60-second
deadline. Terminal booking state, request/payment-read errors, sign-out, navigation/reference changes also
stop polling. A manual button starts a new bounded observation session. No overlap or automatic write retries.
404 payment means not yet available/not visible, not FAILED. Payment SUCCESS is distinct from booking confirmation.

Axios uses a 10-second timeout. Aborting a request cannot roll back server work. An uncertain reservation POST
can leave a hold that cannot be recovered by user-history lookup because that API does not exist. The UI warns;
it does not auto-retry. Booking creation can reuse the same reservationReference through backend idempotency.

Unsupported by current DTOs/APIs: posters/trailers/ratings, physical seat geometry/categories, a quote before
payment, user booking/reservation history, notification-delivery status, real charge/refund flows, push updates.
These would normally require new backend contracts; none was added. Admin CRUD/inventory APIs and booking
cancellation do exist, but their UI screens are deliberately omitted. Role-level Booking/Reservation access
and Payment ownership/404 behavior are backend limitations documented rather than changed in the frontend.

Memory-only auth, no refresh tokens, no production hosting deployment, basic HTML validation and display-only
client countdowns are intentional learning choices. React has no Redis dependency; existing backend Redis
features are separate. See README for production CORS/same-origin hosting and security considerations.

## Verification results

* `npm install`: SUCCESS, 97 packages added / 98 audited, 0 reported vulnerabilities.
* `npm ls --depth=0`: all ten requested runtime/dev packages resolved without peer errors.
* `npm run build`: SUCCESS, Vite 8.3.1, 488 transformed modules, 3.54 seconds.
  Output: index.html 0.60 kB; CSS 232.81 kB (31.80 kB gzip); JS 412.42 kB (132.23 kB gzip).
* `npm test`: 10 passed, 0 failed/cancelled/skipped. Tests use Node's built-in runner; no live writes.
* Tests cover token DTO + /me/bearer wiring, page DTO mapping, stale responses, logout cleanup, seat conflict
  payloads, payment 404, confirmed booking with failed payment read, 401 cleanup, ProblemDetail/rate-limit
  metadata and real React/React Bootstrap page/card rendering through Vite's JSX transform.
* Read-only live smoke checks through http://localhost:5173/api: movie page/detail, filtered shows and seats
  succeeded through Gateway. The existing data returned 2 movies, one show for movie 1, and 10 seats for
  show 1 with AVAILABLE and BOOKED states. Frontend /movies HTML returned HTTP 200.
* The browser-control tool reported no available browser. Thus no screenshot/interactive browser journey,
  browser polling-timer test or authenticated end-to-end reservation/payment run is claimed.
* Backend Maven tests were not rerun: no backend source/configuration changed and the task only required
  frontend install/build verification. `git diff --check` passed; tracked diff remained empty.

Git evidence, run from the repository root after implementation:

```text
$ git status --short
?? bookmyshow-ui/

$ git diff --name-only
(no output)

$ git diff --cached --name-only
(no output)
```

`git ls-files --others --exclude-standard` lists only the frontend files below. node_modules/dist are
frontend-local ignored outputs, not new backend files. No existing user changes were reverted.

## Recommended study order

1. src/components/MovieCard.jsx - component, JSX, props.
2. src/pages/MoviesPage.jsx - effects, list/keys, loading and selectors.
3. src/features/movies/moviesSlice.js - async thunk lifecycle/actions/reducers.
4. src/store/store.js, src/main.jsx - configureStore, Provider, Router and bootstrap.
5. src/services/apiClient.js - one Gateway client, async errors and bearer interception.
6. src/App.jsx, src/components/ProtectedRoute.jsx - routes and session UX.
7. LoginPage/authSlice - local form versus shared identity.
8. SeatSelectionPage/SeatGrid - local state, callbacks, lifting state and backend conflicts.
9. ReservationPage/bookingSlice - real mutation DTOs and shared multi-page state.
10. BookingPage/useBookingPolling - observing asynchronous backend work with effect cleanup.
11. test/ - state/API examples and rendering verification.

REACT_LEARNING.md covers all 40 requested concepts and maps each to these actual files.

## Complete file inventory
- [.env.example](.env.example)
- [.gitignore](.gitignore)
- [API_CONTRACTS.md](API_CONTRACTS.md)
- [Earlier_answer.md](Earlier_answer.md)
- [index.html](index.html)
- [package.json](package.json)
- [package-lock.json](package-lock.json)
- [REACT_LEARNING.md](REACT_LEARNING.md)
- [README.md](README.md)
- [src/App.jsx](src/App.jsx)
- [src/components/AppNavbar.jsx](src/components/AppNavbar.jsx)
- [src/components/Feedback.jsx](src/components/Feedback.jsx)
- [src/components/MovieCard.jsx](src/components/MovieCard.jsx)
- [src/components/PageControls.jsx](src/components/PageControls.jsx)
- [src/components/ProtectedRoute.jsx](src/components/ProtectedRoute.jsx)
- [src/components/SeatGrid.jsx](src/components/SeatGrid.jsx)
- [src/components/ShowCard.jsx](src/components/ShowCard.jsx)
- [src/components/StatusBadge.jsx](src/components/StatusBadge.jsx)
- [src/features/auth/authSlice.js](src/features/auth/authSlice.js)
- [src/features/booking/bookingSlice.js](src/features/booking/bookingSlice.js)
- [src/features/movies/moviesSlice.js](src/features/movies/moviesSlice.js)
- [src/hooks/useBookingPolling.js](src/hooks/useBookingPolling.js)
- [src/hooks/useCountdown.js](src/hooks/useCountdown.js)
- [src/main.jsx](src/main.jsx)
- [src/pages/BookingLookupPage.jsx](src/pages/BookingLookupPage.jsx)
- [src/pages/BookingPage.jsx](src/pages/BookingPage.jsx)
- [src/pages/LoginPage.jsx](src/pages/LoginPage.jsx)
- [src/pages/MovieDetailsPage.jsx](src/pages/MovieDetailsPage.jsx)
- [src/pages/MoviesPage.jsx](src/pages/MoviesPage.jsx)
- [src/pages/NotFoundPage.jsx](src/pages/NotFoundPage.jsx)
- [src/pages/RegisterPage.jsx](src/pages/RegisterPage.jsx)
- [src/pages/ReservationPage.jsx](src/pages/ReservationPage.jsx)
- [src/pages/SeatSelectionPage.jsx](src/pages/SeatSelectionPage.jsx)
- [src/services/apiClient.js](src/services/apiClient.js)
- [src/services/format.js](src/services/format.js)
- [src/store/store.js](src/store/store.js)
- [src/styles.css](src/styles.css)
- [test/render.test.js](test/render.test.js)
- [test/state.test.js](test/state.test.js)
- [vite.config.js](vite.config.js)

Total: 40 delivered files, all under bookmyshow-ui/.
