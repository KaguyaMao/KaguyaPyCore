package cn.solarmoon.spark_core.physics.track

import java.util.concurrent.CompletableFuture

/**
 * Result of submitting a bounded link input.
 *
 * [clampedMagnitude] is the magnitude **accepted for submission** after
 * clamping, not proof that the physics thread already applied it. When the
 * caller needs that proof, it uses [Future.completion].
 */
data class TrackRunInputReceipt(
    val accepted: Boolean,
    val clampedMagnitude: Float,
    val reason: String?,
) {
    /**
     * Bundles the synchronous receipt with the future that completes when the
     * physics thread has applied the input. The future fails when the run or
     * level closed before the task ran.
     */
    class Future(
        val receipt: TrackRunInputReceipt,
        val completion: CompletableFuture<Void>,
    ) {
        val accepted: Boolean get() = receipt.accepted
        val acceptedMagnitude: Float get() = receipt.clampedMagnitude
        val reason: String? get() = receipt.reason
    }

    companion object {
        internal fun rejected(reason: String): TrackRunInputReceipt =
            TrackRunInputReceipt(accepted = false, clampedMagnitude = 0f, reason = reason)
    }
}
