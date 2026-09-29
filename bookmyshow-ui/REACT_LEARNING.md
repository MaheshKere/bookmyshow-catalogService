# React + Redux refresher for a Java/Spring Boot developer

This guide describes this project's code, not a hypothetical architecture. Read it with the source open.
The backend owns durable business state; React owns the current browser presentation and user interactions.
The UI uses ordinary functions, a few hooks, three Redux slices and one Axios client.

## React foundations

### 1. What React is

React is a UI library: describe the UI for the current data, and React updates the browser when that data
changes. It is not an application server, ORM or authentication authority. Our Spring services still do all
business transactions. React does not replace them; it renders their responses and sends user intent.

### 2. SPA concept

`index.html` loads `main.jsx` once. BrowserRouter changes the displayed page without downloading a new HTML
document for each navigation. API responses are JSON. Direct visits to `/booking/...` still need the hosting
server to serve index.html. A SPA does not mean one component, one URL or that everything must be downloaded
or placed in global state.

### 3. Component

A component is a reusable part of the UI with a defined input. MovieCard displays one movie; SeatGrid displays
seats and reports clicks. It resembles a small Java class in the sense of encapsulating a responsibility,
but it is not a mutable singleton service. Component instances have independent React-managed state.

### 4. Functional component

`function MovieCard({ movie }) { return ... }` is the normal modern style. React calls the function to compute
the next render. Calling it is not the same as constructing a Spring bean once. Keep rendering pure: no HTTP
writes, subscriptions or mutations of external state in the function body.

### 5. JSX

JSX lets JavaScript describe elements: `<MovieCard movie={movie} />`. Vite transforms it into JavaScript;
the browser does not execute raw JSX. Braces insert JS expressions, while quoted attributes are literal strings.
`className` supplies a CSS class. `<>...</>` is a Fragment that groups siblings without adding a DOM wrapper.
JSX is not a string template engine. React escapes ordinary text such as movie titles; this app does not use
dangerouslySetInnerHTML for backend content.

### 6. Props

MovieCard receives `movie`; ShowCard receives `show`. Props are read-only inputs supplied by a parent. They
are similar to method parameters, not writable shared fields or Spring dependency injection. A child must
not change `movie.title` to update the application. It requests changes through a callback/action.

### 7. State

State is data that can change over time and affect rendering. A plain local `let` variable resets whenever
the component function runs and does not notify React. Use React state for local UI data; use Redux state
when shared application flows benefit from it. The backend remains authoritative even when a response is in Redux.

### 8. useState

SeatSelectionPage declares `const [selected, setSelected] = useState([])`. The setter schedules a state update.
`setSelected(previous => [...previous, seat])` computes from the latest queued value, avoiding stale snapshots.
Hooks must be called unconditionally at the top level of a component or another hook, not inside loops/branches.

### 9. Re-rendering

Updating state, receiving changed props, or a changed Redux selector result can cause another render. React
calls the component again with a new snapshot. Calling `setSelected` does not immediately rewrite the `selected`
variable captured by the current event handler. React batches updates where appropriate. Re-rendering does
not imply replacing the entire DOM or reloading the page.

### 10. Virtual DOM, at a high level

React builds an in-memory element description, reconciles it with the prior description, then commits needed
host DOM changes. Component type, position and keys help preserve identity/state. This is not a second browser
DOM, a persistence cache or a guarantee that any React code will be fast. For this app, clarity matters more
than premature memoization.

## Effects and interaction

### 11. useEffect

MoviesPage dispatches fetchMovies in an effect depending on dispatch/page/retry. Effects synchronize with
external systems after rendering: requests, timers and subscriptions. The effect returns cleanup that aborts
its request. Dependencies describe values used by the effect; omitting them to force fewer executions can
cause stale closures. Do not make the effect callback itself async: put an async function inside it so the
callback can return cleanup rather than a Promise.

### 12. Lifecycle concepts

Think mount, update and unmount, but hooks model synchronization rather than a one-to-one translation of old
class lifecycle methods. An effect sets up work, cleans up before a dependency-driven replacement, and cleans
up on unmount. StrictMode intentionally runs an extra setup/cleanup cycle in development to reveal mistakes.
GET effects tolerate cancellation; reservation/booking POSTs live in click handlers, not mount effects.

### 13. Event handling

`onClick={reserve}` passes a function; `onClick={reserve()}` would execute during render. LoginPage's submit
handler calls `event.preventDefault()` to avoid a browser form navigation, then dispatches login. Most state
changes caused directly by user intent belong in event handlers, not effects.

### 14. Conditional rendering

ErrorAlert returns null when no error exists. MoviesPage renders loading/error/cards according to request
status. BookingPage distinguishes PENDING, CONFIRMED and CANCELLED using backend values. Ternaries and `&&`
are ordinary JavaScript expressions. Never equate a successful HTTP request with successful booking/payment.

