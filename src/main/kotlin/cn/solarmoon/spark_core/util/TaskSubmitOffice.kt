package cn.solarmoon.spark_core.util

enum class PPhase {
    /** Once per submitted Minecraft physics frame, before all fixed substeps. */
    FRAME_PRE,
    PRE,
    POST,
    ALL,
}

interface TaskSubmitOffice {
    fun submitImmediateTask(phase: PPhase = PPhase.ALL, task: () -> Unit)
    fun submitDeduplicatedTask(key: String, phase: PPhase = PPhase.ALL, task: () -> Unit)
}
