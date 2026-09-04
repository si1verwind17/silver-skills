-- Booking service — write paths and availability computation.
--
-- Every multi-statement write lives here so it costs one network round trip and
-- one transaction (data-design.md, "Atomicity and round-trips").  Business
-- POLICY stays in the application: which gate a booking must pass (R33) is
-- decided by the caller and passed in as p_hold_reason_id.  What lives here is
-- what must be true regardless of caller: the invariants of R1, R2, R31, NF4
-- and R22.
--
-- Domain errors use SQLSTATE class 'BK' so the API layer can map a failure to
-- the requirement it violated (NF8):
--   BK001 session overlap, buffers included (R1, R31)
--   BK002 session at capacity (R2)
--   BK003 outside published availability (R3)
--   BK004 inside minimum lead time (R4)
--   BK005 beyond booking horizon (R5)
--   BK006 forbidden state transition (requirements 4.1, R28)
--   BK007 past the cancellation window (R6)
--   BK008 resource/service inactive or not eligible (R19, UC1)
--   BK009 attendance marked before the booking ended (R12)

-- ---------------------------------------------------------------------
-- Availability (UC2, UC3, UC4).  Never materialised: computed from rules,
-- exceptions and occupancy at read time (R26).
-- ---------------------------------------------------------------------

CREATE FUNCTION fn_available_windows(
    p_resource_id bigint,
    p_from        timestamptz,
    p_to          timestamptz
) RETURNS tstzmultirange
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_tz      text;
    v_rules   tstzmultirange;
    v_opens   tstzmultirange;
    v_blocks  tstzmultirange;
BEGIN
    SELECT p.timezone INTO v_tz
      FROM resource r JOIN provider p ON p.provider_id = r.provider_id
     WHERE r.resource_id = p_resource_id AND r.is_active AND p.is_active;
    IF v_tz IS NULL THEN
        RETURN '{}'::tstzmultirange;   -- unknown or inactive resource publishes nothing
    END IF;

    -- Rules are wall-clock in the provider's zone, so they are expanded per
    -- local calendar date and only then converted to instants.  This is what
    -- makes 09:00-17:00 stay 09:00-17:00 across a DST change (R14).
    -- The date span is widened by a day at each end so a window straddling the
    -- requested boundary is not lost.
    SELECT range_agg(tstzrange((d.day + ar.start_time) AT TIME ZONE v_tz,
                               (d.day + ar.end_time)   AT TIME ZONE v_tz, '[)'))
      INTO v_rules
      FROM generate_series((p_from AT TIME ZONE v_tz)::date - 1,
                           (p_to   AT TIME ZONE v_tz)::date + 1,
                           interval '1 day') AS g(day)
      JOIN availability_rule ar
        ON ar.resource_id = p_resource_id
       AND ar.day_of_week = EXTRACT(DOW FROM g.day)
       AND g.day::date >= ar.effective_from
       AND (ar.effective_until IS NULL OR g.day::date <= ar.effective_until)
      CROSS JOIN LATERAL (SELECT g.day::date AS day) d;

    SELECT range_agg(tstzrange(e.starts_at, e.ends_at, '[)'))
      INTO v_opens
      FROM availability_exception e
      WHERE e.resource_id = p_resource_id AND e.availability_exception_type_id = 2;

    SELECT range_agg(tstzrange(e.starts_at, e.ends_at, '[)'))
      INTO v_blocks
      FROM availability_exception e
      WHERE e.resource_id = p_resource_id AND e.availability_exception_type_id = 1;

    -- OPEN adds, BLOCK subtracts and therefore wins over both rules and OPEN (UC3).
    RETURN (COALESCE(v_rules,  '{}'::tstzmultirange)
          + COALESCE(v_opens,  '{}'::tstzmultirange)
          - COALESCE(v_blocks, '{}'::tstzmultirange))
          * tstzmultirange(tstzrange(p_from, p_to, '[)'));
END;
$$;
COMMENT ON FUNCTION fn_available_windows(bigint, timestamptz, timestamptz) IS
'The published availability of one resource over a window, as a multirange: recurring rules expanded in the provider timezone, plus OPEN exceptions, minus BLOCK exceptions (UC2, UC3). Returns empty for an inactive or unknown resource.';

