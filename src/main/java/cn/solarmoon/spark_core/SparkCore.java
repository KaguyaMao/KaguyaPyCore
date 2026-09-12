package cn.solarmoon.spark_core;

import cn.solarmoon.spark_core.network.SparkNetwork;
import cn.solarmoon.spark_core.physics.NativePhysicsLoader;
import cn.solarmoon.spark_core.physics.level.PhysicsLevelEvents;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SparkCore.MOD_ID)
public final class SparkCore {
    public static final String MOD_ID = "spark_core";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SparkCore() {
        NativePhysicsLoader.load();
        SparkNetwork.register();
        MinecraftForge.EVENT_BUS.register(PhysicsLevelEvents.INSTANCE);
    }
}