### 15. Lists

`movies.items.map(movie => <Col key={movie.id}>...</Col>)` transforms an array into elements. `filter` removes
unselected seats or no-longer-available choices. Unlike a Java Stream that might be consumed, map/filter return
new arrays; the original array stays unchanged. The UI uses server pagination for movies, shows and seats.

### 16. Why keys matter

Keys identify siblings across renders. Movie/seat IDs are stable business IDs; an array index can attach state
to the wrong item when a list changes. A key is special React metadata, not a normal prop. Pass the ID separately
if a child needs it. Keys only need to be unique among siblings, not across the entire application.

### 17. Forms

LoginPage/RegisterPage use React Bootstrap Form components that render normal HTML form controls. Submit
handlers send the real DTO fields. Browser `required`, type=email and length constraints improve feedback;
they are not replacements for Jakarta Validation or service checks. Server ProblemDetail errors are shown too.

### 18. Controlled components

`<Form.Control value={email} onChange={e => setEmail(e.target.value)} />` makes React state the current input
value. If you set value without onChange the user cannot edit it. RegisterPage uses an object and spreads the
old fields when changing one. Password input remains component-local and is not persisted; login action
metadata still contains it transiently, which is why Redux DevTools recording is disabled.

### 19. Parent-child communication

Data flows down through props. SeatSelectionPage passes seats/selectedIds/onToggle to SeatGrid. The child
calls onToggle(seat); the parent chooses how to update state. A callback communicates intent upward; a child
does not find or mutate its parent's state directly. This differs from a bidirectional object reference graph.

### 20. Lifting state up

SeatGrid and the selection summary both need the selected seats, so their nearest shared parent
SeatSelectionPage owns selected. This is lifting state up. There is no need to put temporary seat selection
in Redux merely because two children display it. Reloading availability prunes unavailable selections;
the POST still lets the database resolve concurrent users.

### 21. React Router

App.jsx declares Routes and Route. Link performs client navigation, useParams reads path parameters,
useNavigate redirects after successful events, and Navigate handles declarative redirects. ProtectedRoute
renders Outlet for nested protected pages or sends the user to login with the intended path in route state.
That state is not a durable booking history. Browser route protection does not authorize the REST endpoint.

## Async JavaScript and the API boundary

### 22. REST with Axios

apiClient.js centralizes the baseURL, timeout, bearer interception and error conversion. It is comparable to
a configured Java HTTP client in responsibility, but runs in an untrusted browser subject to CORS and browser
network rules. Do not include service secrets. The development proxy forwards only to Gateway, not service ports.

### 23. Promise basics

A Promise represents a future result or error. Axios returns one; `.then`/`.catch` can observe it. It is not a
Java Thread. Browser JavaScript does not block the UI waiting for network I/O. Promise.all in SeatSelectionPage
waits for the independent show/seat GETs together and rejects if either fails.

### 24. async/await

An async function returns a Promise. `await apiClient.get(...)` pauses that function until the result arrives;
it does not pause rendering or block the browser's whole event loop. Use try/catch for rejected operations and
finally for cleanup. AbortController stops client observation/transport where supported; it cannot undo a
database transaction already executed by the backend.

### Modern JavaScript to recognize

`const` prevents rebinding but does not freeze an object. `let` is for reassignment, such as a poll timer handle.
Destructuring extracts `{ data }` or `[selected, setSelected]`. Spread builds new arrays/objects; it is shallow,
not a deep clone. Arrow functions capture lexical scope; old closures can hold old state snapshots. Optional
chaining (`error.response?.status`) safely accesses a possibly absent object. ES modules use import/export;
their singleton module evaluation is not Spring bean lifecycle management. Prefer readable transformations
over compressed tricks. The few helper functions in services/format.js are ordinary pure JS functions.

## Redux, one step at a time

### 25. Local versus global state

Local: credentials while editing, city/date inputs, search filters, selected seats, page/retry counters,
countdown ticks and polling messages. Redux: signed-in identity/token, movie responses, reservation/booking/
payment responses shared across steps. Do not duplicate the same source of truth in both places without a
reason. Neither local state nor Redux is the source of truth for seat availability; PostgreSQL is.

### 26. Why Redux exists

Redux gives shared browser state a predictable update path and decouples screens from each other's component
hierarchy. It is useful for the auth/navbar/guard and multi-page booking flow here. It is not mandatory for
every React app and is not a server-side distributed cache or durable database.

### 27. Redux Store

store/store.js creates one store per running browser app. Provider makes that store available to descendant
components. It resembles an application-state container, but it is not shared across users or server instances,
not a Spring application context, and is lost on full reload in this implementation.

### 28. Redux State

