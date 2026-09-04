-- UC11.  Releases capacity held by lapsed gates, whatever the gate is.
SELECT fn_expire_holds(:now::timestamptz, :limit::int) AS affected
