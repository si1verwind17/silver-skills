-- The relay's re-scan IS the retry: nothing is dropped, the row simply becomes
-- due again later.  attempt_count drives the NF11 alert on repeated failure.
UPDATE outbox_event
   SET attempt_count   = attempt_count + 1,
       next_attempt_at = :nextAttemptAt::timestamptz,
       last_error      = :lastError::text,
       updated_at      = :now::timestamptz
 WHERE outbox_event_id = :outboxEventId::bigint