The store tree has auth, movies and booking branches. These hold plain serializable values such as strings,
arrays and response objects. Dates from REST remain strings. Do not store Axios Error objects, Promises,
AbortControllers or timer handles in Redux. apiError converts errors to a small plain object.

### 29. Action

An action is usually `{ type, payload }`, a description of something that happened. `logout()` is an action
creator returning an object; it does not update state until dispatched. Async thunk lifecycle actions add
metadata such as requestId. Redux actions are local UI events, not Kafka domain events or transactional outboxes.

### 30. Reducer

A reducer calculates next state from previous state and an action. It must not perform HTTP calls, mutate
outside objects or read browser APIs. createSlice uses Immer: assignments such as `state.status = 'loading'`
edit a draft and produce an immutable next state. This is not permission to mutate Redux state outside a reducer.

### 31. Dispatch

`dispatch(fetchMovies(page))` sends work through the store middleware/reducers. Dispatching a plain action
runs the reducer synchronously; dispatching a thunk starts async logic. It is not an HTTP request by itself.
The thunk is where Axios is invoked. Components can dispatch actions without knowing reducer internals.

### 32. Selector

`state => state.movies` selects the branch a component needs. A selector is a function over in-memory state,
not a SQL query. Select the smallest useful value; avoid constructing a new object on every call unnecessarily.
The navbar selects auth, while seat selection only selects the shared reservation loading flag.

### 33. Redux Toolkit

Redux Toolkit is the standard helper set used here to reduce action/reducer/store boilerplate. It includes
Immer and thunk middleware through configureStore. We deliberately use createAsyncThunk rather than an extra
data fetching abstraction so request/pending/success/error flow remains visible.

### 34. configureStore

It combines the three slice reducers under named state keys and adds useful default middleware. The store
does not make async requests automatically. DevTools is explicitly disabled because login action metadata
and auth state contain sensitive data. Do not enable action logging in this project without redaction.

### 35. createSlice

createSlice groups a name, initialState and reducers. authSlice's reducers generate logout/sessionExpired
actions. extraReducers handles lifecycle actions generated elsewhere, including async thunk completion.
bookingSlice also clears private state on logout/sessionExpired/new login. These are ordinary explicit rules,
not a hidden global cache manager.

### 36. createAsyncThunk

fetchMovies calls Axios and returns the page DTO. Toolkit dispatches pending, fulfilled or rejected around
that Promise. `rejectWithValue(apiError(error))` provides a serializable error payload. A dispatched thunk's
Promise normally resolves to the final action even when the request failed; `.unwrap()` gives the payload or
throws the rejected value, letting a form navigate only on success. `requestId` guards stop stale replies from
overwriting newer requests or restoring private state after logout. The dispatch Promise exposes abort().

### 37. useDispatch

MoviesPage obtains dispatch from Provider's store and uses it in an effect; LoginPage uses it in a submit
handler. useDispatch does not itself subscribe to state or make a component re-render. The state subscription
comes from useSelector.

### 38. useSelector

useSelector subscribes to a selected state value. When it changes by the selected comparison rules, React
renders with that new snapshot. The reducer does not manually call a component or update its HTML.

```text
MoviesPage effect
 -> dispatch(fetchMovies(page))
 -> pending action -> reducer sets loading
 -> Axios GET -> Gateway -> Catalog -> JSON page
 -> fulfilled action -> reducer sets items/status
 -> useSelector observes new state -> MovieCard list renders
```

For a synchronous change: Navbar click -> dispatch(logout()) -> auth/booking reducers clear state ->
useSelector updates navbar/guard -> guarded content redirects. No backend logout endpoint is invented.

## Authentication and the complete backend flow

### 39. Authentication/JWT handling

Login's form inputs are local. The login thunk sends the actual email/password DTO, receives accessToken/
tokenType/expiresIn, then obtains UserResponse from /me. Shared auth state contains that token/profile and
expiry/status/error. The interceptor reads the latest token via a callback wired in main.jsx, avoiding a
circular import of store into Axios into slices into store.

We do not decode JWT claims for authority. Gateway and services verify them. Token lifetime uses expiresIn;
App's timer and a matching 401 expire local state. The token is not in localStorage/sessionStorage/cookies,
so refresh loses auth. There is no silent refresh or server-side logout. This simpler learning trade-off is
not a complete production auth solution; see README security limitations.

### 40. React to Spring Boot through Gateway

