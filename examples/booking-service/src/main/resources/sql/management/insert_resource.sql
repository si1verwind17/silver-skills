-- UC1.  LD6: a provider may have many independently bookable resources.
INSERT INTO resource (public_ref, provider_id, name, idp_subject)
VALUES (:publicRef::uuid, :providerId::bigint, :name::text, :idpSubject::text)
RETURNING resource_id
