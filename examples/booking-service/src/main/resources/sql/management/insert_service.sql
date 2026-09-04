-- UC1.  Duration, capacity and buffers live on the service (LD7, LD17) and are
-- snapshotted onto each session at booking time (R25).
INSERT INTO service (public_ref, provider_id, name, duration_minutes, capacity,
                     buffer_before_minutes, buffer_after_minutes, slot_step_minutes)
VALUES (:publicRef::uuid, :providerId::bigint, :name::text, :durationMinutes::int,
        COALESCE(:capacity::int, 1),
        COALESCE(:bufferBeforeMinutes::int, 0),
        COALESCE(:bufferAfterMinutes::int, 0),
        COALESCE(:slotStepMinutes::int, 15))
RETURNING service_id
