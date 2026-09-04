-- Every state change goes through this one function (UC6, UC7, UC9, UC11, UC12),
-- so the permitted-move table and the outbox write are never bypassed.
SELECT fn_transition_booking(
           :bookingId::bigint,
           :targetState::text,
           (SELECT actor_type_id FROM actor_type WHERE code = :actorType::text),
           :actorSubject::text,
           :reason::text,
           :now::timestamptz,
           :eventId::uuid,
           (SELECT hold_reason_id FROM hold_reason WHERE code = :gate::text),
           (SELECT completion_source_id FROM completion_source WHERE code = :completionSource::text)
       ) AS sequence_no
