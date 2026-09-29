# BookMyShow UI - React + Redux learning application

A small JavaScript SPA for an experienced Java/Spring Boot developer refreshing React. It uses the existing
backend exactly as implemented. Every API call goes through API Gateway. All frontend work, including this
documentation and the change log, lives inside `bookmyshow-ui/`; no Maven/backend/configuration changes are needed.

```text
Browser -> React components -> Redux thunk or local async function -> Axios
 -> Vite development proxy (development only) -> API Gateway
 -> Identity / Catalog / Booking / Payment -> response -> state -> React UI
```

Start with [REACT_LEARNING.md](REACT_LEARNING.md), then follow its file-by-file study map.
[API_CONTRACTS.md](API_CONTRACTS.md) records the endpoints/DTOs inspected in the repository.
[Earlier_answer.md](Earlier_answer.md) records files, verification and implementation limits.

## Stack and prerequisites

Use **Node.js 24 LTS and npm 11** (verified with Node 24.21.0 / npm 11.19.0). The project declares Node >=24
and npm >=11. Versions were checked against npm's stable tags/peer requirements at implementation time and
pinned, with a committed package-lock.json for reproducibility.

| Dependency | Version | Purpose |
|---|---|---|
| react / react-dom | 19.3.0 | Components and browser rendering |
| @reduxjs/toolkit | 2.12.0 | Store, slices and async thunks |
| react-redux | 9.3.0 | Provider, useDispatch and useSelector |
| react-router | 8.4.0 | Declarative browser routes |
| axios | 1.20.0 | Gateway HTTP client |
| bootstrap | 5.3.8 | CSS and responsive grid |
| react-bootstrap | 2.10.10 | React UI components |
| vite / @vitejs/plugin-react | 8.3.1 / 6.1.1 | Development server and production build |

No TypeScript, Next.js, Tailwind, RTK Query, form library, icon package or extra testing framework is used.
Bootstrap CSS is imported once; Bootstrap's imperative JavaScript bundle is not used with React Bootstrap.

## Installation and commands

From the repository root in PowerShell:

```powershell
cd bookmyshow-ui
npm install
Copy-Item .env.example .env
npm run dev
```

Open **http://localhost:5173**. Vite uses a strict port rather than silently moving to another one.
The environment file is optional for the default local Gateway at localhost:8080.

```powershell
npm run build
npm test
npm run preview
```

`build` produces `dist/`; `preview` serves that build at localhost:4173. `test` uses Node's built-in test runner,
Axios adapters and an existing Vite JSX transform for a small rendering check. No tests write backend data.
Use `npm ci` instead of npm install for later clean installs from the lockfile. node_modules/dist/local env
files are ignored by the frontend's own .gitignore.

## Gateway and environment configuration

```dotenv
VITE_API_BASE_URL=http://localhost:8080
VITE_USE_DEV_PROXY=true
```

VITE_API_BASE_URL is the **Gateway origin**, without `/api`. Never point it at a downstream microservice.
In development, Axios uses relative `/api` and Vite forwards that path to this Gateway origin. Thus the browser
sees same-origin requests on localhost:5173 and does not require backend CORS changes. Vite preserves the full
`/api/v1/...` path and bearer header. The API layer uses `/v1/...` paths appended to its centralized `/api` base.

Set VITE_USE_DEV_PROXY=false only if direct browser-to-Gateway access is already permitted in your environment.
No CORS configuration was found in the fixed Gateway; cross-origin requests may therefore be blocked.
Restart Vite after editing env values. VITE variables are bundled public configuration, not a place for secrets.

For production, Vite's development proxy does not exist. A configured absolute VITE_API_BASE_URL becomes the
browser's API origin and requires an already CORS-enabled deployment. Alternatively build with
`VITE_API_BASE_URL=` so Axios uses same-origin `/api`, and have the frontend hosting layer forward `/api`
to Gateway. SPA hosting must return index.html for frontend deep links while preserving API routes. Neither a
production reverse proxy nor backend CORS changes are implemented. Previewing a build with an absolute Gateway
URL has the same browser CORS restriction; preview is not a production server.

## Backend services

* Browsing: Gateway and Catalog, with Catalog's existing PostgreSQL database.
* Sign-in/registration: Identity and its PostgreSQL database, with existing JWT key configuration.
* Reservation/booking: Booking and its PostgreSQL database, initialized show seats and JWT configuration.
* Automatic payment/confirmation: Kafka, Payment and payment_db, plus existing Booking/Payment outbox workers.
* Notifications: existing Notification Service/database consume BookingConfirmed; the UI does not call it.

Use the backend's existing setup instructions without changing them. Redis is **not** a React dependency.
The current backend uses Redis for its cache/rate-limit/reporting examples; Catalog and movie rate limiting
already have their own fallback behavior. React requires neither a Redis connection nor Redis credentials.

## Pages and flow

| UI route | Page | Access |
|---|---|---|
| `/` | Redirect to movies | Public |
| `/movies` | Paginated movie cards | Public |
| `/movies/:movieId` | Movie details, shows, city/date filters | Public |
| `/login` | Login | Public |
| `/register` | Registration using the actual existing endpoint | Public |
| `/shows/:showId/seats` | Paginated seat list and local selection | Signed in |
| `/reservations/:reference` | Hold status/deadline, create booking, cancel hold | Signed in |
| `/booking` | Lookup by a saved booking reference | Signed in |
| `/booking/:reference` | Booking and payment status | Signed in |
| Other paths | Not-found page | Public |

