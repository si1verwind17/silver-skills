-- R29: consuming a duplicate must be a no-op.  The unique message_id arbitrates,
-- so a redelivery inserts nothing and the caller skips the effect entirely.
INSERT INTO inbox_message (message_id, message_type, payload)
VALUES (:messageId::text, :messageType::text, :payload::jsonb)
ON CONFLICT (message_id) DO NOTHING
RETURNING inbox_message_id
