package cn.solarmoon.spark_core.physics.level

/**
 * Per-frame timing and workload counters for diagnosing the physics worker.
 *
 * Callback timings are included in [bulletUpdateNanos]; they are broken out as
 * nested values and must not be added to that total. Request timings run on the
 * caller thread and are not included in [PhysicsFrameSnapshot.stepNanos].
 */
data class PhysicsStepMetrics(
    val requestTerrainNanos: Long = 0L,
    val requestTerrainCalls: Int = 0,
    val requestPoseCaptureNanos: Long = 0L,
    val droppedFrameRequests: Int = 0,
    val requestQueueWaitNanos: Long = 0L,
    val taskNanos: Long = 0L,
    val attachedPoseNanos: Long = 0L,
    val terrainApplyNanos: Long = 0L,
    val bulletUpdateNanos: Long = 0L,
    val preCallbackNanos: Long = 0L,
    val postCallbackNanos: Long = 0L,
    val bodyCaptureNanos: Long = 0L,
    val substepCount: Int = 0,
    val bodyCount: Int = 0,
    val dynamicBodyCount: Int = 0,
    val staticBodyCount: Int = 0,
    val kinematicBodyCount: Int = 0,
    val terrainBodyCount: Int = 0,
    val activationBoxCount: Int = 0,
    val preCallbackBodyVisits: Long = 0L,
    val postCallbackBodyVisits: Long = 0L,
) {
    companion object {
        val EMPTY = PhysicsStepMetrics()
    }

    override fun toString(): String =
        "requestNs=${requestTerrainNanos + requestPoseCaptureNanos}" +
            "(terrain=$requestTerrainNanos/$requestTerrainCalls calls,poses=$requestPoseCaptureNanos)," +
            "droppedRequests=$droppedFrameRequests," +
            "queueWaitNs=$requestQueueWaitNanos," +
            "workerNs=${taskNanos + attachedPoseNanos + terrainApplyNanos + bulletUpdateNanos + bodyCaptureNanos}" +
            "(tasks=$taskNanos,attached=$attachedPoseNanos,terrain=$terrainApplyNanos," +
            "bullet=$bulletUpdateNanos,preCallbacks=$preCallbackNanos,postCallbacks=$postCallbackNanos," +
            "capture=$bodyCaptureNanos),substeps=$substepCount,bodies=$bodyCount," +
            "dynamic=$dynamicBodyCount,static=$staticBodyCount,kinematic=$kinematicBodyCount,terrain=$terrainBodyCount," +
            "activationBoxes=$activationBoxCount,callbackVisits=$preCallbackBodyVisits/$postCallbackBodyVisits"
}
