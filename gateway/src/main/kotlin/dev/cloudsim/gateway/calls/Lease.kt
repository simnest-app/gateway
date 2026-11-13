package dev.cloudsim.gateway.calls

/**
 * How long remote access may go on without the gateway confirming it holds the latest permissions
 * (docs/architecture/REVOKE_LEASE_5.md): at most 5 minutes from the last confirmation, owner and family alike, even
 * while a direct media path still works. [clock] is monotonic (elapsedRealtime), so changing the system time does not
 * stretch it; a new process starts unconfirmed, so a restart does not either.
 */
class Lease(private val clock: () -> Long) {
    @Volatile private var confirmedAt: Long? = null

    fun confirm() { confirmedAt = clock() }

    fun valid(): Boolean = confirmedAt?.let { clock() - it <= WINDOW_MS } ?: false

    /** Milliseconds since the last confirmation, or null if never confirmed in this process. */
    fun age(): Long? = confirmedAt?.let { clock() - it }

    companion object {
        const val WINDOW_MS = 5 * 60_000L
        /** How often the gateway asks the service for the current revision. */
        const val RENEW_MS = 60_000L

        /** The running gateway's lease, for the status screen; null while the service is off. */
        @Volatile var current: Lease? = null
    }
}
