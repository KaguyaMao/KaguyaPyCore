package cn.solarmoon.spark_core.network;

import cn.solarmoon.spark_core.physics.SparkPhysics;
import net.minecraft.client.Minecraft;

final class ClientTerrainPacketHandler {
    private ClientTerrainPacketHandler() {
    }

    static void handle(TerrainUpdateMessage message) {
        if (Minecraft.getInstance().level != null) {
            SparkPhysics.get(Minecraft.getInstance().level).getTerrainManager().markDirty(message.sections());
        }
    }
}
