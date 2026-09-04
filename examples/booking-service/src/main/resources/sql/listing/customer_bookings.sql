-- UC10, the customer's own timeline.  Scoped by customer_id rather than by a
-- request parameter, so a caller cannot widen it (R18).
SELECT b.public_ref,
       bs.code   AS state,
       hr.code   AS hold_reason,
       b.starts_at,
       b.ends_at,
       p.public_ref  AS provider_ref,
       sv.public_ref AS service_ref,
       r.public_ref  AS resource_ref,
       p.timezone
  FROM booking b
  JOIN booking_state bs ON bs.booking_state_id = b.booking_state_id
  JOIN provider p       ON p.provider_id = b.provider_id
  JOIN session se       ON se.session_id = b.session_id
  JOIN service sv       ON sv.service_id = se.service_id
  JOIN resource r       ON r.resource_id = se.resource_id
  LEFT JOIN hold_reason hr ON hr.hold_reason_id = b.hold_reason_id
 WHERE b.customer_id = :customerId::bigint
 ORDER BY b.starts_at DESC, b.public_ref
 LIMIT :limit::int OFFSET :offset::int