CREATE FUNCTION fn_search_availability(
    p_service_id  bigint,
    p_from        timestamptz,
    p_to          timestamptz,
    p_resource_id bigint DEFAULT NULL
) RETURNS TABLE (resource_id bigint, starts_at timestamptz, ends_at timestamptz, remaining_capacity integer)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_dur interval;
    v_bb  interval;
    v_ba  interval;
    v_cap integer;
    v_step interval;
BEGIN
    SELECT make_interval(mins => s.duration_minutes),
           make_interval(mins => s.buffer_before_minutes),
           make_interval(mins => s.buffer_after_minutes),
           s.capacity,
           make_interval(mins => s.slot_step_minutes)
      INTO v_dur, v_bb, v_ba, v_cap, v_step
      FROM service s WHERE s.service_id = p_service_id AND s.is_active;
    IF v_dur IS NULL THEN
        RETURN;   -- unknown or inactive service offers nothing
    END IF;

    RETURN QUERY
    WITH eligible AS (
        SELECT sr.resource_id
          FROM service_resource sr
          JOIN resource r ON r.resource_id = sr.resource_id
         WHERE sr.service_id = p_service_id
           AND r.is_active
           AND (p_resource_id IS NULL OR sr.resource_id = p_resource_id)
    ),
    windows AS (
        SELECT e.resource_id, w.win
          FROM eligible e
          CROSS JOIN LATERAL unnest(fn_available_windows(e.resource_id, p_from, p_to)) AS w(win)
    ),
    candidates AS (
        -- Only starts whose whole service duration fits the window are offered
        -- (UC4).  The buffer deliberately may fall outside it (R32).
        SELECT w.resource_id, g.ts AS starts_at
          FROM windows w
          CROSS JOIN LATERAL generate_series(lower(w.win), upper(w.win) - v_dur, v_step) AS g(ts)
    )
    SELECT c.resource_id,
           c.starts_at,
           c.starts_at + v_dur,
           COALESCE(s.capacity, v_cap) - COALESCE(s.booked_count, 0)
      FROM candidates c
      LEFT JOIN session s
             ON s.resource_id = c.resource_id
            AND s.service_id  = p_service_id
            AND s.starts_at   = c.starts_at
     WHERE
       -- Either an occupied session of exactly this identity still has room, in
       -- which case it already owns the space and nothing else can conflict...
       (s.session_id IS NOT NULL AND s.booked_count > 0 AND s.booked_count < s.capacity)
       -- ...or the space must be genuinely clear, buffers included.  An emptied
       -- session (booked_count = 0) falls here, because its time may since have
       -- been taken by a neighbour.
       OR NOT EXISTS (
            SELECT 1 FROM session x
             WHERE x.resource_id = c.resource_id
               AND x.booked_count > 0
               AND x.occupied_range && tstzrange(c.starts_at - v_bb, c.starts_at + v_dur + v_ba, '[)')
          )
     ORDER BY c.resource_id, c.starts_at;
END;
$$;
COMMENT ON FUNCTION fn_search_availability(bigint, timestamptz, timestamptz, bigint) IS
'Bookable start times for a service (UC4). Set-based rather than looped, so a 62-day search costs one query per eligible resource. Every returned start satisfies R1, R3 and R31 at the instant of the call; R4 and R5 are applied by the caller, which knows whether the actor is a customer or the provider.';

-- ---------------------------------------------------------------------
-- Event emission (R22).  Called only from inside the write paths below, so an
-- event can never be written without its transition, nor survive a rollback.
-- ---------------------------------------------------------------------

