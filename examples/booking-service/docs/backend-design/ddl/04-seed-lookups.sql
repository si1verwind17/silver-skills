-- Booking service — lookup seed data.  Identifiers are stable and must never be
-- renumbered; code deployed against them will be reading these values.

INSERT INTO booking_state (booking_state_id, code, is_terminal, holds_capacity) VALUES
    (1, 'HELD',      false, true),
    (2, 'CONFIRMED', false, true),
    (3, 'CANCELLED', true,  false),
    (4, 'DECLINED',  true,  false),
    (5, 'EXPIRED',   true,  false),
    (6, 'COMPLETED', true,  false),
    (7, 'NO_SHOW',   true,  false);

INSERT INTO hold_reason (hold_reason_id, code, default_ttl_minutes) VALUES
    (1, 'AWAITING_PROVIDER_APPROVAL', 1440);
-- PD16: a payment gate would be inserted here as (2, 'AWAITING_PAYMENT', 15).
-- No schema change, no state-machine change (LD16).

INSERT INTO actor_type (actor_type_id, code) VALUES
    (1, 'CUSTOMER'), (2, 'PROVIDER'), (3, 'SYSTEM');

INSERT INTO confirmation_mode (confirmation_mode_id, code) VALUES
    (1, 'INSTANT'), (2, 'APPROVAL');

INSERT INTO availability_exception_type (availability_exception_type_id, code) VALUES
    (1, 'BLOCK'), (2, 'OPEN');

INSERT INTO completion_source (completion_source_id, code) VALUES
    (1, 'PROVIDER'), (2, 'SYSTEM');

INSERT INTO event_type (event_type_id, code) VALUES
    (1, 'BookingHeld'),
    (2, 'BookingConfirmed'),
    (3, 'BookingDeclined'),
    (4, 'BookingCancelled'),
    (5, 'BookingExpired'),
    (6, 'BookingRescheduled'),
    (7, 'BookingCompleted'),
    (8, 'BookingNoShow'),
    (9, 'BookingGateResolutionRejected');

-- The transition table of requirements section 4.1, verbatim.  Anything absent
-- here is a forbidden transition and fn_transition_booking will refuse it.
INSERT INTO booking_transition_rule (from_state_id, to_state_id, actor_type_id, requires_gate) VALUES
    -- creation
    (NULL, 1, 1, false), (NULL, 1, 2, false),   -- book into a gated hold
    (NULL, 2, 1, false), (NULL, 2, 2, false),   -- book straight to confirmed
    -- gate resolution (R28): the caller must present the matching hold reason
    (1, 2, 2, true),                            -- provider approves
    (1, 4, 2, true),                            -- provider declines
    -- hold lapses
    (1, 5, 3, false),                           -- system expires (UC11)
    -- withdrawal / cancellation
    (1, 3, 1, false), (1, 3, 2, false),
    (2, 3, 1, false), (2, 3, 2, false),
    -- reschedule keeps the booking in place; see fn_reschedule_booking
    (1, 1, 1, false),
    (2, 2, 2, false),
    (2, 2, 1, false),                           -- customer moves a confirmed booking at an instant-mode provider
    (2, 1, 1, false),                           -- customer moves a confirmed booking at an approval-mode provider (R11)
    -- attendance
    (2, 6, 2, false),                           -- provider marks completed
    (2, 7, 2, false),                           -- provider marks no-show
    (2, 6, 3, false);                           -- system auto-completes (R13)

