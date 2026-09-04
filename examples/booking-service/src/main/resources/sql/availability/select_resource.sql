-- PD14: when the customer expresses no preference, choose deterministically —
-- fewest capacity-holding sessions on that resource for the provider's local day,
-- then the lowest resource id.  Deterministic because an arbitrary choice would
-- make the same request reproducible only by accident.
--
-- Candidates come from fn_search_availability rather than from the eligibility
-- table, so a resource that is merely eligible but not actually free is never
-- offered.
WITH svc AS (
    SELECT s.service_id, s.duration_minutes, p.timezone
      FROM service s
      JOIN provider p ON p.provider_id = s.provider_id
     WHERE s.service_id = :serviceId::bigint
),
candidates AS (
    SELECT a.resource_id
      FROM svc
      CROSS JOIN LATERAL fn_search_availability(
          svc.service_id,
          :startsAt::timestamptz,
          :startsAt::timestamptz + make_interval(mins => svc.duration_minutes)
      ) a
     WHERE a.starts_at = :startsAt::timestamptz
)
SELECT c.resource_id
  FROM candidates c
  CROSS JOIN svc
  LEFT JOIN session s
         ON s.resource_id = c.resource_id
        AND s.booked_count > 0
        AND (s.starts_at AT TIME ZONE svc.timezone)::date
            = (:startsAt::timestamptz AT TIME ZONE svc.timezone)::date
 GROUP BY c.resource_id
 ORDER BY count(s.session_id), c.resource_id
 LIMIT 1