CREATE FUNCTION fn_emit_event(
    p_booking_id  bigint,
    p_event_code  text,
    p_sequence_no integer,
    p_event_id    uuid
) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO outbox_event (event_id, event_type_id, booking_id, transition_sequence_no, payload)
    SELECT p_event_id,
           et.event_type_id,
           b.booking_id,
           p_sequence_no,
           -- Identifiers only.  No name, email or phone ever leaves this
           -- service in an event; consumers resolve contact details
           -- themselves (PD17, NF5).
           jsonb_build_object(
               'bookingRef',  b.public_ref,
               'customerRef', c.public_ref,
               'providerRef', p.public_ref,
               'serviceRef',  sv.public_ref,
               'resourceRef', r.public_ref,
               'state',       bs.code,
               'holdReason',  hr.code,
               'startsAt',    b.starts_at,
               'endsAt',      b.ends_at,
               'sequenceNo',  p_sequence_no,
               -- Present only when the IMMEDIATELY preceding transition sat on a
               -- different session, which is exactly a reschedule.  Comparing
               -- against any earlier transition instead would leak the old time
               -- into every event that followed the move.
               'previousStartsAt', prev.starts_at,
               'previousEndsAt',   prev.ends_at)
      FROM booking b
      JOIN customer c       ON c.customer_id = b.customer_id
      JOIN provider p       ON p.provider_id = b.provider_id
      JOIN session  se      ON se.session_id = b.session_id
      JOIN service  sv      ON sv.service_id = se.service_id
      JOIN resource r       ON r.resource_id = se.resource_id
      JOIN booking_state bs ON bs.booking_state_id = b.booking_state_id
      LEFT JOIN hold_reason hr ON hr.hold_reason_id = b.hold_reason_id
      LEFT JOIN LATERAL (
          SELECT ps.starts_at, ps.ends_at
            FROM booking_transition bt
            JOIN session ps ON ps.session_id = bt.session_id
           WHERE bt.booking_id = b.booking_id
             AND bt.sequence_no = p_sequence_no - 1
             AND bt.session_id <> b.session_id) prev ON true
      CROSS JOIN (SELECT event_type_id FROM event_type WHERE code = p_event_code) et
     WHERE b.booking_id = p_booking_id;
END;
$$;
COMMENT ON FUNCTION fn_emit_event(bigint, text, integer, uuid) IS
'Writes one outbox row for one booking transition (R22, UC13). Payload carries public_ref identifiers only, never PII (PD17).';

-- ---------------------------------------------------------------------
-- The booking write paths.
-- ---------------------------------------------------------------------

CREATE FUNCTION fn_assert_bookable(
    p_service_id    bigint,
    p_resource_id   bigint,
    p_starts_at     timestamptz,
    p_actor_type_id integer ,
    p_now           timestamptz
) RETURNS TABLE (provider_id bigint, ends_at timestamptz, duration_minutes integer,
                 capacity integer, buffer_before_minutes integer, buffer_after_minutes integer,
                 approval_hold_ttl_minutes integer)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_lead    integer;
    v_horizon integer;
BEGIN
    -- Eligibility: active service, active resource, same provider, linked (UC1, R19).
    SELECT sv.provider_id, sv.duration_minutes, sv.capacity,
           sv.buffer_before_minutes, sv.buffer_after_minutes,
           p.approval_hold_ttl_minutes, p.min_lead_minutes, p.booking_horizon_days
      INTO provider_id, duration_minutes, capacity,
           buffer_before_minutes, buffer_after_minutes,
           approval_hold_ttl_minutes, v_lead, v_horizon
      FROM service sv
      JOIN provider p        ON p.provider_id = sv.provider_id
      JOIN service_resource sr ON sr.service_id = sv.service_id AND sr.resource_id = p_resource_id
      JOIN resource r        ON r.resource_id = p_resource_id
     WHERE sv.service_id = p_service_id
       AND sv.is_active AND r.is_active AND p.is_active;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'service % is not bookable on resource %', p_service_id, p_resource_id
            USING ERRCODE = 'BK008';
    END IF;

    ends_at := p_starts_at + make_interval(mins => duration_minutes);

    IF p_starts_at <= p_now THEN
        RAISE EXCEPTION 'cannot book in the past' USING ERRCODE = 'BK004';
    END IF;
    -- R4: minimum lead time binds customers only; a provider booking on a
    -- customer's behalf is present and consenting, so it bypasses.
    IF p_actor_type_id = 1 AND p_starts_at < p_now + make_interval(mins => v_lead) THEN
        RAISE EXCEPTION 'start is inside the % minute minimum lead time', v_lead
            USING ERRCODE = 'BK004';
    END IF;
    -- R5: the horizon binds everyone, because it also bounds availability.
    IF p_starts_at > p_now + make_interval(days => v_horizon) THEN
        RAISE EXCEPTION 'start is beyond the % day booking horizon', v_horizon
            USING ERRCODE = 'BK005';
    END IF;

    -- R3: the booked range must sit inside published availability.  The buffer
    -- deliberately need not (R32), which is why only the service range is tested.
    IF NOT (fn_available_windows(p_resource_id, p_starts_at, ends_at)
            @> tstzrange(p_starts_at, ends_at, '[)')) THEN
        RAISE EXCEPTION 'outside published availability' USING ERRCODE = 'BK003';
    END IF;

    RETURN NEXT;
