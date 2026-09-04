-- UC3.  BLOCK removes availability and beats rules and OPEN alike.
INSERT INTO availability_exception (public_ref, resource_id, availability_exception_type_id,
                                    starts_at, ends_at, reason)
SELECT :publicRef::uuid, r.resource_id,
       (SELECT availability_exception_type_id FROM availability_exception_type
         WHERE code = :type::text),
       :startsAt::timestamptz, :endsAt::timestamptz, :reason::text
  FROM resource r
 WHERE r.public_ref = :resourceRef::uuid
   AND r.provider_id = :providerId::bigint
RETURNING availability_exception_id
