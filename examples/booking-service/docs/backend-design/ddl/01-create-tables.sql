-- Booking service — tables, constraints and comments.
-- Engine: PostgreSQL 16+.  See docs/backend-design/data-design.md for rationale.
--
-- Requirement ids (R*, NF*, UC*, LD*) refer to docs/backend-design/requirements.md r4.

-- btree_gist lets an exclusion constraint combine an equality column
-- (resource_id) with a range column (occupied_range).  This extension is what
-- makes NF4 / R1 / R31 enforceable by the store rather than by application code.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- =====================================================================
-- Lookup tables
-- =====================================================================

CREATE TABLE booking_state (
    booking_state_id smallint      PRIMARY KEY,
    code             text          NOT NULL UNIQUE,
    is_terminal      boolean       NOT NULL,
    holds_capacity   boolean       NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now()
);
COMMENT ON TABLE  booking_state IS 'The booking lifecycle states of requirements r4 section 4.1. Seeded, never written at runtime.';
COMMENT ON COLUMN booking_state.booking_state_id IS 'Stable seeded identifier; values are fixed by 04-seed-lookups.sql and must never be renumbered.';
COMMENT ON COLUMN booking_state.code IS 'Uppercase state name as it appears in requirements section 4.1 and in published events, e.g. HELD, CONFIRMED, NO_SHOW.';
COMMENT ON COLUMN booking_state.is_terminal IS 'True when no transition may leave this state (R28 forbidden transitions). Read by fn_transition_booking rather than hard-coded.';
COMMENT ON COLUMN booking_state.holds_capacity IS 'True when a booking in this state occupies a unit of its session capacity (R1). True for HELD and CONFIRMED only.';
COMMENT ON COLUMN booking_state.created_at IS 'Row insertion time.';

