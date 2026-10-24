package dev.lucario.client;

import dev.lucario.Variants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Shared "apply morph" logic for the client command bridge (patch 16) and the
 * GUI screens (patch 10): validation, client state, config persistence and
 * mirror publishing — exactly one place to change.
 */
@Environment(EnvType.CLIENT)
public final class MorphApplier {
   private MorphApplier() {
   }

   /** Applies the morph without any chat output. Returns false for unknown variants. */
   public static boolean apply(String variant) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null) {
         return false;
      }

      String v = Variants.normalize(variant);
      if (v != null && !Variants.isValid(v)) {
         return false;
      }

      ClientMorphs.set(player.getUUID(), v);
      SyncConfig cfg = SyncConfig.get();
      cfg.lastVariant = v == null ? "" : v;
      cfg.save();
      SyncClient.publish(v);
      return true;
   }

   /** Applies the morph and prints the same feedback the client command would print. */
   public static boolean applyWithFeedback(String variant) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null) {
         return false;
      }

      String v = Variants.normalize(variant);
      if (v != null && !Variants.isValid(v)) {
         player.sendSystemMessage(Component.translatable("lucariomod.cmd.unknown"));
         return false;
      }

      if (apply(variant)) {
         if (v == null) {
            player.sendSystemMessage(Component.translatable("lucariomod.cmd.normal"));
         } else {
            // 1.6.2: "You are now Lucario pmd" — proper name, no technical key
            player.sendSystemMessage(Variants.morphedMessage(v));
         }

         return true;
      } else {
         return false;
      }
   }
}