END;
$$;
COMMENT ON FUNCTION fn_assert_bookable(bigint,bigint,timestamptz,integer,timestamptz) IS
'The checks a proposed booking must pass before capacity is touched: eligibility, R4, R5 and R3. Shared verbatim by fn_create_booking and fn_reschedule_booking, so a reschedule target is validated by exactly the same rules as a new booking (R10). Raises on failure; never returns a verdict.';

CREATE FUNCTION fn_create_booking(
    p_customer_id        bigint,
    p_service_id         bigint,
    p_resource_id        bigint,
    p_starts_at          timestamptz,
    p_hold_reason_id     integer ,
    p_actor_type_id      integer ,
    p_actor_subject      text,
    p_idempotency_key    text,
    p_now                timestamptz,
    p_booking_public_ref uuid,
    p_event_id           uuid
) RETURNS TABLE (booking_id bigint, public_ref uuid, state_code text, was_replay boolean)
LANGUAGE plpgsql AS $$
DECLARE
    v_provider_id bigint;
    v_dur         integer;
    v_cap         integer;
    v_bb          integer;
    v_ba          integer;
    v_hold_ttl    integer;
    v_ends_at     timestamptz;
    v_session_id  bigint;
    v_s_starts    timestamptz;
    v_s_ends      timestamptz;
    v_state_id    smallint;
    v_deadline    timestamptz;
    v_booking_id  bigint;
BEGIN
    -- R15: a replay returns the original booking and writes nothing, so no
    -- second event is emitted either.
    IF p_idempotency_key IS NOT NULL THEN
        SELECT b.booking_id, b.public_ref, bs.code
          INTO booking_id, public_ref, state_code
          FROM booking b JOIN booking_state bs ON bs.booking_state_id = b.booking_state_id
         WHERE b.customer_id = p_customer_id AND b.idempotency_key = p_idempotency_key;
        IF FOUND THEN
            was_replay := true;
            RETURN NEXT;
            RETURN;
        END IF;
    END IF;

    SELECT a.provider_id, a.ends_at, a.duration_minutes, a.capacity,
           a.buffer_before_minutes, a.buffer_after_minutes, a.approval_hold_ttl_minutes
      INTO v_provider_id, v_ends_at, v_dur, v_cap, v_bb, v_ba, v_hold_ttl
      FROM fn_assert_bookable(p_service_id, p_resource_id, p_starts_at, p_actor_type_id, p_now) a;

    -- Take capacity.  Either this booking opens a new session, or it joins the
    -- existing one of identical identity.  session_no_overlap decides whether a
    -- new session may exist at all; the WHERE decides whether an existing one
    -- has room.  Neither is a read-then-write.
    BEGIN
        INSERT INTO session (provider_id, resource_id, service_id, starts_at, ends_at,
                             duration_minutes, capacity, buffer_before_minutes,
                             buffer_after_minutes, booked_count, occupied_range)
        VALUES (v_provider_id, p_resource_id, p_service_id, p_starts_at, v_ends_at,
                v_dur, v_cap, v_bb, v_ba, 1, 'empty')
        ON CONFLICT (resource_id, service_id, starts_at) DO UPDATE
            SET booked_count = session.booked_count + 1,
                updated_at   = p_now
            WHERE session.booked_count < session.capacity
        RETURNING session.session_id, session.starts_at, session.ends_at
             INTO v_session_id, v_s_starts, v_s_ends;
    EXCEPTION WHEN exclusion_violation THEN
        RAISE EXCEPTION 'the resource is occupied at that time, buffers included'
            USING ERRCODE = 'BK001';
    END;
    IF v_session_id IS NULL THEN
        RAISE EXCEPTION 'session is at capacity' USING ERRCODE = 'BK002';
    END IF;

    IF p_hold_reason_id IS NULL THEN
        v_state_id := 2;                       -- CONFIRMED
        v_deadline := NULL;
    ELSE
        v_state_id := 1;                       -- HELD
        -- R8: min(gate TTL from now, start time).  The provider override applies
        -- to the approval gate; any other gate uses its own default.
        SELECT LEAST(p_now + make_interval(mins =>
                   CASE WHEN hr.code = 'AWAITING_PROVIDER_APPROVAL'
                        THEN v_hold_ttl ELSE hr.default_ttl_minutes END),
               v_s_starts)
          INTO v_deadline
          FROM hold_reason hr WHERE hr.hold_reason_id = p_hold_reason_id;
    END IF;

    INSERT INTO booking (public_ref, customer_id, session_id, provider_id, booking_state_id,
                         hold_reason_id, hold_deadline, starts_at, ends_at,
                         created_by_actor_type_id, idempotency_key)
    VALUES (p_booking_public_ref, p_customer_id, v_session_id, v_provider_id, v_state_id,
            p_hold_reason_id, v_deadline, v_s_starts, v_s_ends,
            p_actor_type_id, p_idempotency_key)
    RETURNING booking.booking_id INTO v_booking_id;

    INSERT INTO booking_transition (booking_id, sequence_no, session_id, from_state_id,
                                    to_state_id, actor_type_id, actor_subject)
    VALUES (v_booking_id, 1, v_session_id, NULL, v_state_id, p_actor_type_id, p_actor_subject);

    PERFORM fn_emit_event(v_booking_id,
                          CASE WHEN v_state_id = 1 THEN 'BookingHeld' ELSE 'BookingConfirmed' END,
                          1, p_event_id);

    booking_id  := v_booking_id;
    public_ref  := p_booking_public_ref;
    state_code  := CASE WHEN v_state_id = 1 THEN 'HELD' ELSE 'CONFIRMED' END;
    was_replay  := false;
    RETURN NEXT;
