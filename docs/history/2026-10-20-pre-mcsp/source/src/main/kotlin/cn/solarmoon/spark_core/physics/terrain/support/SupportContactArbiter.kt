package cn.solarmoon.spark_core.physics.terrain.support

/**
 * Pure decision for one upward patch. This does not modify Bullet manifolds.
 * The live world does not call it: callback timing is not proven, and the
 * support mesh is not installed beside raw terrain tops.
 */
object SupportContactArbiter {
    data class UpwardContact(
        val patchX: Float,
        val patchZ: Float,
        /** Positive when the dynamic body is supported upward. */
        val normalY: Float,
        val authority: ContactAuthority,
        val impulse: Float,
    )

    data class Decision(
        val contact: UpwardContact,
        val kept: Boolean,
        val reason: String,
    )

    /**
     * [patchRadius] is a caller-supplied match radius, not a frozen acceptance limit.
     * An upward height-field contact rejects another upward contact on the same patch.
     * Side contacts and contacts without an upward height-field owner are unchanged.
     */
    fun arbitrate(contacts: List<UpwardContact>, patchRadius: Float, upwardNormal: Float = 0.5f): List<Decision> {
        require(patchRadius >= 0f)
        return contacts.map { contact ->
            val upward = contact.normalY >= upwardNormal && contact.impulse > 0f
            val duplicate = upward && contact.authority != ContactAuthority.HEIGHT_FIELD &&
                contacts.any { other ->
                    other !== contact &&
                        other.authority == ContactAuthority.HEIGHT_FIELD &&
                        other.normalY >= upwardNormal &&
                        other.impulse > 0f &&
                        kotlin.math.abs(other.patchX - contact.patchX) <= patchRadius &&
                        kotlin.math.abs(other.patchZ - contact.patchZ) <= patchRadius
                }
            if (duplicate) Decision(contact, kept = false, reason = "upward patch already owned by height field")
            else Decision(contact, kept = true, reason = "kept")
        }
    }
}
