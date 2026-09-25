ALTER TABLE bookings ADD COLUMN subject VARCHAR(100);
ALTER TABLE bookings ADD COLUMN amount NUMERIC(12,2);
ALTER TABLE bookings ADD COLUMN currency VARCHAR(3);
ALTER TABLE bookings ADD CONSTRAINT ck_booking_payment CHECK (
    (subject IS NULL AND amount IS NULL AND currency IS NULL) OR
    (subject IS NOT NULL AND amount > 0 AND currency = 'INR'));

CREATE TABLE outbox_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    booking_reference VARCHAR(36) NOT NULL,
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events(id) WHERE published_at IS NULL;
CREATE TABLE consumed_events (
    event_id UUID PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE booking_payment_results (
    booking_reference VARCHAR(36) PRIMARY KEY REFERENCES bookings(booking_reference),
    payment_reference VARCHAR(36) NOT NULL UNIQUE,
    result VARCHAR(30) NOT NULL CHECK (result IN ('PaymentSucceeded', 'PaymentFailed'))
);