END;
$$;
COMMENT ON FUNCTION fn_create_booking(bigint,bigint,bigint,timestamptz,integer,integer,text,text,timestamptz,uuid,uuid) IS
'UC5 and UC6 in one transaction and one round trip: eligibility, R4, R5, R3, capacity, the booking row, its first transition and its outbox event (R22). Which gate applies is decided by the caller and passed as p_hold_reason_id (R33).';

CREATE FUNCTION fn_transition_booking(
    p_booking_id           bigint,
    p_to_state_code        text,
    p_actor_type_id        integer ,
    p_actor_subject        text,
    p_reason               text,
    p_now                  timestamptz,
    p_event_id             uuid,
    p_gate_hold_reason_id  integer DEFAULT NULL,
    p_completion_source_id integer DEFAULT NULL,
    p_event_code_override  text     DEFAULT NULL
) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    b              booking%ROWTYPE;
    v_from_term    boolean;
    v_from_holds   boolean;
    v_to_id        smallint;
    v_to_holds     boolean;
    v_requires_gate boolean;
    v_window       integer;
    v_seq          integer;
    v_event        text;
BEGIN
    SELECT * INTO b FROM booking WHERE booking_id = p_booking_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'booking % not found', p_booking_id USING ERRCODE = 'BK006';
    END IF;

    SELECT bs.is_terminal, bs.holds_capacity INTO v_from_term, v_from_holds
      FROM booking_state bs WHERE bs.booking_state_id = b.booking_state_id;
    IF v_from_term THEN
        RAISE EXCEPTION 'booking % is terminal and cannot transition', p_booking_id
            USING ERRCODE = 'BK006';
    END IF;

    SELECT bs.booking_state_id, bs.holds_capacity INTO v_to_id, v_to_holds
      FROM booking_state bs WHERE bs.code = p_to_state_code;

    -- The permitted moves are data (requirements 4.1), not branches.
    SELECT tr.requires_gate INTO v_requires_gate
      FROM booking_transition_rule tr
     WHERE COALESCE(tr.from_state_id, -1) = b.booking_state_id
       AND tr.to_state_id = v_to_id
       AND tr.actor_type_id = p_actor_type_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'transition to % is not permitted from this state for this actor', p_to_state_code
            USING ERRCODE = 'BK006';
    END IF;

    -- R28: a gate resolution applies only to a booking that is currently HELD on
    -- the very gate being resolved.  Both directions matter.  Requiring the gate
    -- when the rule expects one stops an unqualified caller resolving a hold;
    -- refusing a gate when the matched rule expects none stops a REPLAYED
    -- resolution landing on an already-confirmed booking, where it would
    -- otherwise match the provider-reschedule rule (CONFIRMED -> CONFIRMED) and
    -- be silently accepted.
    IF v_requires_gate AND (p_gate_hold_reason_id IS NULL OR p_gate_hold_reason_id IS DISTINCT FROM b.hold_reason_id) THEN
        RAISE EXCEPTION 'gate resolution does not match the booking''s current gate'
            USING ERRCODE = 'BK006';
    END IF;
    IF p_gate_hold_reason_id IS NOT NULL AND NOT v_requires_gate THEN
        RAISE EXCEPTION 'booking is not awaiting a gate; it is already %', 
            (SELECT code FROM booking_state WHERE booking_state_id = b.booking_state_id)
            USING ERRCODE = 'BK006';
    END IF;

    IF p_to_state_code IN ('CONFIRMED', 'DECLINED') AND p_now >= b.starts_at THEN
        RAISE EXCEPTION 'the booking has already started' USING ERRCODE = 'BK006';
    END IF;

    IF p_to_state_code = 'CANCELLED' THEN
        IF p_now >= b.starts_at THEN
            RAISE EXCEPTION 'a started booking cannot be cancelled; record attendance instead'
                USING ERRCODE = 'BK006';       -- R27
        END IF;
        IF p_actor_type_id = 1 THEN            -- R6, customers only
            SELECT p.cancellation_window_minutes INTO v_window
              FROM provider p WHERE p.provider_id = b.provider_id;
            IF p_now > b.starts_at - make_interval(mins => v_window) THEN
                RAISE EXCEPTION 'past the % minute cancellation window', v_window
                    USING ERRCODE = 'BK007';
            END IF;
        END IF;
    END IF;

    IF p_to_state_code IN ('COMPLETED', 'NO_SHOW') AND p_now < b.ends_at THEN
        RAISE EXCEPTION 'attendance cannot be recorded before the booking ends'
            USING ERRCODE = 'BK009';           -- R12
    END IF;

    -- Capacity follows the state, because booking_state says which states hold it.
    IF v_from_holds AND NOT v_to_holds THEN
        UPDATE session SET booked_count = booked_count - 1, updated_at = p_now
         WHERE session_id = b.session_id AND booked_count > 0;
    ELSIF NOT v_from_holds AND v_to_holds THEN
        UPDATE session SET booked_count = booked_count + 1, updated_at = p_now
         WHERE session_id = b.session_id AND booked_count < capacity;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'session is at capacity' USING ERRCODE = 'BK002';
        END IF;
    END IF;

    UPDATE booking
       SET booking_state_id           = v_to_id,
           hold_reason_id             = CASE WHEN v_to_id = 1 THEN hold_reason_id ELSE NULL END,
           hold_deadline              = CASE WHEN v_to_id = 1 THEN hold_deadline  ELSE NULL END,
           cancelled_by_actor_type_id = CASE WHEN v_to_id = 3 THEN p_actor_type_id ELSE cancelled_by_actor_type_id END,
           cancellation_reason        = CASE WHEN v_to_id = 3 THEN p_reason        ELSE cancellation_reason END,
           completion_source_id       = COALESCE(p_completion_source_id, completion_source_id),
           updated_at                 = p_now
     WHERE booking_id = p_booking_id;

    SELECT COALESCE(max(sequence_no), 0) + 1 INTO v_seq
      FROM booking_transition WHERE booking_id = p_booking_id;

    INSERT INTO booking_transition (booking_id, sequence_no, session_id, from_state_id,
                                    to_state_id, actor_type_id, actor_subject, reason)
    SELECT p_booking_id, v_seq, cur.session_id, b.booking_state_id, v_to_id,
           p_actor_type_id, p_actor_subject, p_reason
      FROM booking cur WHERE cur.booking_id = p_booking_id;

    v_event := COALESCE(p_event_code_override, CASE p_to_state_code
        WHEN 'HELD'      THEN 'BookingHeld'
        WHEN 'CONFIRMED' THEN 'BookingConfirmed'
        WHEN 'DECLINED'  THEN 'BookingDeclined'
        WHEN 'CANCELLED' THEN 'BookingCancelled'
        WHEN 'EXPIRED'   THEN 'BookingExpired'
        WHEN 'COMPLETED' THEN 'BookingCompleted'
        WHEN 'NO_SHOW'   THEN 'BookingNoShow' END);
    PERFORM fn_emit_event(p_booking_id, v_event, v_seq, p_event_id);

    RETURN v_seq;
