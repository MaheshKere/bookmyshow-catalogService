# Inspected backend contracts

These are repository contracts, not hypothetical endpoints. All paths below start at **Gateway**.
Sources are listed relative to the repository root for inspection only; none was changed.

## Routes and authorization

GatewayRoutes.java maps `/api/v1/auth/**` and `/api/v1/users/**` to Identity; `/api/v1/movies/**` and
`/api/v1/shows/**` to Catalog; `/api/v1/reservations/**` and `/api/v1/bookings/**` to Booking;
`/api/v1/payments/**` to Payment. The more specific `/api/v1/shows/{showId}/seats` route has order -10
and goes to Booking, not Catalog. The frontend makes no service-port calls.

Gateway SecurityConfiguration permits registration/login and Catalog/seat GETs publicly. USER or ADMIN
is required for /users/me, reservations, bookings and payments. Catalog writes/inventory initialization
require ADMIN and are not used by this UI. Invalid bearer tokens are rejected even on public paths.
The UI additionally requires login to open its seat-selection page so reservation is ready to use.

## APIs used

| Method and path | Request | Response/use |
|---|---|---|
| POST `/api/v1/auth/register` | `{ firstName, lastName, email, password }` | UserResponse; 201, then UI asks user to log in |
| POST `/api/v1/auth/login` | `{ email, password }` | TokenResponse |
| GET `/api/v1/users/me` | Bearer token | UserResponse |
| GET `/api/v1/movies?page=0&size=12` | Pagination | MoviePageResponse |
| GET `/api/v1/movies/{id}` | Positive ID | MovieResponse |
| GET `/api/v1/shows?movieId=...&city=...&date=...&page=0&size=6` | Optional city/date, movie filter | ShowPageResponse |
| GET `/api/v1/shows/{id}` | Positive ID | ShowResponse |
| GET `/api/v1/shows/{showId}/seats?page=0&size=100` | No status filter, so all states are visible | ShowSeatPageResponse |
| POST `/api/v1/reservations` | `{ showId, seatIds: [1, 2] }` | ReservationResponse, 201 |
| GET `/api/v1/reservations/{reference}` | Reference up to 36 characters | ReservationResponse |
| POST `/api/v1/reservations/{reference}/cancel` | No body | ReservationResponse |
| POST `/api/v1/bookings` | `{ reservationReference }` | BookingResponse, 200 for creation/replay |
| GET `/api/v1/bookings/{reference}` | Reference up to 36 characters | BookingResponse |
| GET `/api/v1/payments/booking/{reference}` | Bearer token; initiating owner only | PaymentView or 404 |

No request is made to `/bookings/{reference}/confirm`: that controller deliberately returns 409 because
PaymentSucceeded processing owns confirmation. The existing booking-cancel endpoint is not exposed in this
UI; hold cancellation is available before continuing. No charge/refund API is invented.

## Actual DTO fields

* TokenResponse: accessToken, tokenType, expiresIn (seconds). RS256 JWT claims in JwtTokenService are sub,
  role, iss, aud, iat and exp. The frontend treats the token as opaque and derives expiry from expiresIn.
* UserResponse: id, email, firstName, lastName, role, active, createdAt, updatedAt.
* MovieResponse: id, title, description, language, genre, durationMinutes, releaseDate, active, createdAt, updatedAt.
* ShowResponse: id, movieId, movieTitle, screenId, screenName, theaterId, theaterName, city,
  startTime, endTime, active, createdAt, updatedAt.
* ShowSeatResponse: id, showId, seatNumber, status, version, createdAt, updatedAt.
* ReservationResponse: id, reservationReference, showId, status, expiresAt, seatIds, createdAt, updatedAt.
* BookingResponse: id, bookingReference, reservationId, reservationReference, showId, status, createdAt, updatedAt.
* PaymentView: paymentReference, bookingReference, amount, currency, status, createdAt, updatedAt.
* All four page DTOs: content (array), page, size, totalElements, totalPages. Page is zero-based;
  backend page size supports 1..100. UI exposes pagination rather than silently taking only the first seats/shows.

Seat states: AVAILABLE / HELD / BOOKED. Reservation states: ACTIVE / CONFIRMED / EXPIRED / CANCELLED.
Booking states: PENDING / CONFIRMED / CANCELLED. Payment states: PENDING / SUCCESS / FAILED.
Frontend request states such as idle/loading/succeeded/failed are separate from these domain values.

ReservationRequest accepts at most 20 positive, distinct seat IDs. The backend's five-minute hold starts
after row locks are acquired. The frontend submits IDs, not seat labels, prices, status or user IDs.
Booking derives the initiating user from the verified JWT. Payment ownership is checked separately.

Errors use Spring ProblemDetail where supplied: status/detail and optional errors map. apiError preserves
the detail and field errors; it also handles 401/403/409/429 and unavailable Gateway. Retry-After is surfaced
for Gateway rate limits. A payment 404 means unavailable/not visible and must not be interpreted as FAILED.

## Source files inspected

* `api-gateway/src/main/java/com/bookmyshow/gateway/GatewayRoutes.java`
* `api-gateway/src/main/java/com/bookmyshow/gateway/security/SecurityConfiguration.java`
* `api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitFilter.java`
* `identity-service/src/main/java/com/bookmyshow/identity/auth/AuthController.java`, `AuthService.java`, `auth/dto/*.java`
* `identity-service/src/main/java/com/bookmyshow/identity/user/UserController.java`, `user/dto/UserResponse.java`
* `identity-service/src/main/java/com/bookmyshow/identity/security/JwtTokenService.java`
* `catalog-service/src/main/java/com/bookmyshow/catalog/movie/MovieController.java`, `movie/dto/*Response.java`
* `catalog-service/src/main/java/com/bookmyshow/catalog/show/ShowController.java`, `ShowService.java`, `show/dto/*Response.java`
* `booking-service/src/main/java/com/bookmyshow/booking/seat/ShowSeatController.java`, `seat/dto/*Response.java`
* `booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationController.java`, `ReservationService.java`, `reservation/dto/*.java`
* `booking-service/src/main/java/com/bookmyshow/booking/booking/BookingController.java`, `booking/dto/*.java`
* `payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentController.java`, `PaymentService.java`, `MockPaymentProcessor.java`
* Catalog/Booking GlobalExceptionHandler classes.

The backend lacks public DTO fields/endpoints for posters, precise seat layout, pre-payment quotes,
owner-scoped history, notification delivery status and push updates. Normally these need additional backend
contracts; this task deliberately adds none. Admin management and booking cancellation APIs do exist but
are deliberately omitted as UI scope choices.
