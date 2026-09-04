-- The internal endpoint that exists because events carry identifiers only
-- (PD17, architecture-design.md AQ3).  This is the ONLY place customer contact
-- details leave the service, and it is never routed through public ingress.
SELECT c.public_ref AS customer_ref,
       c.display_name,
       c.email,
       c.phone,
       c.erased_at,
       b.cancellation_reason
  FROM booking b
  JOIN customer c ON c.customer_id = b.customer_id
 WHERE b.public_ref = :bookingRef::uuid
