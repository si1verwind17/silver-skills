-- UC2.  The resource must belong to the provider in the path, which is checked
-- here rather than trusted from the request.
INSERT INTO availability_rule (public_ref, resource_id, day_of_week, start_time,
                               end_time, effective_from, effective_until)
SELECT :publicRef::uuid, r.resource_id, :dayOfWeek::smallint, :startTime::time,
       :endTime::time, :effectiveFrom::date, :effectiveUntil::date
  FROM resource r
 WHERE r.public_ref = :resourceRef::uuid
   AND r.provider_id = :providerId::bigint
RETURNING availability_rule_id
