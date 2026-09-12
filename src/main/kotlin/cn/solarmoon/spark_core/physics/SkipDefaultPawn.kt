package cn.solarmoon.spark_core.physics

/**
 * Entities that own their own Bullet bodies must implement this so Spark Core
 * does not attach the default kinematic pawn AABB on join.
 */
interface SkipDefaultPawn