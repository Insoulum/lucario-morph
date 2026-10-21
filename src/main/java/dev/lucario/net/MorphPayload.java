package dev.lucario.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** S2C: "игрок uuid теперь выглядит как variant" (пустая строка = обычный игрок). */
public record MorphPayload(String uuid, String variant) implements CustomPayload {
    public static final CustomPayload.Id<MorphPayload> ID =
            new CustomPayload.Id<>(Identifier.of("lucariomod", "morph"));

    public static final PacketCodec<RegistryByteBuf, MorphPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, MorphPayload::uuid,
            PacketCodecs.STRING, MorphPayload::variant,
            MorphPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
