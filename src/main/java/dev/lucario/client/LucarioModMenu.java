package dev.lucario.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu integration: the "Config" button of the mod entry opens
 * {@link LucarioConfigScreen}. Mod Menu is a compile-only dependency
 * (see CHANGES.md, build notes) and is referenced only from this
 * entrypoint class, so the mod runs fine without it.
 * <p>
 * Mod Menu 21.x: {@code getModConfigScreenFactory()} returns
 * {@code ConfigScreenFactory<?>} (functional: {@code create(Screen parent)}).
 */
@Environment(EnvType.CLIENT)
public class LucarioModMenu implements ModMenuApi {
   @Override
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      return LucarioConfigScreen::new;
   }
}
