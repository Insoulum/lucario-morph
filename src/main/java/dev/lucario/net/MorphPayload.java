package dev.lucario.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C: "игрок uuid теперь выглядит как variant" (пустая строка = обычный игрок). */
public record MorphPayload(String uuid, String variant) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MorphPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("lucariomod", "morph"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MorphPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MorphPayload::uuid,
            ByteBufCodecs.STRING_UTF8, MorphPayload::variant,
            MorphPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
