-- Ownership and provider policy for one booking: what R18 needs to decide whether
-- the caller may act, and what R11 needs to decide a reschedule's gate.
SELECT b.booking_id,
       b.public_ref,
       b.customer_id,
       b.provider_id,
       cm.code AS confirmation_mode
  FROM booking b
  JOIN provider p           ON p.provider_id = b.provider_id
  JOIN confirmation_mode cm ON cm.confirmation_mode_id = p.confirmation_mode_id
 WHERE b.public_ref = :bookingRef::uuid