END;
$$;
COMMENT ON FUNCTION fn_transition_booking(bigint,text,integer,text,text,timestamptz,uuid,integer,integer,text) IS
'The single gate through which every booking state change passes — provider approval, cancellation, attendance, the system sweeps, and any future consumed gate resolution alike (UC14). Permitted moves come from booking_transition_rule, so the forbidden-transition list of requirements 4.1 is enforced by data rather than by branches.';

CREATE FUNCTION fn_reschedule_booking(
    p_booking_id         bigint,
    p_new_resource_id    bigint,
    p_new_starts_at      timestamptz,
    p_actor_type_id      integer ,
    p_actor_subject      text,
    p_now                timestamptz,
    p_event_id           uuid,
    p_new_hold_reason_id integer DEFAULT NULL
) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    b            booking%ROWTYPE;
    v_service_id bigint;
    v_old_sess   bigint;
    v_resource   bigint;
    v_a          record;
    v_new_sess   bigint;
    v_s_starts   timestamptz;
    v_s_ends     timestamptz;
    v_to_state   smallint;
    v_deadline   timestamptz;
BEGIN
    SELECT * INTO b FROM booking WHERE booking_id = p_booking_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'booking % not found', p_booking_id USING ERRCODE = 'BK006';
    END IF;
    IF (SELECT bs.is_terminal FROM booking_state bs WHERE bs.booking_state_id = b.booking_state_id) THEN
        RAISE EXCEPTION 'a terminal booking cannot be rescheduled' USING ERRCODE = 'BK006';
    END IF;

    SELECT s.service_id, s.session_id, s.resource_id
      INTO v_service_id, v_old_sess, v_resource
      FROM session s WHERE s.session_id = b.session_id;
    v_resource := COALESCE(p_new_resource_id, v_resource);

    -- R10: identical checks to a new booking, by construction.
    SELECT * INTO v_a FROM fn_assert_bookable(v_service_id, v_resource, p_new_starts_at,
                                              p_actor_type_id, p_now);

    -- R9: release and take inside one transaction.  Releasing first is what lets
    -- a booking move within its own buffer shadow; if the take fails, the whole
    -- function raises and the release rolls back with it, so the booking is left
    -- exactly where it was.
    UPDATE session SET booked_count = booked_count - 1, updated_at = p_now
     WHERE session_id = v_old_sess AND booked_count > 0;

    BEGIN
        INSERT INTO session (provider_id, resource_id, service_id, starts_at, ends_at,
                             duration_minutes, capacity, buffer_before_minutes,
                             buffer_after_minutes, booked_count, occupied_range)
        VALUES (v_a.provider_id, v_resource, v_service_id, p_new_starts_at, v_a.ends_at,
                v_a.duration_minutes, v_a.capacity, v_a.buffer_before_minutes,
                v_a.buffer_after_minutes, 1, 'empty')
        ON CONFLICT (resource_id, service_id, starts_at) DO UPDATE
            SET booked_count = session.booked_count + 1,
                updated_at   = p_now
            WHERE session.booked_count < session.capacity
        RETURNING session.session_id, session.starts_at, session.ends_at
             INTO v_new_sess, v_s_starts, v_s_ends;
    EXCEPTION WHEN exclusion_violation THEN
        RAISE EXCEPTION 'the resource is occupied at that time, buffers included'
            USING ERRCODE = 'BK001';
    END;
    IF v_new_sess IS NULL THEN
        RAISE EXCEPTION 'session is at capacity' USING ERRCODE = 'BK002';
    END IF;

    IF p_new_hold_reason_id IS NULL THEN
        v_to_state := 2; v_deadline := NULL;                       -- stays CONFIRMED
    ELSE
        v_to_state := 1;                                           -- back to HELD (R11)
        SELECT LEAST(p_now + make_interval(mins =>
                   CASE WHEN hr.code = 'AWAITING_PROVIDER_APPROVAL'
                        THEN v_a.approval_hold_ttl_minutes ELSE hr.default_ttl_minutes END),
               v_s_starts)
          INTO v_deadline FROM hold_reason hr WHERE hr.hold_reason_id = p_new_hold_reason_id;
    END IF;

    UPDATE booking
       SET session_id       = v_new_sess,
           starts_at        = v_s_starts,
           ends_at          = v_s_ends,
           reschedule_count = reschedule_count + 1,
           hold_reason_id   = p_new_hold_reason_id,
           hold_deadline    = v_deadline,
           updated_at       = p_now
     WHERE booking_id = p_booking_id;

    -- The state change, its history row and its event go through the one
    -- transition path, so the permitted-move check is not duplicated here.
    RETURN fn_transition_booking(
               p_booking_id,
               CASE WHEN v_to_state = 1 THEN 'HELD' ELSE 'CONFIRMED' END,
               p_actor_type_id, p_actor_subject, NULL, p_now, p_event_id,
               NULL, NULL, 'BookingRescheduled');
