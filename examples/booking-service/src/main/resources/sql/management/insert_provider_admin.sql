-- The subject who creates a provider becomes its first administrator.  Without
-- this the provider would exist with nobody able to manage it.
INSERT INTO provider_admin (provider_id, idp_subject)
VALUES (:providerId::bigint, :idpSubject::text)
ON CONFLICT (provider_id, idp_subject) DO NOTHING
