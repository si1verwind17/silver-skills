-- Lookup only.  Authorizing a request must never create a customer profile as a
-- side effect, which is why this is separate from the upsert.
SELECT customer_id FROM customer WHERE idp_subject = :idpSubject::text
