-- Resolves a public customer reference, used when a provider books on a
-- customer's behalf (UC5).
SELECT customer_id FROM customer WHERE public_ref = :customerRef::uuid
