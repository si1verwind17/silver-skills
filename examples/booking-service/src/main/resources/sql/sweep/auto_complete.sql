-- UC12.  Only CONFIRMED bookings are candidates, so an explicit provider mark is
-- never overridden.
SELECT fn_auto_complete(:now::timestamptz, :limit::int) AS affected
