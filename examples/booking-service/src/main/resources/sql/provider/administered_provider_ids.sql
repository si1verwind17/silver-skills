-- R18: which providers this IdP subject administers.  Membership is resolved
-- here rather than trusted from a token claim, per PD1's interim assumption.
SELECT provider_id FROM provider_admin WHERE idp_subject = :idpSubject::text
