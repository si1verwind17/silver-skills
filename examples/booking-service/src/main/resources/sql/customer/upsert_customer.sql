-- Maps a verified IdP subject onto this service's own customer profile,
-- provisioning it on first use (LD3 leaves registration to the IdP).  The
-- conflict branch keeps the statement idempotent and still returns the id, so
-- this is one round trip whether or not the profile already existed.
INSERT INTO customer (public_ref, idp_subject, display_name, email, phone)
VALUES (:publicRef::uuid, :idpSubject::text, :displayName::text, :email::text, :phone::text)
ON CONFLICT (idp_subject) DO UPDATE
   SET updated_at = :now::timestamptz
RETURNING customer_id