```text
SeatSelectionPage (local selected IDs)
 -> dispatch(reserveSeats({ showId, seatIds }))
 -> Axios POST /api/v1/reservations + Bearer
 -> Gateway -> Booking Service -> PostgreSQL locks/checks/hold
 -> ReservationResponse -> fulfilled -> bookingSlice -> ReservationPage

ReservationPage -> createBooking(reservationReference)
 -> Axios -> Gateway -> Booking DB + BookingCreated outbox
 -> BookingResponse (initially PENDING) -> Redux -> BookingPage

Kafka -> Payment -> PaymentResult outbox -> Kafka -> Booking confirmation + outbox
 -> Kafka BookingConfirmed -> Notification mock senders

BookingPage polling -> GET booking + GET payment through Gateway
 -> response -> Redux -> UI shows separate domain statuses
```

The frontend never confirms the booking or sends a Kafka event. The bounded polling hook observes asynchronous
backend work without introducing a new backend transport. A payment 404 is unavailable/not visible, not proof
of failure. A payment SUCCESS is not proof of Booking CONFIRMED. Server errors are displayed, not replaced
by optimistic business state. React does not participate in the server database transaction.

## Where to study each concept in this project

| Concept | Actual file(s) |
|---|---|
| React root, SPA bootstrap, StrictMode | `index.html`, `src/main.jsx` |
| Component, functional component, JSX, props | `src/components/MovieCard.jsx`, `src/components/ShowCard.jsx` |
| State, useState, immutable array updates | `src/pages/SeatSelectionPage.jsx` |
| Re-rendering, Redux subscription | `src/pages/MoviesPage.jsx`, `src/components/AppNavbar.jsx` |
| Virtual DOM and stable element identity | `src/components/SeatGrid.jsx` (stable ID keys) |
| useEffect, lifecycle and request cleanup | `src/pages/MoviesPage.jsx`, `src/pages/MovieDetailsPage.jsx` |
| Timer setup/cleanup and dependency changes | `src/hooks/useCountdown.js`, `src/hooks/useBookingPolling.js` |
| Event handling | `src/pages/LoginPage.jsx`, `src/pages/SeatSelectionPage.jsx` |
| Conditional rendering | `src/components/Feedback.jsx`, `src/pages/BookingPage.jsx` |
| Lists and keys | `src/pages/MoviesPage.jsx`, `src/components/SeatGrid.jsx` |
| Forms and controlled inputs | `src/pages/LoginPage.jsx`, `src/pages/RegisterPage.jsx` |
| Parent-child communication, lifting state | `src/pages/SeatSelectionPage.jsx` -> `src/components/SeatGrid.jsx` |
| Reusable callback props | `src/components/PageControls.jsx` |
| React Router | `src/App.jsx`, `src/components/ProtectedRoute.jsx` |
| Axios, REST, interceptors, error handling | `src/services/apiClient.js` |
| Promises and Promise.all | `src/pages/SeatSelectionPage.jsx` |
| async/await and try/catch/finally | `src/pages/RegisterPage.jsx`, `src/features/auth/authSlice.js` |
| Local versus global state | `src/pages/SeatSelectionPage.jsx` vs `src/features/booking/bookingSlice.js` |
| Redux store/state, configureStore, Provider | `src/store/store.js`, `src/main.jsx` |
| Action/reducer/dispatch/selector | `src/features/auth/authSlice.js`, `src/components/AppNavbar.jsx` |
| Redux Toolkit, createSlice, createAsyncThunk | `src/features/movies/moviesSlice.js` |
| useDispatch and useSelector | `src/pages/MoviesPage.jsx` |
| JWT and expiry | `src/features/auth/authSlice.js`, `src/services/apiClient.js`, `src/App.jsx` |
| Entire Redux/API flow and pending domain states | `src/features/booking/bookingSlice.js`, `src/pages/BookingPage.jsx` |
| Modern JS modules, destructuring, spread, map/filter | The slices, RegisterPage and SeatSelectionPage |
| Contracts at the fixed backend boundary | `API_CONTRACTS.md`, `vite.config.js` |
| Tests of stale responses, auth and HTTP errors | `test/state.test.js` |
| Lightweight component rendering check | `test/render.test.js` |

## Suggested study exercises

1. Trace one movie request through MoviesPage, moviesSlice and apiClient; inspect Network in your browser.
2. Change a local seat selection and explain why it causes no REST call until Reserve is clicked.
3. Add a local display-only toggle and decide why it should not go in Redux.
4. Read a 409 response and trace rejectWithValue -> unwrap -> ErrorAlert without changing backend code.
5. Explain why request IDs and effect cleanup both matter, especially under StrictMode.
6. Compare client loading status with server PENDING and identify who owns each transition.

Useful primary references: [React learn](https://react.dev/learn),
[Redux Toolkit quick start](https://redux-toolkit.js.org/tutorials/quick-start),
[React Router declarative setup](https://reactrouter.com/start/declarative/installation),
[Vite guide](https://vite.dev/guide/), and
[React Bootstrap introduction](https://react-bootstrap.github.io/docs/getting-started/introduction/).
