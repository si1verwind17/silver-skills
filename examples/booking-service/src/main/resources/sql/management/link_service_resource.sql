-- UC1 eligibility.  Both composite foreign keys carry provider_id, so the
-- database itself refuses to link across providers.
INSERT INTO service_resource (service_id, resource_id, provider_id)
SELECT s.service_id, r.resource_id, :providerId::bigint
  FROM service s, resource r
 WHERE s.public_ref = :serviceRef::uuid
   AND r.public_ref = :resourceRef::uuid
   AND s.provider_id = :providerId::bigint
   AND r.provider_id = :providerId::bigint
ON CONFLICT DO NOTHING
