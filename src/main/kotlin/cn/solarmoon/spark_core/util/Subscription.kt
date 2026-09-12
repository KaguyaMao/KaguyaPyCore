package cn.solarmoon.spark_core.util

class Subscription(private val unsubscribe: () -> Unit) {
    fun dispose() = unsubscribe()
}
