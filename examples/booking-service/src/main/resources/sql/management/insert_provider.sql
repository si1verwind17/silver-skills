-- UC1.  The timezone is validated by the column's CHECK, so an unrecognised zone
-- is refused by the database rather than trusted here (R14).
INSERT INTO provider (public_ref, name, timezone, confirmation_mode_id,
                      min_lead_minutes, booking_horizon_days,
                      cancellation_window_minutes, approval_hold_ttl_minutes,
                      auto_complete_grace_days)
VALUES (:publicRef::uuid, :name::text, :timezone::text,
        (SELECT confirmation_mode_id FROM confirmation_mode WHERE code = :confirmationMode::text),
        COALESCE(:minLeadMinutes::int, 60),
        COALESCE(:bookingHorizonDays::int, 90),
        COALESCE(:cancellationWindowMinutes::int, 1440),
        COALESCE(:approvalHoldTtlMinutes::int, 1440),
        COALESCE(:autoCompleteGraceDays::int, 7))
RETURNING provider_id
