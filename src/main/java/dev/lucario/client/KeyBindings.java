package dev.lucario.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Patch 10: key bindings for the morph picker and the dance emote.
 * <p>
 * 26.3 uses SDL scancodes instead of GLFW keycodes (W=26, T=23, so J=13, K=14)
 * and a {@code KeyMapping.Category} record instead of a String category.
 * Fabric API 0.16x: {@code KeyMappingHelper.registerKeyMapping} (module
 * {@code fabric-key-mapping-api-v1}).
 * <p>
 * 1.6.2: both keys live in their OWN "Lucario Morph" section of the vanilla
 * Key Binds screen (appended after the vanilla categories via
 * {@code KeyMapping.Category.register}, label key
 * {@code key.category.lucariomorph.lucario_morph}) instead of Misc.
 * <p>
 * 1.6.1: DANCE (J) no longer toggles the dance directly — it opens the
 * {@link AnimationPreviewScreen} emote menu instead (the key's display name
 * is "Animation menu (emotes)" since this build).
 */
@Environment(EnvType.CLIENT)
public final class KeyBindings {
   /** 1.6.2: separate "Lucario Morph" section in the controls screen. */
   public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
      Identifier.fromNamespaceAndPath("lucariomorph", "lucario_morph")
   );
   public static KeyMapping PICKER;
   public static KeyMapping DANCE;

   private KeyBindings() {
   }

   public static void register() {
      // 1.8.1: PICKER now defaults to K (SDL_SCANCODE_K = 14) instead of L — 26.3 uses
      // SDL scancodes (GLFW keycodes are gone): A=4 ... J=13, K=14, L=15 ... Z=29.
      // NOTE: players who already ran an older build keep their saved L binding
      // (options.txt wins over the default).
      PICKER = KeyMappingHelper.registerKeyMapping(new KeyMapping(
         "key.lucariomod.picker",
         14,
         CATEGORY
      ));
      DANCE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
         "key.lucariomod.dance",
         13,
         CATEGORY
      ));
   }

   public static void tick(Minecraft mc) {
      while (PICKER.consumeClick()) {
         if (mc.gui.screen() == null && mc.player != null) {
            mc.setScreenAndShow(new VariantPickerScreen());
         }
      }

      while (DANCE.consumeClick()) {
         // 1.6.1: J opens the emote menu (any animation of the current model) instead of
         // hard-coding the dance — the screen starts/stops the emote via DanceState.
         if (mc.gui.screen() == null && mc.player != null) {
            mc.setScreenAndShow(new AnimationPreviewScreen());
         }
      }
   }
}