END;
$$;
COMMENT ON FUNCTION fn_reschedule_booking(bigint,bigint,timestamptz,integer,text,timestamptz,uuid,integer) IS
'UC8. Atomic by transaction rather than by compensation (R9): the old capacity is released and the new taken in one statement pair, and any failure rolls both back. Booking identity is preserved; whether the move returns the booking to HELD is the caller''s decision under R11 and R33.';

CREATE FUNCTION fn_expire_holds(p_now timestamptz, p_limit integer DEFAULT 500)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE r record; n integer := 0;
BEGIN
    -- SKIP LOCKED lets several instances sweep at once without contending or
    -- double-expiring (UC11, NF6).  A booking approved in the same instant is
    -- filtered out when the row is re-read under its lock, because hold_deadline
    -- is cleared by the transition.
    FOR r IN
        SELECT booking_id FROM booking
         WHERE hold_deadline IS NOT NULL AND hold_deadline <= p_now
         ORDER BY hold_deadline
         LIMIT p_limit FOR UPDATE SKIP LOCKED
    LOOP
        PERFORM fn_transition_booking(r.booking_id, 'EXPIRED', 3, NULL,
                                      'hold deadline passed', p_now, gen_random_uuid());
        n := n + 1;
    END LOOP;
    RETURN n;
