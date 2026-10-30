package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.terrain.support.StructuralBox
import cn.solarmoon.spark_core.physics.terrain.support.StructuralObstaclePlan
import cn.solarmoon.spark_core.physics.terrain.support.SupportFieldPolicy
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportFieldBuilder

/**
 * Builder-thread product. It contains no Bullet objects.
 *
 * [LIVE] matches the written install limits: a one-block shared edge may be
 * lowered, and a two-block face may not. It is not a vehicle-length wedge and
 * not a confirmed climb-feel budget.
 */
object TerrainSupportInstallPolicy {
    val NO_SMOOTHING = SupportFieldPolicy(
        maxDownwardCorrection = 0f,
        maxRiseForSupportTransition = null,
        smoothIterations = 0,
    )

    val LIVE = SupportFieldPolicy(
        maxDownwardCorrection = 1f,
        maxRiseForSupportTransition = 1f,
        smoothIterations = 1,
    )
}

data class TerrainSectionBuild(
    val plan: TerrainShapePlan,
    val supportField: TerrainSupportField,
    val structural: List<StructuralBox>,
) {
    val revision: Long get() = plan.revision

    companion object {
        fun from(snapshot: SectionSnapshot): TerrainSectionBuild {
            val plan = TerrainShapePlan.build(snapshot)
            val field = TerrainSupportFieldBuilder.build(snapshot, TerrainSupportInstallPolicy.LIVE)
            return TerrainSectionBuild(plan, field, StructuralObstaclePlan.build(snapshot, field))
        }
    }
}
