-- Ending a rule is setting effective_until, never deleting it (R20): the rule is
-- retained for audit and simply stops producing availability.
UPDATE availability_rule ar
   SET effective_until = :effectiveUntil::date,
       updated_at      = :now::timestamptz
  FROM resource r
 WHERE ar.resource_id = r.resource_id
   AND ar.public_ref = :ruleRef::uuid
   AND r.provider_id = :providerId::bigint
