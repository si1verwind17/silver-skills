-- Resolves a public resource reference.  Eligibility for a given service is the
-- database function's call (BK008), not this lookup's.
SELECT resource_id FROM resource WHERE public_ref = :resourceRef::uuid