1. Browse actual Catalog movie data; select a movie and filter its shows.
2. Sign in (or register and then sign in). A guarded route returns you to the requested page after login.
3. Select only seats currently reported AVAILABLE. Up to 20 seat IDs can be selected across pages.
4. Reserve: POST `{ showId, seatIds }`. The backend locks/checks seats and returns the reservation. A 409 is
   shown and availability is reloaded; no optimistic HELD state is invented.
5. Review reservationReference, status, seat IDs and expiresAt. The countdown uses the browser clock and is
   advisory; only the backend decides validity. Create a booking with `{ reservationReference }` before expiry.
6. Booking creation starts the existing asynchronous Kafka/mock-payment flow. The frontend does not submit
   payment details or call the disabled booking-confirm endpoint.
7. View booking status and the separately returned payment status/amount. CONFIRMED is the authoritative UI
   success state; Payment SUCCESS alone does not imply booking confirmation.

The current learning backend uses INR 100 per seat and deterministically fails mock payments of INR 1000 or
more (or expired requests). This is documented backend behavior, not a frontend charge calculation. The UI
does not invent a price field before PaymentView returns the actual amount/currency.

## Authentication

authSlice calls POST `/api/v1/auth/login` with email/password. The response is `{ accessToken, tokenType,
expiresIn }`; a follow-up GET `/api/v1/users/me` supplies the user's actual profile. The token and user are
kept only in Redux memory. No browser JWT claims are invented or decoded to authorize operations.

`main.jsx` connects the store to `configureApiAuth` in `services/apiClient.js`. A request interceptor reads
the current token and adds `Authorization: Bearer <token>` automatically. Login/registration skip it;
the first /me call uses the just-returned token explicitly before the login thunk is fulfilled.

A timer based on expiresIn signs out locally; a matching authenticated 401 also clears auth and booking state.
403 remains a visible permission error. There is no refresh-token or backend logout endpoint. Sign-out only
forgets local state, and **a full page refresh signs you out**. ProtectedRoute is a UX guard, not security;
Gateway and downstream services validate JWTs and roles. Redux DevTools is disabled because login action
metadata contains credentials and state contains a bearer token. Never log those values. In-memory tokens
remain accessible to running JavaScript/XSS; HTTPS, CSP and a reviewed production auth/session design are
outside this learning UI. No secrets or test account credentials are committed.

## Polling and uncertain outcomes

`useBookingPolling` checks Booking then Payment immediately and schedules another cycle **3 seconds after
the previous cycle completes**. There is no overlap. A hard **60-second timer** ends the session and aborts an
outstanding client request. Polling stops on CONFIRMED/CANCELLED, a request error, a payment-read error,
reference change, sign-out or leaving the page. A button explicitly starts a new bounded session.

Payment 404 is displayed as unavailable/not visible, not FAILED: it can mean asynchronous creation has not
happened yet, or the authenticated user does not own the payment. Booking and payment failures are displayed
separately. No WebSockets or new backend mechanism is introduced. Poll timing was reviewed in code;
the lightweight tests do not simulate browser timers.

Axios uses a 10-second request timeout. Network failure/abort of a POST does not undo server work.
Reservation POST has no client idempotency key or user-history API, so an unknown outcome can leave held seats
until backend expiry; the UI warns and does not automatically retry. Creating a booking with the same
reservationReference reuses the backend's existing one-booking-per-reservation behavior and is safe to retry.
Leaving the booking page stops observation, not server-side payment/booking processing.

## Known limits

* No movie posters, ratings, trailers, seat coordinates/categories or price quote are exposed by these DTOs.
  Movie covers are typography using the real title; seats are a flat ID-ordered list, not an invented map.
* No account booking/reservation history endpoint: users must keep references. The lookup page is not a history.
* No notification delivery status, real payment initiation, refund or push endpoint is exposed. None is invented.
* Admin Catalog/inventory management APIs exist but an admin UI is intentionally out of scope, not impossible.
* Reservation/Booking authorization in this learning backend is role-level, not owner-only. Payment has its own
  ownership check and hides another user's data with 404. The frontend cannot repair backend authorization.
* Availability is a snapshot; backend conflicts decide the result. No background seat streaming is provided.
* City/date filtering uses the actual Show API; date filtering is UTC, while displayed timestamps use browser locale.
* Browser route/poll interactions and a live authenticated booking journey were not visually exercised because
  the browser-control session had no available browser. Build/render/state tests and real read-only proxy checks
  passed. No live backend accounts, reservations, bookings or payments were created during verification.

## Structure and study order

```text
bookmyshow-ui/
  src/
    components/   # Reusable display pieces, navigation and route guard
    pages/        # Router screens, forms, local effects/state
    features/     # auth, movies, booking Redux slices and thunks
    store/        # configureStore
    services/     # One Axios client, errors and small formatting helpers
    hooks/        # Countdown and bounded polling
    App.jsx       # Route tree and session timer
    main.jsx      # React root, Provider, BrowserRouter, Axios auth wiring
    styles.css    # Small Bootstrap overrides and typographic covers
  test/           # Node state/API and rendering checks
  .env.example
  .gitignore
  index.html
  package.json
  package-lock.json
  vite.config.js
  README.md
  API_CONTRACTS.md
  REACT_LEARNING.md
  Earlier_answer.md
```

Recommended order: MovieCard -> MoviesPage -> moviesSlice -> store -> apiClient -> main/App -> Login/authSlice
-> SeatSelectionPage/SeatGrid -> ReservationPage/bookingSlice -> BookingPage/useBookingPolling -> tests.

Verification: `npm install` succeeded (0 reported vulnerabilities); `npm run build` succeeded;
`npm test` passed 10 tests. Real Gateway reads through Vite returned movies, movie details, shows and seats.
The repository started clean; final Git evidence is recorded in Earlier_answer.md. Backend Maven tests were
not rerun because no backend source/configuration was modified.
