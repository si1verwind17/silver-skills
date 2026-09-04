-- Booking service — indexes.  Each is listed beside the access pattern it serves;
-- see the index plan in docs/backend-design/data-design.md.

-- UC5/R15: idempotent creation.  Partial, because most bookings carry no key.
CREATE UNIQUE INDEX booking_idempotency_key_uq
    ON booking (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- UC10: "my bookings", newest first.
CREATE INDEX booking_customer_timeline_ix ON booking (customer_id, starts_at DESC);

-- UC10 + R18: a provider's calendar over a date range, always tenant-filtered.
CREATE INDEX booking_provider_timeline_ix ON booking (provider_id, starts_at);

-- Capacity reconciliation, and listing everyone attending one session.
CREATE INDEX booking_session_ix ON booking (session_id);

-- UC11: the expiry sweep.  hold_deadline is non-null exactly while HELD
-- (booking_hold_pair), so the predicate needs no state literal.
CREATE INDEX booking_hold_deadline_ix ON booking (hold_deadline)
    WHERE hold_deadline IS NOT NULL;

-- UC12: the auto-complete sweep, which scans CONFIRMED bookings by end time.
CREATE INDEX booking_state_ends_at_ix ON booking (booking_state_id, ends_at);

-- Availability subtraction and the provider calendar both scan a resource's
-- sessions over a window; the exclusion constraint's GiST index serves overlap
-- tests, this one serves ordered range scans.
CREATE INDEX session_resource_starts_at_ix ON session (resource_id, starts_at);
CREATE INDEX session_service_ix            ON session (service_id);

-- UC2: expanding a resource's rules over a date range.
CREATE INDEX availability_rule_lookup_ix
    ON availability_rule (resource_id, day_of_week, effective_from);

-- UC3: finding exceptions overlapping a window.  The expression is immutable
-- because both bounds are timestamptz.
CREATE INDEX availability_exception_range_gix
    ON availability_exception USING gist (resource_id, tstzrange(starts_at, ends_at, '[)'));

-- UC13: the dispatcher polls undelivered events in backoff order.
CREATE INDEX outbox_event_pending_ix
    ON outbox_event (next_attempt_at, outbox_event_id)
    WHERE dispatched_at IS NULL;

-- UC13 + R29: the dispatcher must send a booking's events in order, so it takes
-- the OLDEST undispatched row per booking rather than the oldest overall.
-- Partial, so it holds only the backlog and stays small however large the table
-- grows.
CREATE INDEX outbox_event_dispatch_ix
    ON outbox_event (booking_id, outbox_event_id)
    WHERE dispatched_at IS NULL;

-- NF11: operator view of one booking's full dispatch history, delivered rows
-- included, which the partial index above deliberately does not cover.
CREATE INDEX outbox_event_booking_ix ON outbox_event (booking_id, outbox_event_id);

-- UC14: consumer messages still in flight.
CREATE INDEX inbox_message_unprocessed_ix ON inbox_message (created_at)
    WHERE processed_at IS NULL;

-- R18: "which providers does this subject administer?", on every provider request.
CREATE INDEX provider_admin_subject_ix ON provider_admin (idp_subject);

-- Listing a provider's resources and services; also the FK maintenance path.
CREATE INDEX resource_provider_ix ON resource (provider_id);
CREATE INDEX service_provider_ix  ON service  (provider_id);

-- service_resource's primary key leads with service_id; this serves the reverse
-- question, "which services can this resource perform?".
CREATE INDEX service_resource_resource_ix ON service_resource (resource_id);

