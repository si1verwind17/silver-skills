-- Marking is guarded on still being undispatched, so a redelivery caused by a
-- crash between publish and mark cannot double-count attempts.
UPDATE outbox_event
   SET dispatched_at = :now::timestamptz,
       updated_at    = :now::timestamptz
 WHERE outbox_event_id = :outboxEventId::bigint
   AND dispatched_at IS NULL
