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
 * 26.3 uses SDL scancodes instead of GLFW keycodes (W=26, T=23, so J=13, L=15)
 * and a {@code KeyMapping.Category} record instead of a String category.
 * Fabric API 0.16x: {@code KeyMappingHelper.registerKeyMapping} (module
 * {@code fabric-key-mapping-api-v1}).
 * <p>
 * 1.6.2: both keys live in their OWN "Lucario Morph" section of the vanilla
 * Key Binds screen (appended after the vanilla categories via
 * {@code KeyMapping.Category.register}, label key
 * {@code key.category.lucariomorph.lucario_morph}) instead of Misc.
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
      // SDL_SCANCODE_L = 15, SDL_SCANCODE_J = 13 (GLFW is gone in 26.3, SDL-era scancodes)
      PICKER = KeyMappingHelper.registerKeyMapping(new KeyMapping(
         "key.lucariomod.picker",
         15,
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
         DanceState.toggle();
      }
   }
}
