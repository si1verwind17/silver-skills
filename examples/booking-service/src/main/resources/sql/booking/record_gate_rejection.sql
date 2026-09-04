-- R28.  Publishes the booking's ACTUAL state so the sender of a late resolution
-- can compensate.  Deliberately changes nothing about the booking.
SELECT fn_record_gate_rejection(:bookingId::bigint, :eventId::uuid)
