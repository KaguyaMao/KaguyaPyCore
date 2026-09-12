package cn.solarmoon.spark_core.network;

import cn.solarmoon.spark_core.SparkCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class SparkNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SparkCore.MOD_ID, "physics"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );
    private static boolean registered;

    private SparkNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(
                0,
                TerrainUpdateMessage.class,
                TerrainUpdateMessage::encode,
                TerrainUpdateMessage::decode,
                TerrainUpdateMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        registered = true;
    }
}
