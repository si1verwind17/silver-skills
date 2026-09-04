package dev.booking.core.booking

/**
 * Decides which external gate, if any, a new booking must pass (R33).
 *
 * A standalone pure function rather than a private method on the service, so it
 * is testable directly instead of only through stubs.  R33 requires this be an
 * evaluation of provider policy rather than a static flag read, which is what
 * keeps a future short-notice or payment gate additive: a new branch here and a
 * row in `hold_reason`, with no change to the lifecycle.
 */
object GatePolicy {

    fun selectGate(confirmationMode: ConfirmationMode): HoldReason? =
        when (confirmationMode) {
            ConfirmationMode.INSTANT -> null
            ConfirmationMode.APPROVAL -> HoldReason.AWAITING_PROVIDER_APPROVAL
        }

    /**
     * The gate a *reschedule* lands on (R11).
     *
     * A customer moving a booking at an approval-mode provider sends it back for
     * re-approval; a provider moving it has already decided, so it stays
     * confirmed.  The asymmetry is the rule, not an oversight.
     */
    fun selectRescheduleGate(
        actorType: ActorType,
        confirmationMode: ConfirmationMode,
    ): HoldReason? = when (actorType) {
        ActorType.CUSTOMER -> selectGate(confirmationMode)
        ActorType.PROVIDER -> null
        ActorType.SYSTEM -> null
    }
}