END;
$$;
COMMENT ON FUNCTION fn_expire_holds(timestamptz, integer) IS
'UC11. Releases capacity held by lapsed gates, whatever the gate is — a payment hold would be swept by this same function with no change (LD16).';

CREATE FUNCTION fn_auto_complete(p_now timestamptz, p_limit integer DEFAULT 500)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE r record; n integer := 0;
BEGIN
    -- Only CONFIRMED bookings are candidates, so an explicit provider mark —
    -- which has already moved the booking to a terminal state — is never
    -- overridden (UC12).
    FOR r IN
        SELECT b.booking_id FROM booking b
          JOIN provider p ON p.provider_id = b.provider_id
          JOIN booking_state bs ON bs.booking_state_id = b.booking_state_id
         WHERE bs.code = 'CONFIRMED'
           AND b.ends_at < p_now - make_interval(days => p.auto_complete_grace_days)
         ORDER BY b.ends_at
         LIMIT p_limit FOR UPDATE OF b SKIP LOCKED
    LOOP
        PERFORM fn_transition_booking(r.booking_id, 'COMPLETED', 3, NULL,
                                      'auto-completed after grace period', p_now,
                                      gen_random_uuid(), NULL, 2);
        n := n + 1;
    END LOOP;
    RETURN n;
END;
$$;
COMMENT ON FUNCTION fn_auto_complete(timestamptz, integer) IS
'UC12, R13. Marks the completion source SYSTEM so history distinguishes an auto-completion from a provider''s mark.';

CREATE FUNCTION fn_record_gate_rejection(p_booking_id bigint, p_event_id uuid)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    -- R28.  Deliberately changes nothing: it publishes the booking's ACTUAL
    -- state so the sender of the late resolution can compensate — refund a
    -- payment taken against a hold that had already expired, for instance.
    PERFORM fn_emit_event(p_booking_id, 'BookingGateResolutionRejected', NULL, p_event_id);
END;
$$;
COMMENT ON FUNCTION fn_record_gate_rejection(bigint, uuid) IS
'Publishes BookingGateResolutionRejected for a gate resolution that could not be applied (R28). Writes no transition, which is why its outbox row carries a NULL transition_sequence_no.';

