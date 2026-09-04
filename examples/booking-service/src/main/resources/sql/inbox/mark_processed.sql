UPDATE inbox_message
   SET processed_at = :now::timestamptz,
       outcome      = :outcome::text,
       updated_at   = :now::timestamptz
 WHERE inbox_message_id = :inboxMessageId::bigint
