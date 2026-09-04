-- R20: editing availability never cancels a booking.  Instead the provider is
-- handed every non-terminal booking that now falls outside published
-- availability, so they can resolve each one deliberately.
SELECT b.public_ref,
       b.starts_at,
       b.ends_at,
       bs.code AS state,
       r.public_ref AS resource_ref
  FROM booking b
  JOIN booking_state bs ON bs.booking_state_id = b.booking_state_id
  JOIN session s        ON s.session_id = b.session_id
  JOIN resource r       ON r.resource_id = s.resource_id
 WHERE b.provider_id = :providerId::bigint
   AND NOT bs.is_terminal
   AND b.starts_at >= :now::timestamptz
   AND NOT (fn_available_windows(s.resource_id, b.starts_at, b.ends_at)
            @> tstzrange(b.starts_at, b.ends_at, '[)'))
 ORDER BY b.starts_at
