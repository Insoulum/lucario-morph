package dev.lucario.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record MorphPayload(String uuid, String variant) implements CustomPacketPayload {
   public static final Type<MorphPayload> ID = new Type(Identifier.fromNamespaceAndPath("lucariomod", "morph"));
   public static final StreamCodec<RegistryFriendlyByteBuf, MorphPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.STRING_UTF8, MorphPayload::uuid, ByteBufCodecs.STRING_UTF8, MorphPayload::variant, MorphPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return ID;
   }
}