CREATE TABLE hold_reason (
    hold_reason_id      smallint    PRIMARY KEY,
    code                text        NOT NULL UNIQUE,
    default_ttl_minutes integer     NOT NULL CHECK (default_ttl_minutes > 0),
    created_at          timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  hold_reason IS 'The external gates a HELD booking may await (LD16). Adding a gate — e.g. AWAITING_PAYMENT — is an INSERT here, never a schema or state-machine change.';
COMMENT ON COLUMN hold_reason.hold_reason_id IS 'Stable seeded identifier.';
COMMENT ON COLUMN hold_reason.code IS 'Uppercase gate name, e.g. AWAITING_PROVIDER_APPROVAL.';
COMMENT ON COLUMN hold_reason.default_ttl_minutes IS 'Gate-level hold TTL in minutes (R8). Per-gate by design; provider.approval_hold_ttl_minutes overrides this for the approval gate only.';
COMMENT ON COLUMN hold_reason.created_at IS 'Row insertion time.';

CREATE TABLE actor_type (
    actor_type_id smallint    PRIMARY KEY,
    code          text        NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  actor_type IS 'Who caused a transition: CUSTOMER, PROVIDER or SYSTEM (R23).';
COMMENT ON COLUMN actor_type.actor_type_id IS 'Stable seeded identifier.';
COMMENT ON COLUMN actor_type.code IS 'Uppercase actor kind: CUSTOMER, PROVIDER, SYSTEM.';
COMMENT ON COLUMN actor_type.created_at IS 'Row insertion time.';

CREATE TABLE confirmation_mode (
    confirmation_mode_id smallint    PRIMARY KEY,
    code                 text        NOT NULL UNIQUE,
    created_at           timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  confirmation_mode IS 'Provider confirmation modes (LD2): INSTANT or APPROVAL. One input to the gate policy of R33, not itself the gate decision.';
COMMENT ON COLUMN confirmation_mode.confirmation_mode_id IS 'Stable seeded identifier.';
COMMENT ON COLUMN confirmation_mode.code IS 'Uppercase mode name: INSTANT, APPROVAL.';
COMMENT ON COLUMN confirmation_mode.created_at IS 'Row insertion time.';

CREATE TABLE availability_exception_type (
    availability_exception_type_id smallint    PRIMARY KEY,
    code                           text        NOT NULL UNIQUE,
    created_at                     timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  availability_exception_type IS 'One-off availability override kinds (UC3): BLOCK removes availability and wins over everything; OPEN adds it.';
COMMENT ON COLUMN availability_exception_type.availability_exception_type_id IS 'Stable seeded identifier.';
COMMENT ON COLUMN availability_exception_type.code IS 'Uppercase kind: BLOCK, OPEN.';
COMMENT ON COLUMN availability_exception_type.created_at IS 'Row insertion time.';

CREATE TABLE completion_source (
    completion_source_id smallint    PRIMARY KEY,
    code                 text        NOT NULL UNIQUE,
    created_at           timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  completion_source IS 'Distinguishes a provider marking attendance from the system auto-completing a stale booking (UC12, R13).';
COMMENT ON COLUMN completion_source.completion_source_id IS 'Stable seeded identifier.';
COMMENT ON COLUMN completion_source.code IS 'Uppercase source: PROVIDER, SYSTEM.';
COMMENT ON COLUMN completion_source.created_at IS 'Row insertion time.';

-- =====================================================================
-- Tenant entities
-- =====================================================================

CREATE TABLE provider (
    provider_id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref                  uuid        NOT NULL UNIQUE,
    name                        text        NOT NULL CHECK (length(btrim(name)) > 0),
    timezone                    text        NOT NULL
        -- A CHECK may not contain a subquery, so pg_timezone_names cannot be
        -- consulted here.  "AT TIME ZONE <col>" resolves to timezone(text,
        -- timestamptz), which is IMMUTABLE and raises on an unknown zone name,
        -- so an invalid zone is rejected at write time either way.
        CHECK ((to_timestamp(0) AT TIME ZONE timezone) IS NOT NULL),
    confirmation_mode_id        smallint    NOT NULL REFERENCES confirmation_mode,
    min_lead_minutes            integer     NOT NULL DEFAULT 60   CHECK (min_lead_minutes >= 0),
    booking_horizon_days        integer     NOT NULL DEFAULT 90   CHECK (booking_horizon_days > 0),
    cancellation_window_minutes integer     NOT NULL DEFAULT 1440 CHECK (cancellation_window_minutes >= 0),
    approval_hold_ttl_minutes   integer     NOT NULL DEFAULT 1440 CHECK (approval_hold_ttl_minutes > 0),
    auto_complete_grace_days    integer     NOT NULL DEFAULT 7    CHECK (auto_complete_grace_days > 0),
    is_active                   boolean     NOT NULL DEFAULT true,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_id, timezone)
);
COMMENT ON TABLE  provider IS 'A booking business (UC1). Owns resources, services and the booking policy values of R4-R8, R13.';
COMMENT ON COLUMN provider.provider_id IS 'Internal surrogate key. Never exposed over the API.';
COMMENT ON COLUMN provider.public_ref IS 'Non-enumerable identifier used in the API and in published events. UUIDv7, generated by the application.';
COMMENT ON COLUMN provider.name IS 'Business display name. Non-blank.';
COMMENT ON COLUMN provider.timezone IS 'IANA timezone name exactly as it appears in pg_timezone_names, e.g. Europe/Berlin. Availability rules are wall-clock in this zone (R14). The CHECK rejects any name Postgres does not recognise.';
COMMENT ON COLUMN provider.confirmation_mode_id IS 'INSTANT or APPROVAL (LD2). An input to the gate policy of R33, never read as the gate decision itself.';
COMMENT ON COLUMN provider.min_lead_minutes IS 'Minimum notice for a CUSTOMER-initiated booking, in minutes (R4). Provider-initiated bookings bypass this.';
COMMENT ON COLUMN provider.booking_horizon_days IS 'How far ahead a booking may start, in days (R5). Binds providers as well as customers, because it also bounds availability expansion.';
COMMENT ON COLUMN provider.cancellation_window_minutes IS 'How long before start a customer may still cancel, in minutes (R6). After this only the provider may cancel.';
COMMENT ON COLUMN provider.approval_hold_ttl_minutes IS 'Overrides hold_reason.default_ttl_minutes for the AWAITING_PROVIDER_APPROVAL gate only (R8, PD3).';
COMMENT ON COLUMN provider.auto_complete_grace_days IS 'Days after a booking ends before the system auto-completes it (R13).';
COMMENT ON COLUMN provider.is_active IS 'False hides the provider entirely: no availability, no new bookings. Existing bookings are unaffected.';
COMMENT ON COLUMN provider.created_at IS 'Row insertion time.';
COMMENT ON COLUMN provider.updated_at IS 'Last modification time, supplied by the backend from its injected clock.';

CREATE TABLE provider_admin (
    provider_admin_id bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id       bigint      NOT NULL REFERENCES provider ON DELETE CASCADE,
    idp_subject       text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_id, idp_subject)
);
COMMENT ON TABLE  provider_admin IS 'Grants an IdP subject admin rights over one provider (R18). Membership is resolved here, not from a vendor token claim (PD1).';
COMMENT ON COLUMN provider_admin.provider_admin_id IS 'Internal surrogate key.';
COMMENT ON COLUMN provider_admin.provider_id IS 'The provider this subject administers.';
COMMENT ON COLUMN provider_admin.idp_subject IS 'The external IdP subject identifier, taken verbatim from the verified token sub claim (R17).';
COMMENT ON COLUMN provider_admin.created_at IS 'Row insertion time.';

CREATE TABLE resource (
    resource_id bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref  uuid        NOT NULL UNIQUE,
    provider_id bigint      NOT NULL REFERENCES provider,
    name        text        NOT NULL CHECK (length(btrim(name)) > 0),
    idp_subject text        UNIQUE,
    is_active   boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (resource_id, provider_id)
);
COMMENT ON TABLE  resource IS 'A bookable staff member or room (LD6). The thing a session occupies; R1 is scoped per resource.';
COMMENT ON COLUMN resource.resource_id IS 'Internal surrogate key.';
COMMENT ON COLUMN resource.public_ref IS 'Non-enumerable identifier used in the API and in published events. UUIDv7.';
COMMENT ON COLUMN resource.provider_id IS 'Owning provider.';
COMMENT ON COLUMN resource.name IS 'Display name of the staff member or room. Non-blank.';
COMMENT ON COLUMN resource.idp_subject IS 'Optional IdP subject of the person this resource represents, granting them read access to their own bookings only (R24). NULL for rooms and unlinked staff.';
COMMENT ON COLUMN resource.is_active IS 'False yields no availability and accepts no new bookings; existing bookings survive and must be resolved explicitly (requirements 4.2). Deactivation replaces deletion (R19).';
COMMENT ON COLUMN resource.created_at IS 'Row insertion time.';
COMMENT ON COLUMN resource.updated_at IS 'Last modification time, supplied by the backend.';

CREATE TABLE service (
    service_id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref             uuid        NOT NULL UNIQUE,
    provider_id            bigint      NOT NULL REFERENCES provider,
    name                   text        NOT NULL CHECK (length(btrim(name)) > 0),
    duration_minutes       integer     NOT NULL CHECK (duration_minutes > 0),
    capacity               integer     NOT NULL DEFAULT 1 CHECK (capacity >= 1),
    buffer_before_minutes  integer     NOT NULL DEFAULT 0 CHECK (buffer_before_minutes >= 0),
    buffer_after_minutes   integer     NOT NULL DEFAULT 0 CHECK (buffer_after_minutes >= 0),
    slot_step_minutes      integer     NOT NULL DEFAULT 15 CHECK (slot_step_minutes > 0),
    is_active              boolean     NOT NULL DEFAULT true,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_id, provider_id)
);
COMMENT ON TABLE  service IS 'A named offering with its own duration, capacity and buffers (LD7, LD17). Its values are snapshotted onto a session at creation (R25).';
COMMENT ON COLUMN service.service_id IS 'Internal surrogate key.';
COMMENT ON COLUMN service.public_ref IS 'Non-enumerable identifier used in the API and in published events. UUIDv7.';
COMMENT ON COLUMN service.provider_id IS 'Owning provider.';
COMMENT ON COLUMN service.name IS 'Display name, e.g. "Haircut" or "Vinyasa flow". Non-blank.';
COMMENT ON COLUMN service.duration_minutes IS 'Length of the booked time range in minutes, excluding buffers. Strictly positive.';
COMMENT ON COLUMN service.capacity IS 'Maximum concurrent bookings within one session of this service (R2). 1 means a 1:1 appointment; higher means a group class.';
COMMENT ON COLUMN service.buffer_before_minutes IS 'Minutes of resource time reserved immediately before the booked range, e.g. setup (R31). Blocks other sessions; never itself bookable.';
COMMENT ON COLUMN service.buffer_after_minutes IS 'Minutes of resource time reserved immediately after the booked range, e.g. cleanup or travel (R31). Buffers of adjoining sessions SUM rather than overlap; see data-design.md OQ1.';
COMMENT ON COLUMN service.slot_step_minutes IS 'Granularity of the candidate start times offered by availability search (UC4), in minutes. Starts are aligned to the beginning of each availability window, not to the wall clock. See OQ2.';
COMMENT ON COLUMN service.is_active IS 'False yields no availability and accepts no new bookings; existing bookings survive (requirements 4.2, R19).';
COMMENT ON COLUMN service.created_at IS 'Row insertion time.';
COMMENT ON COLUMN service.updated_at IS 'Last modification time, supplied by the backend.';

CREATE TABLE service_resource (
    service_id  bigint      NOT NULL,
    resource_id bigint      NOT NULL,
    provider_id bigint      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, resource_id),
    FOREIGN KEY (service_id,  provider_id) REFERENCES service  (service_id,  provider_id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id, provider_id) REFERENCES resource (resource_id, provider_id) ON DELETE CASCADE
);
COMMENT ON TABLE  service_resource IS 'Which resources may perform which service (UC1). The composite foreign keys carry provider_id so the database itself rejects linking a service to another provider''s resource.';
COMMENT ON COLUMN service_resource.service_id IS 'Eligible service.';
COMMENT ON COLUMN service_resource.resource_id IS 'Resource able to perform it.';
COMMENT ON COLUMN service_resource.provider_id IS 'Owning provider, present solely so both composite foreign keys can enforce that service and resource share it.';
COMMENT ON COLUMN service_resource.created_at IS 'Row insertion time.';

CREATE TABLE customer (
    customer_id  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref   uuid        NOT NULL UNIQUE,
    idp_subject  text        NOT NULL UNIQUE,
    display_name text,
    email        text,
    phone        text,
    erased_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  customer IS 'A booking end user, keyed to an external IdP subject (LD3). Holds PII; see NF5 and OQ10 for the erasure approach.';
COMMENT ON COLUMN customer.customer_id IS 'Internal surrogate key.';
COMMENT ON COLUMN customer.public_ref IS 'Non-enumerable identifier used in the API and in published events. UUIDv7. This is the only customer identifier that ever appears in an event payload (PD17).';
COMMENT ON COLUMN customer.idp_subject IS 'The external IdP subject identifier, taken verbatim from the verified token sub claim (R17). Never a credential.';
COMMENT ON COLUMN customer.display_name IS 'PII. Name as supplied by the customer. NULL once erased.';
COMMENT ON COLUMN customer.email IS 'PII. Contact email, resolved by consuming services on demand rather than shipped in events (PD17). NULL once erased.';
COMMENT ON COLUMN customer.phone IS 'PII. Contact phone in E.164 form, e.g. +4915112345678. NULL once erased.';
COMMENT ON COLUMN customer.erased_at IS 'When the PII columns were cleared under an erasure request (NF5). NULL while the profile is intact; booking history survives erasure.';
COMMENT ON COLUMN customer.created_at IS 'Row insertion time.';
COMMENT ON COLUMN customer.updated_at IS 'Last modification time, supplied by the backend.';

-- =====================================================================
-- Published availability (UC2, UC3).  Availability is never materialised into
-- slot rows: it is expressed as rules plus exceptions and computed on read
-- (R26).  The only rows describing occupancy are sessions, below.
-- =====================================================================

CREATE TABLE availability_rule (
    availability_rule_id bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref           uuid        NOT NULL UNIQUE,
    resource_id          bigint      NOT NULL REFERENCES resource,
    day_of_week          smallint    NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    start_time           time        NOT NULL,
    end_time             time        NOT NULL,
    effective_from       date        NOT NULL,
    effective_until      date,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT availability_rule_window_forward CHECK (end_time > start_time),
    CONSTRAINT availability_rule_dates_forward  CHECK (effective_until IS NULL OR effective_until >= effective_from)
);
COMMENT ON TABLE  availability_rule IS 'A repeating weekly window during which a resource is bookable (UC2, LD5). A resource''s published schedule is the union of its rules in effect on a given date, minus BLOCK exceptions.';
COMMENT ON COLUMN availability_rule.availability_rule_id IS 'Internal surrogate key.';
COMMENT ON COLUMN availability_rule.public_ref IS 'Non-enumerable identifier used in the API. UUIDv7.';
COMMENT ON COLUMN availability_rule.resource_id IS 'The resource this rule publishes availability for.';
COMMENT ON COLUMN availability_rule.day_of_week IS 'ISO-style day index in the provider timezone, 0 = Sunday through 6 = Saturday, matching PostgreSQL EXTRACT(DOW).';
COMMENT ON COLUMN availability_rule.start_time IS 'Window start as a wall-clock local time in the provider timezone, e.g. 09:00. Expanded per date against provider.timezone so DST is handled correctly (R14).';
COMMENT ON COLUMN availability_rule.end_time IS 'Window end as a wall-clock local time in the provider timezone. Must be strictly later than start_time: windows never cross midnight, a provider needing overnight hours writes two rules (see OQ4).';
COMMENT ON COLUMN availability_rule.effective_from IS 'First calendar date, in the provider timezone, on which this rule produces availability.';
COMMENT ON COLUMN availability_rule.effective_until IS 'Last calendar date on which it produces availability; NULL means open-ended. Setting it is how a rule is ENDED (requirements 4.2); rules are never hard-deleted (R20).';
COMMENT ON COLUMN availability_rule.created_at IS 'Row insertion time.';
COMMENT ON COLUMN availability_rule.updated_at IS 'Last modification time, supplied by the backend.';

CREATE TABLE availability_exception (
    availability_exception_id      bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref                     uuid        NOT NULL UNIQUE,
    resource_id                    bigint      NOT NULL REFERENCES resource,
    availability_exception_type_id smallint    NOT NULL REFERENCES availability_exception_type,
    starts_at                      timestamptz NOT NULL,
    ends_at                        timestamptz NOT NULL,
    reason                         text,
    created_at                     timestamptz NOT NULL DEFAULT now(),
    updated_at                     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT availability_exception_forward CHECK (ends_at > starts_at)
);
COMMENT ON TABLE  availability_exception IS 'A one-off override of the recurring rules for a resource (UC3). BLOCK removes availability and beats both rules and OPEN; OPEN adds availability where no rule covers.';
COMMENT ON COLUMN availability_exception.availability_exception_id IS 'Internal surrogate key.';
COMMENT ON COLUMN availability_exception.public_ref IS 'Non-enumerable identifier used in the API. UUIDv7.';
COMMENT ON COLUMN availability_exception.resource_id IS 'The resource this exception applies to.';
COMMENT ON COLUMN availability_exception.availability_exception_type_id IS 'BLOCK or OPEN.';
COMMENT ON COLUMN availability_exception.starts_at IS 'Absolute start instant. Unlike availability_rule, exceptions are stored as absolute instants, not wall-clock times, because they name a concrete occasion (R14).';
COMMENT ON COLUMN availability_exception.ends_at IS 'Absolute end instant, exclusive. Strictly later than starts_at.';
COMMENT ON COLUMN availability_exception.reason IS 'Free-text note shown to the provider, e.g. "public holiday". Not interpreted by the service.';
COMMENT ON COLUMN availability_exception.created_at IS 'Row insertion time.';
COMMENT ON COLUMN availability_exception.updated_at IS 'Last modification time, supplied by the backend.';

-- =====================================================================
-- Occupancy: the session (R1) — the load-bearing table of this schema.
-- =====================================================================

CREATE TABLE session (
    session_id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id           bigint      NOT NULL,
    resource_id           bigint      NOT NULL,
    service_id            bigint      NOT NULL,
    starts_at             timestamptz NOT NULL,
    ends_at               timestamptz NOT NULL,
    duration_minutes      integer     NOT NULL CHECK (duration_minutes > 0),
    capacity              integer     NOT NULL CHECK (capacity >= 1),
    buffer_before_minutes integer     NOT NULL CHECK (buffer_before_minutes >= 0),
    buffer_after_minutes  integer     NOT NULL CHECK (buffer_after_minutes >= 0),
    booked_count          integer     NOT NULL DEFAULT 0,
    occupied_range        tstzrange   NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT session_forward       CHECK (ends_at > starts_at),
    CONSTRAINT session_within_capacity CHECK (booked_count >= 0 AND booked_count <= capacity),
    FOREIGN KEY (resource_id, provider_id) REFERENCES resource (resource_id, provider_id),
    FOREIGN KEY (service_id,  provider_id) REFERENCES service  (service_id,  provider_id),
    CONSTRAINT session_identity UNIQUE (resource_id, service_id, starts_at),
    CONSTRAINT session_no_overlap
        EXCLUDE USING gist (resource_id WITH =, occupied_range WITH &&)
        WHERE (booked_count > 0)
);
COMMENT ON TABLE  session IS
'The unit a resource is actually occupied by: one (resource, service, start, end) tuple holding up to capacity bookings (R1). '
'session_no_overlap is the single mechanism enforcing NF4, R1 and R31 — a resource hosts at most one occupied session at any instant, '
'buffers included. It is a GiST exclusion constraint, so the guarantee is held by the index rather than by any application check. '
'The WHERE (booked_count > 0) predicate means an emptied session stops blocking its time; refilling it re-enters the index and is '
're-checked against whatever took the space meanwhile.';
COMMENT ON COLUMN session.session_id IS 'Internal surrogate key. Sessions are not addressed over the API; customers address bookings.';
COMMENT ON COLUMN session.provider_id IS 'Owning provider. Present so the composite foreign keys can enforce that resource and service belong to the same provider.';
COMMENT ON COLUMN session.resource_id IS 'The occupied resource. R1 is scoped per resource, so this is the equality column of session_no_overlap.';
COMMENT ON COLUMN session.service_id IS 'The service being delivered. Two different services at the same instant on one resource are two sessions, and therefore conflict.';
COMMENT ON COLUMN session.starts_at IS 'Absolute start of the booked range, exclusive of buffer. This is the time a customer sees and books.';
COMMENT ON COLUMN session.ends_at IS 'Absolute end of the booked range, exclusive, equal to starts_at plus duration_minutes. Exclusive of buffer.';
COMMENT ON COLUMN session.duration_minutes IS 'Snapshot of service.duration_minutes when this session was created (R25). Later edits to the service never alter an existing session.';
COMMENT ON COLUMN session.capacity IS 'Snapshot of service.capacity (R25). The ceiling booked_count is checked against; the session, not the current service row, is authoritative (see OQ5).';
COMMENT ON COLUMN session.buffer_before_minutes IS 'Snapshot of service.buffer_before_minutes (R25, R31). Widens occupied_range backwards.';
COMMENT ON COLUMN session.buffer_after_minutes IS 'Snapshot of service.buffer_after_minutes (R25, R31). Widens occupied_range forwards.';
COMMENT ON COLUMN session.booked_count IS 'Number of bookings on this session in a capacity-holding state (booking_state.holds_capacity). Maintained only by the functions in 03-functions.sql, never by direct DML; see the reconciliation query in data-design.md.';
COMMENT ON COLUMN session.occupied_range IS
'[starts_at - buffer_before, ends_at + buffer_after), the span this session denies to every other session on the resource. '
'Maintained by trigger, not GENERATED: "timestamptz + interval" is STABLE rather than IMMUTABLE, which Postgres refuses in a generation expression.';
COMMENT ON COLUMN session.created_at IS 'Row insertion time.';
COMMENT ON COLUMN session.updated_at IS 'Last modification time, supplied by the backend.';

CREATE FUNCTION session_set_occupied_range() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.occupied_range := tstzrange(
        NEW.starts_at - make_interval(mins => NEW.buffer_before_minutes),
        NEW.ends_at   + make_interval(mins => NEW.buffer_after_minutes),
        '[)');
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION session_set_occupied_range() IS 'Derives session.occupied_range from the range and buffer columns. Exists because a GENERATED column may not use the STABLE timestamptz + interval operator.';

CREATE TRIGGER session_occupied_range
    BEFORE INSERT OR UPDATE OF starts_at, ends_at, buffer_before_minutes, buffer_after_minutes
    ON session
    FOR EACH ROW EXECUTE FUNCTION session_set_occupied_range();

CREATE TABLE event_type (
    event_type_id smallint    PRIMARY KEY,
    code          text        NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  event_type IS 'The kinds of message this service publishes (UC13). Seeded here from requirements section 4.1; the phase-2 event catalogue is the authority on payload shape and owns any additions.';
COMMENT ON COLUMN event_type.event_type_id IS 'Stable seeded identifier.';
COMMENT ON COLUMN event_type.code IS 'Event name as published, e.g. BookingConfirmed, BookingGateResolutionRejected.';
COMMENT ON COLUMN event_type.created_at IS 'Row insertion time.';

-- =====================================================================
-- Booking and its immutable history
-- =====================================================================

CREATE TABLE booking (
    booking_id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref                 uuid        NOT NULL UNIQUE,
    customer_id                bigint      NOT NULL REFERENCES customer,
    session_id                 bigint      NOT NULL REFERENCES session,
    provider_id                bigint      NOT NULL REFERENCES provider,
    booking_state_id           smallint    NOT NULL REFERENCES booking_state,
    hold_reason_id             smallint             REFERENCES hold_reason,
    hold_deadline              timestamptz,
    starts_at                  timestamptz NOT NULL,
    ends_at                    timestamptz NOT NULL,
    created_by_actor_type_id   smallint    NOT NULL REFERENCES actor_type,
    idempotency_key            text,
    reschedule_count           integer     NOT NULL DEFAULT 0 CHECK (reschedule_count >= 0),
    cancelled_by_actor_type_id smallint             REFERENCES actor_type,
    cancellation_reason        text,
    completion_source_id       smallint             REFERENCES completion_source,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT booking_forward CHECK (ends_at > starts_at),
    -- A gate and its deadline exist together or not at all (R8).  That the pair
    -- is present exactly when the state is HELD is enforced by
    -- fn_transition_booking, which is the only writer of booking_state_id.
    CONSTRAINT booking_hold_pair CHECK ((hold_reason_id IS NULL) = (hold_deadline IS NULL))
);
COMMENT ON TABLE  booking IS 'One customer''s claim on one unit of a session''s capacity (LD15). Its lifecycle is requirements section 4.1; every state change is written by fn_transition_booking and recorded in booking_transition.';
COMMENT ON COLUMN booking.booking_id IS 'Internal surrogate key.';
COMMENT ON COLUMN booking.public_ref IS 'Non-enumerable identifier used in the API and in published events. UUIDv7 — time-ordered so inserts stay local in the index, unlike a v4.';
COMMENT ON COLUMN booking.customer_id IS 'The customer holding the booking. Scopes read and write access under R18.';
COMMENT ON COLUMN booking.session_id IS 'The session whose capacity this booking occupies. A reschedule repoints this column (R9) and keeps booking_id, which is why identity survives a move (R8 of UC8).';
COMMENT ON COLUMN booking.provider_id IS 'Owning provider, copied from the session. Denormalised so every tenant-scoped query filters without joining session and resource; maintained only by the booking functions.';
COMMENT ON COLUMN booking.booking_state_id IS 'Current lifecycle state. Whether it holds capacity or is terminal is read from booking_state, never hard-coded.';
COMMENT ON COLUMN booking.hold_reason_id IS 'While HELD, the single external gate awaited (LD16). NULL in every other state. Today only AWAITING_PROVIDER_APPROVAL occurs.';
COMMENT ON COLUMN booking.hold_deadline IS 'When the hold lapses: min(gate TTL from now, starts_at) per R8. NULL unless HELD. The expiry job of UC11 reads this column.';
COMMENT ON COLUMN booking.starts_at IS 'Copy of session.starts_at, denormalised so the customer timeline, the cancellation-window test (R6) and the auto-complete sweep (UC12) need no join. Repointed together with session_id on reschedule.';
COMMENT ON COLUMN booking.ends_at IS 'Copy of session.ends_at, denormalised for the same reason. Attendance may only be marked after this instant (R12).';
COMMENT ON COLUMN booking.created_by_actor_type_id IS 'CUSTOMER or PROVIDER. Decides whether the minimum lead time of R4 applied: a provider-initiated booking bypasses it.';
COMMENT ON COLUMN booking.idempotency_key IS 'Caller-supplied key making creation idempotent per customer (R15). Unique per customer where present; NULL for bookings created without one.';
COMMENT ON COLUMN booking.reschedule_count IS 'How many times this booking has moved (UC8). Recorded so a limit can be introduced later without a schema change (PD5).';
COMMENT ON COLUMN booking.cancelled_by_actor_type_id IS 'Who cancelled, once cancelled (R7). NULL otherwise.';
COMMENT ON COLUMN booking.cancellation_reason IS 'Free-text reason, mandatory for a provider cancellation (R7). NULL otherwise.';
COMMENT ON COLUMN booking.completion_source_id IS 'PROVIDER when attendance was marked by a person, SYSTEM when auto-completed (R13, UC12). NULL until the booking completes.';
COMMENT ON COLUMN booking.created_at IS 'Row insertion time.';
COMMENT ON COLUMN booking.updated_at IS 'Last modification time, supplied by the backend.';

CREATE TABLE booking_transition (
    booking_transition_id bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id            bigint      NOT NULL REFERENCES booking,
    sequence_no           integer     NOT NULL CHECK (sequence_no >= 1),
    session_id            bigint      NOT NULL REFERENCES session,
    from_state_id         smallint             REFERENCES booking_state,
    to_state_id           smallint    NOT NULL REFERENCES booking_state,
    actor_type_id         smallint    NOT NULL REFERENCES actor_type,
    actor_subject         text,
    reason                text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT booking_transition_seq UNIQUE (booking_id, sequence_no)
);
COMMENT ON TABLE  booking_transition IS 'Append-only history of every booking state change (R23). Never updated, never deleted. Its sequence_no is the per-booking ordering key consumers use to order events (R29).';
COMMENT ON COLUMN booking_transition.booking_transition_id IS 'Internal surrogate key.';
COMMENT ON COLUMN booking_transition.booking_id IS 'The booking that moved.';
COMMENT ON COLUMN booking_transition.sequence_no IS 'Position in this booking''s history, starting at 1 for creation and incrementing by 1. Published in the event so a consumer can order and dedupe (R29).';
COMMENT ON COLUMN booking_transition.session_id IS 'The session the booking occupied at this point in its history. Without it a reschedule would erase the previous time entirely, leaving R23''s history incomplete and making BookingRescheduled unable to report what the booking moved from.';
COMMENT ON COLUMN booking_transition.from_state_id IS 'State left. NULL on the creation transition, which has no predecessor.';
COMMENT ON COLUMN booking_transition.to_state_id IS 'State entered.';
COMMENT ON COLUMN booking_transition.actor_type_id IS 'CUSTOMER, PROVIDER or SYSTEM.';
COMMENT ON COLUMN booking_transition.actor_subject IS 'IdP subject of the person responsible, verbatim from the verified token. NULL when actor_type is SYSTEM.';
COMMENT ON COLUMN booking_transition.reason IS 'Free-text justification where one applies, e.g. a provider cancellation reason (R7). NULL otherwise.';
COMMENT ON COLUMN booking_transition.created_at IS 'When the transition committed. This table is insert-only, so it carries no updated_at.';

-- =====================================================================
-- Transactional outbox (R22) and consumer inbox (R29)
-- =====================================================================

CREATE TABLE outbox_event (
    outbox_event_id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id               uuid        NOT NULL UNIQUE,
    event_type_id          smallint    NOT NULL REFERENCES event_type,
    booking_id             bigint      NOT NULL REFERENCES booking,
    transition_sequence_no integer,
    schema_version         integer     NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    payload                jsonb       NOT NULL,
    dispatched_at          timestamptz,
    attempt_count          integer     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at        timestamptz NOT NULL DEFAULT now(),
    last_error             text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT outbox_event_per_transition UNIQUE (booking_id, transition_sequence_no),
    FOREIGN KEY (booking_id, transition_sequence_no)
        REFERENCES booking_transition (booking_id, sequence_no)
);
COMMENT ON TABLE  outbox_event IS
'Transactional outbox (R22). A row is inserted in the same transaction as the state change that caused it, so no committed transition can lack its event and no event can survive a rollback. '
'A separate dispatcher delivers rows to the broker asynchronously, which is why a broker outage delays messages and never fails a booking. '
'The composite foreign key to booking_transition plus outbox_event_per_transition make "exactly one event per transition" a database guarantee rather than a convention.';
COMMENT ON COLUMN outbox_event.outbox_event_id IS 'Internal surrogate key, and the dispatch order within a booking.';
COMMENT ON COLUMN outbox_event.event_id IS 'Globally unique event identifier published to consumers for deduplication (R29). Any UUID version: application-minted events use v7, the database sweeps use v4, and neither matters because event ORDER is carried by transition_sequence_no, never by this id.';
COMMENT ON COLUMN outbox_event.event_type_id IS 'Which event this is.';
COMMENT ON COLUMN outbox_event.booking_id IS 'The booking the event concerns. Also the partition key consumers order by (R29).';
COMMENT ON COLUMN outbox_event.transition_sequence_no IS
'The booking_transition this event reports, giving one event per transition. NULL for events that report no state change — today only BookingGateResolutionRejected (R28), which fires precisely because nothing changed. '
'NULL rows are exempt from both the unique constraint and the foreign key, so a booking may accumulate several rejections.';
COMMENT ON COLUMN outbox_event.schema_version IS 'Version of the payload shape for this event type (R29). Incremented when a payload changes incompatibly.';
COMMENT ON COLUMN outbox_event.payload IS 'The published message body as JSON. JSONB rather than columns because the shape is owned by the event contract and varies per event type; it carries public_ref identifiers only and never PII (PD17, NF5).';
COMMENT ON COLUMN outbox_event.dispatched_at IS 'When the broker acknowledged the message. NULL while undelivered; the dispatcher and the NF11 lag alert both key on this.';
COMMENT ON COLUMN outbox_event.attempt_count IS 'Delivery attempts so far, used for backoff and for the repeated-failure alert of NF11.';
COMMENT ON COLUMN outbox_event.next_attempt_at IS 'Earliest instant the dispatcher may try again. Set forward on failure to implement backoff.';
COMMENT ON COLUMN outbox_event.last_error IS 'Message from the most recent failed attempt, for operators. NULL if never failed.';
COMMENT ON COLUMN outbox_event.created_at IS 'Row insertion time, which is also the transition time.';
COMMENT ON COLUMN outbox_event.updated_at IS 'Last modification time, moved by each dispatch attempt.';

CREATE TABLE inbox_message (
    inbox_message_id bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id       text        NOT NULL UNIQUE,
    message_type     text        NOT NULL,
    payload          jsonb       NOT NULL,
    processed_at     timestamptz,
    outcome          text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE  inbox_message IS
'Deduplication record for messages consumed from the broker — today only gate resolutions (UC14). The unique message_id is what makes a redelivered message a no-op (R29), inserted in the same transaction as the effect it causes.';
COMMENT ON COLUMN inbox_message.inbox_message_id IS 'Internal surrogate key.';
COMMENT ON COLUMN inbox_message.message_id IS 'The producer''s unique message identifier, verbatim. A second delivery of the same id conflicts here and is discarded without re-applying its effect.';
COMMENT ON COLUMN inbox_message.message_type IS 'Producer''s message type name, recorded for operators; the consumer dispatches on it.';
COMMENT ON COLUMN inbox_message.payload IS 'The consumed message body as received, retained for diagnosis. Treated as untrusted input.';
COMMENT ON COLUMN inbox_message.processed_at IS 'When handling finished. NULL while in flight.';
COMMENT ON COLUMN inbox_message.outcome IS 'What handling decided, e.g. APPLIED or REJECTED_STATE_MISMATCH for a late gate resolution (R28). NULL until processed.';
COMMENT ON COLUMN inbox_message.created_at IS 'When the message was accepted for handling.';
COMMENT ON COLUMN inbox_message.updated_at IS 'Last modification time, supplied by the backend.';

CREATE TABLE booking_transition_rule (
    from_state_id     smallint             REFERENCES booking_state,
    to_state_id       smallint    NOT NULL REFERENCES booking_state,
    actor_type_id     smallint    NOT NULL REFERENCES actor_type,
    requires_gate     boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX booking_transition_rule_key
    ON booking_transition_rule (COALESCE(from_state_id, -1), to_state_id, actor_type_id);
COMMENT ON TABLE  booking_transition_rule IS
'The permitted transitions of requirements section 4.1, as data. fn_transition_booking admits a move only if a row here matches, so the forbidden-transition list is enforced by the seed rather than by branches in code, and is testable by querying the table.';
COMMENT ON COLUMN booking_transition_rule.from_state_id IS 'State being left. NULL means the creation transition, which has no predecessor.';
COMMENT ON COLUMN booking_transition_rule.to_state_id IS 'State being entered.';
COMMENT ON COLUMN booking_transition_rule.actor_type_id IS 'Which actor may perform this move. The same from/to pair may be legal for one actor and forbidden for another, e.g. only SYSTEM may expire a hold.';
COMMENT ON COLUMN booking_transition_rule.requires_gate IS 'True when the move is a gate resolution and the caller must therefore present a matching hold_reason_id (R28).';
COMMENT ON COLUMN booking_transition_rule.created_at IS 'Row insertion time.';

