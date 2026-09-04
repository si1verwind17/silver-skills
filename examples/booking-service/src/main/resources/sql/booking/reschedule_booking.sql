-- UC8.  Release and take happen inside this one call, so a failed take rolls the
-- release back and the booking is left exactly where it was (R9).
SELECT fn_reschedule_booking(
           :bookingId::bigint,
           :newResourceId::bigint,
           :newStartsAt::timestamptz,
           (SELECT actor_type_id FROM actor_type WHERE code = :actorType::text),
           :actorSubject::text,
           :now::timestamptz,
           :eventId::uuid,
           (SELECT hold_reason_id FROM hold_reason WHERE code = :gate::text)
       ) AS sequence_no
