package cn.solarmoon.spark_core.network;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public record TerrainUpdateMessage(Set<SectionPos> sections) {
    private static final int MAX_SECTIONS = 4096;

    public TerrainUpdateMessage {
        sections = Set.copyOf(sections);
        if (sections.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("Too many terrain sections: " + sections.size());
        }
    }

    static void encode(TerrainUpdateMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.sections.size());
        for (SectionPos section : message.sections) buffer.writeLong(section.asLong());
    }

    static TerrainUpdateMessage decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SECTIONS) throw new IllegalArgumentException("Invalid terrain section count: " + count);
        Set<SectionPos> sections = new HashSet<>(count);
        for (int index = 0; index < count; index++) sections.add(SectionPos.of(buffer.readLong()));
        return new TerrainUpdateMessage(sections);
    }

    static void handle(TerrainUpdateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientTerrainPacketHandler.handle(message)
        ));
        context.setPacketHandled(true);
    }
}
