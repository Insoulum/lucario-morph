package dev.lucario.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Patch 17: one-time consent screen for the external sync service. Shown on
 * the first join to an online server; existing users (legacy configs) are
 * migrated silently in SyncConfig and never see it.
 */
@Environment(EnvType.CLIENT)
public class LucarioConsentScreen extends Screen {
   protected final LinearLayout layout = LinearLayout.vertical().spacing(8);

   public LucarioConsentScreen() {
      super(Component.translatable("gui.lucariomod.consent.title"));
   }

   @Override
   protected void init() {
      this.layout.removeChildren();
      this.layout.defaultCellSetting().alignHorizontallyCenter();
      this.layout.addChild(new StringWidget(this.title, this.font));
      this.layout.addChild(
         new MultiLineTextWidget(Component.translatable("gui.lucariomod.consent.body"), this.font)
            .setMaxWidth(this.width - 80)
            .setMaxRows(15)
            .setCentered(true)
      );

      // 1.6.0: stacked full-width buttons — long translated labels no longer overflow
      int w = Math.min(280, this.width - 40);
      this.layout.addChild(
         Button.builder(Component.translatable("gui.lucariomod.consent.enable"), b -> {
            SyncConfig cfg = SyncConfig.get();
            cfg.syncOptIn = Boolean.TRUE;
            cfg.save();
            SyncClient.onJoin();
            this.onClose();
         }).size(w, 20).build()
      );
      this.layout.addChild(
         Button.builder(Component.translatable("gui.lucariomod.consent.decline"), b -> {
            SyncConfig cfg = SyncConfig.get();
            cfg.syncOptIn = Boolean.FALSE;
            cfg.syncEnabled = false;
            cfg.save();
            this.onClose();
         }).size(w, 20).build()
      );
      this.layout.addChild(
         Button.builder(Component.translatable("gui.lucariomod.consent.later"), b -> this.onClose()).size(w, 20).build()
      );

      this.layout.visitWidgets(this::addRenderableWidget);
      this.repositionElements();
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
      FrameLayout.centerInRectangle(this.layout, this.getRectangle());
   }
}
