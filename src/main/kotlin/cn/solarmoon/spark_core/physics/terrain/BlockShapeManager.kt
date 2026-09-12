package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CollisionShape
import com.jme3.bullet.collision.shapes.CompoundCollisionShape
import com.jme3.math.Vector3f
import net.minecraft.core.BlockPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

class BlockShapeManager(private val physicsLevel: PhysicsLevel) {
    private val cache = ConcurrentHashMap<BlockState, CollisionShape>()

    fun getCollisionShape(state: BlockState): CollisionShape = cache.computeIfAbsent(state) { createShape(it) }

    private fun createShape(state: BlockState): CollisionShape {
        physicsLevel.assertPhysicsThread()
        val boxes = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()
        if (boxes.isEmpty()) return BoxCollisionShape(.001f)
        if (boxes.size == 1) {
            val box = boxes[0]
            if (box.minX == 0.0 && box.minY == 0.0 && box.minZ == 0.0 && box.maxX == 1.0 && box.maxY == 1.0 && box.maxZ == 1.0) {
                return BoxCollisionShape(.5f)
            }
        }
        return CompoundCollisionShape().also { compound ->
            boxes.forEach { box ->
                val half = Vector3f(((box.maxX - box.minX) / 2).toFloat(), ((box.maxY - box.minY) / 2).toFloat(), ((box.maxZ - box.minZ) / 2).toFloat())
                val center = Vector3f(((box.minX + box.maxX) / 2 - .5).toFloat(), ((box.minY + box.maxY) / 2 - .5).toFloat(), ((box.minZ + box.maxZ) / 2 - .5).toFloat())
                compound.addChildShape(BoxCollisionShape(half), center)
            }
        }
    }

    fun clear() = cache.clear()
}
