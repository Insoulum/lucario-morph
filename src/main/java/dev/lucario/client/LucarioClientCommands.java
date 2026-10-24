package dev.lucario.client;

import dev.lucario.MorphCommands;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public final class LucarioClientCommands {
   private LucarioClientCommands() {
   }

   /** Patch 16: client-side bridge — the command tree itself is shared with the server. */
   private static final MorphCommands.Bridge<FabricClientCommandSource> CLIENT_BRIDGE = new MorphCommands.Bridge<>() {
      @Override
      public UUID player(FabricClientCommandSource source) {
         return source.getPlayer() != null ? source.getPlayer().getUUID() : null;
      }

      @Override
      public String current(UUID id) {
         return ClientMorphs.get(id);
      }

      @Override
      public void apply(FabricClientCommandSource source, UUID id, String variant) {
         MorphApplier.apply(variant);
      }

      @Override
      public void feedback(FabricClientCommandSource source, Component message) {
         source.sendFeedback(message);
      }

      @Override
      public void failure(FabricClientCommandSource source, Component message) {
         source.sendError(message);
      }
   };

   public static void register() {
      ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
         dispatcher.register(MorphCommands.tree("lucario", "lucario", CLIENT_BRIDGE));
         dispatcher.register(MorphCommands.tree("riolu", "riolu", CLIENT_BRIDGE));
      });
   }
}
