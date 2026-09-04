-- UC5 and UC6.  One call performs eligibility, R4, R5, R3, capacity, the booking
-- row, its first transition and its outbox event inside one transaction
-- (data-design.md section 4).  Rejections arrive as BK* SQLSTATEs.
--
-- Lookup ids are resolved by code in the statement rather than hard-coded in
-- Kotlin, so the seed in ddl/04-seed-lookups.sql stays the single source of
-- truth for them.  A NULL :holdReason correctly yields no gate.
SELECT booking_id, public_ref, state_code, was_replay
  FROM fn_create_booking(
           :customerId::bigint,
           :serviceId::bigint,
           :resourceId::bigint,
           :startsAt::timestamptz,
           (SELECT hold_reason_id FROM hold_reason WHERE code = :holdReason::text),
           (SELECT actor_type_id  FROM actor_type  WHERE code = :actorType::text),
           :actorSubject::text,
           :idempotencyKey::text,
           :now::timestamptz,
           :bookingRef::uuid,
           :eventId::uuid)
