-- UC4.  Every row returned is bookable at the instant of the call: R1, R3 and R31
-- are applied inside the function, and the application filters nothing.
SELECT r.public_ref AS resource_ref,
       a.starts_at,
       a.ends_at,
       a.remaining_capacity
  FROM service s
  JOIN provider p ON p.provider_id = s.provider_id
  CROSS JOIN LATERAL fn_search_availability(
      s.service_id,
      :from::timestamptz,
      :to::timestamptz,
      (SELECT resource_id FROM resource WHERE public_ref = :resourceRef::uuid)
  ) a
  JOIN resource r ON r.resource_id = a.resource_id
 WHERE s.public_ref = :serviceRef::uuid
   -- The provider in the path must actually own the service, so the path segment
   -- is meaningful rather than decorative.
   AND p.public_ref = :providerRef::uuid
 ORDER BY a.starts_at, r.public_ref
