-- Resolves a public service reference to its internal id and the provider policy
-- the gate decision needs (R33).
SELECT s.service_id,
       s.provider_id,
       cm.code AS confirmation_mode
  FROM service s
  JOIN provider p           ON p.provider_id = s.provider_id
  JOIN confirmation_mode cm ON cm.confirmation_mode_id = p.confirmation_mode_id
 WHERE s.public_ref = :serviceRef::uuid
