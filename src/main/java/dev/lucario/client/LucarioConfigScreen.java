package dev.lucario.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Settings screen (also wired as the Mod Menu config screen via
 * {@link LucarioModMenu}). Allows disabling sync after opting in (patch 17)
 * and editing the mirror list.
 * <p>
 * 1.6.0 changes:
 * - every row uses a generous, screen-aware width — long translated labels no
 *   longer overflow the default 150px buttons;
 * - new "choose a form" row opens the {@link VariantPickerScreen} (Mod Menu →
 *   Config → morph picker);
 * - new key-bind rows show the current picker/dance keys and open the vanilla
 *   Key Binds screen, so both hotkeys are configurable right from Mod Menu.
 * <p>
 * Built with the 26.3 layout API — no custom render() needed.
 */
@Environment(EnvType.CLIENT)
public class LucarioConfigScreen extends Screen {
   private static final List<Integer> INTERVALS = List.of(2, 5, 10, 30, 60);

   protected final LinearLayout layout = LinearLayout.vertical().spacing(6);
   private final Screen parent;
   private EditBox mirrorsBox;

   public LucarioConfigScreen(Screen parent) {
      super(Component.translatable("gui.lucariomod.config.title"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      SyncConfig cfg = SyncConfig.get();
      // 1.6.0: wide rows — fit long labels, but never overflow the screen
      int w = Math.min(300, this.width - 40);
      this.layout.removeChildren();
      this.layout.defaultCellSetting().alignHorizontallyCenter();
      this.layout.addChild(new StringWidget(this.title, this.font));

      this.layout.addChild(
         Button.builder(Component.translatable("gui.lucariomod.config.picker"), b -> this.minecraft.setScreenAndShow(new VariantPickerScreen(this)))
            .size(w, 20)
            .build()
      );

      CycleButton<Boolean> sync = this.layout.addChild(
         CycleButton.onOffBuilder(cfg.syncEnabled)
            .create(Component.translatable("gui.lucariomod.config.sync"), (btn, val) -> cfg.syncEnabled = val)
      );
      sync.setWidth(w);
      CycleButton<Boolean> remember = this.layout.addChild(
         CycleButton.onOffBuilder(cfg.rememberVariant)
            .create(Component.translatable("gui.lucariomod.config.remember"), (btn, val) -> cfg.rememberVariant = val)
      );
      remember.setWidth(w);
      CycleButton<Boolean> autoAuth = this.layout.addChild(
         CycleButton.onOffBuilder(Boolean.TRUE.equals(cfg.autoAuth))
            .create(Component.translatable("gui.lucariomod.config.autoauth"), (btn, val) -> cfg.autoAuth = val)
      );
      autoAuth.setWidth(w);

      int initial = INTERVALS.contains(cfg.syncIntervalSeconds) ? cfg.syncIntervalSeconds : 5;
      CycleButton<Integer> interval = this.layout.addChild(
         CycleButton.<Integer>builder(v -> Component.translatable("gui.lucariomod.config.interval.value", v), initial)
            .withValues(INTERVALS)
            .create(Component.translatable("gui.lucariomod.config.interval"), (btn, val) -> cfg.syncIntervalSeconds = val)
      );
      interval.setWidth(w);

      // 1.6.0: key-bind section — current bindings + one click to the vanilla Key Binds screen
      String pickerKey = KeyBindings.PICKER != null ? KeyBindings.PICKER.getTranslatedKeyMessage().getString() : "?";
      String danceKey = KeyBindings.DANCE != null ? KeyBindings.DANCE.getTranslatedKeyMessage().getString() : "?";
      this.layout.addChild(
         new StringWidget(Component.translatable("gui.lucariomod.config.keys.value", pickerKey, danceKey), this.font)
      );
      this.layout.addChild(
         Button.builder(Component.translatable("gui.lucariomod.config.keys"), b -> this.minecraft.setScreenAndShow(new KeyBindsScreen(this, this.minecraft.options)))
            .size(w, 20)
            .build()
      );

      this.mirrorsBox = new EditBox(this.font, w, 20, Component.translatable("gui.lucariomod.config.mirrors"));
      this.mirrorsBox.setMaxLength(1024);
      this.mirrorsBox.setValue(String.join(", ", cfg.serviceUrls));
      this.layout.addChild(this.mirrorsBox);

      this.layout.addChild(
         Button.builder(CommonComponents.GUI_DONE, b -> {
            List<String> urls = new ArrayList<>();
            for (String u : this.mirrorsBox.getValue().split(",")) {
               String t = u.trim().replaceAll("/+$", "");
               if (!t.isEmpty()) {
                  urls.add(t);
               }
            }

            if (!urls.isEmpty()) {
               cfg.serviceUrls = urls;
            }

            cfg.save();
            this.onClose();
         }).size(w, 20).build()
      );

      this.layout.visitWidgets(this::addRenderableWidget);
      this.repositionElements();
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
      FrameLayout.centerInRectangle(this.layout, this.getRectangle());
   }

   @Override
   public void onClose() {
      this.minecraft.setScreenAndShow(this.parent);
   }
}
