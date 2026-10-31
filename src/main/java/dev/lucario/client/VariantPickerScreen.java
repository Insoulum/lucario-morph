package dev.lucario.client;

import dev.lucario.Variants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Patch 10: grid-based variant picker. Variant short names plus the shiny
 * toggle, same semantics as "/lucario captain shiny".
 * <p>
 * 1.6.0 changes:
 * - the BASE form ("lucario" / "riolu") is now part of the grid, so you can
 *   switch back to the plain morph from the GUI (matches the new "/lucario"
 *   semantics of switching to the base form instead of resetting the skin);
 * - the grid adapts to the GUI width (column count and button width are
 *   computed from the screen size, so nothing is clipped on narrow layouts);
 * - when opened from the config screen (Mod Menu), "Done"/"Esc" returns there
 *   instead of dropping you back into the game.
 * <p>
 * 1.6.2 changes:
 * - the base form is no longer listed TWICE (the short-name pass re-added
 *   "lucario"/"riolu" after the explicit base entry);
 * - the shiny toggle reads "Shiny: Yes/No" instead of a bare ON/OFF;
 * - the family toggle reads "Type: Lucario/Riolu";
 * - both toggles are remembered between openings (static state), including
 *   after picking a form.
 * <p>
 * Built with the 26.3 layout API (StringWidget + LinearLayout +
 * visitWidgets/addRenderableWidget); no custom render() needed — the default
 * Screen.extractRenderState renders all registered widgets.
 * <p>
 * 1.8.1 (prototype):
 * - a third toggle, "Cape: Yes/No" (next to Shiny): with a real elytra worn the wings
 *   always render (functional, vanilla rules); WITHOUT one, the LOCAL player's morph
 *   wears the cape -- the player's own Minecraft cape drawn as a hanging cloth on
 *   the back (Lucario, Riolu and Mega alike). Takes effect immediately; remembered
 *   for the session like the other toggles;
 * - pressing "Done" now APPLIES the Shiny toggle to the currently worn morph:
 *   flip Shiny and press Done — the morph re-applies as shiny/non-shiny without
 *   re-picking the variant. With no morph worn, Done just closes.
 */
@Environment(EnvType.CLIENT)
public class VariantPickerScreen extends Screen {
   protected final LinearLayout layout = LinearLayout.vertical().spacing(6);
   private final Screen parent;

   // 1.6.2: remembered between openings (and after picking a form) for the session
   private static boolean rioluFamily = false;
   private static boolean shiny = false;

   /**
    * 1.8.1 prototype: whether the cape is drawn on the LOCAL player's morph when NO
    * elytra is worn (Lucario, Riolu and Mega alike). With an elytra actually worn
    * the wings always render, regardless of this flag. Toggled by the
    * picker's Cape button next to Shiny; read by LucarioRenderer every frame, so the
    * change takes effect immediately. Session-scoped, like the toggles above.
    * 1.8.1-pre8: the toggle is also SYNCED - it rides the morph key as the "_nocape"
    * suffix (the same way "_shiny" does), so other players render the cape exactly as
    * its owner set it; flipping the toggle re-publishes the key.
    */
   public static boolean capeVisible = true;

   /** 1.8.1: the Shiny value when the screen was opened (for the Done re-apply). */
   private final boolean shinyOnOpen;

   public VariantPickerScreen() {
      this(null);
   }

   public VariantPickerScreen(Screen parent) {
      super(Component.translatable("gui.lucariomod.picker.title"));
      this.parent = parent;
      // Captured in the CONSTRUCTOR on purpose: init() also runs on every
      // rebuildWidgets() (toggle press), and the baseline must not reset then.
      this.shinyOnOpen = shiny;
   }

   @Override
   protected void init() {
      this.layout.removeChildren();
      this.layout.defaultCellSetting().alignHorizontallyCenter();
      this.layout.addChild(new StringWidget(this.title, this.font));

      LinearLayout topRow = this.layout.addChild(LinearLayout.horizontal().spacing(4));
      // 1.8.1: the row now holds THREE toggles (Type / Shiny / Cape) — the width adapts
      // to the GUI so nothing clips on narrow layouts
      int toggleW = Math.min(150, Math.max(60, (this.width - 16 - 2 * 4) / 3));
      CycleButton<Boolean> family = topRow.addChild(
         CycleButton.<Boolean>builder(
               v -> v ? Component.translatable("gui.lucariomod.picker.riolu") : Component.translatable("gui.lucariomod.picker.lucario"),
               this.rioluFamily
            )
            .withValues(Boolean.FALSE, Boolean.TRUE)
            .create(Component.translatable("gui.lucariomod.picker.type"), (btn, val) -> {
               this.rioluFamily = val;
               this.rebuildWidgets();
            })
      );
      family.setWidth(toggleW);
      // 1.6.2: "Shiny: Yes / Shiny: No" instead of a bare ON/OFF value
      CycleButton<Boolean> shinyBtn = topRow.addChild(
         CycleButton.<Boolean>builder(
               v -> v ? Component.translatable("gui.lucariomod.picker.yes") : Component.translatable("gui.lucariomod.picker.no"),
               this.shiny
            )
            .withValues(Boolean.FALSE, Boolean.TRUE)
            .create(Component.translatable("gui.lucariomod.picker.shiny"), (btn, val) -> {
               this.shiny = val;
               this.rebuildWidgets();
            })
      );
      shinyBtn.setWidth(toggleW);
      // 1.8.1 prototype: "Cape: Yes / No" — shows the player's own cape as a cloth on the
      // local player's morph (Lucario / Riolu / Mega) when NO elytra is worn (with a real
      // elytra the wings always render). The renderer reads the flag every frame, so the
      // change is instant; no rebuildWidgets() needed.
      CycleButton<Boolean> capeBtn = topRow.addChild(
         CycleButton.<Boolean>builder(
               v -> v ? Component.translatable("gui.lucariomod.picker.yes") : Component.translatable("gui.lucariomod.picker.no"),
               capeVisible
            )
            .withValues(Boolean.FALSE, Boolean.TRUE)
            .create(Component.translatable("gui.lucariomod.picker.cape"), (btn, val) -> {
               capeVisible = val;
               // 1.8.1-pre8: the toggle is synced - re-publish the morph key with the new flag
               // so other players see the change immediately (a no-op with no morph / no sync).
               MorphApplier.republishCape();
            })
      );
      capeBtn.setWidth(toggleW);

      // 1.6.0: adaptive grid — narrower GUIs get fewer, smaller columns
      int cols = this.width >= 480 ? 5 : this.width >= 340 ? 4 : 3;
      int btnW = Math.min(120, (this.width - 16 - (cols - 1) * 4) / cols);

      String base = this.rioluFamily ? Variants.DEFAULT_RIOLU : Variants.DEFAULT_LUCARIO;
      List<String> shorts = new ArrayList<>();
      shorts.add(base);
      for (String v : this.rioluFamily ? Variants.RIOLU : Variants.LUCARIO) {
         // 1.6.2: skip the base form — it is already in the grid (it used to be
         // added twice: shortName(base, base) falls back to the full name)
         if (v.equals(base)) {
            continue;
         }

         String s = Variants.shortName(v, base);
         if (!s.isEmpty()) {
            shorts.add(s);
         }
      }

      for (int i = 0; i < shorts.size(); i += cols) {
         LinearLayout row = this.layout.addChild(LinearLayout.horizontal().spacing(4));
         for (int j = i; j < Math.min(shorts.size(), i + cols); j++) {
            final String name = shorts.get(j);
            // 1.6.1: buttons show the localized display name (lucariomod.name.*) instead of
            // the raw short name; the full variant key is shortName -> base + shortName
            String full = name.equals(base) ? base : base + name;
            row.addChild(Button.builder(Component.translatable(Variants.nameKey(full)), b -> this.pick(name, base)).size(btnW, 20).build());
         }
      }

      LinearLayout bottom = this.layout.addChild(LinearLayout.horizontal().spacing(4));
      bottom.defaultCellSetting().paddingTop(8);
      bottom.addChild(Button.builder(Component.translatable("gui.lucariomod.picker.off"), b -> this.pickOff()).size(btnW, 20).build());
      bottom.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onDone()).size(btnW, 20).build());

      this.layout.visitWidgets(this::addRenderableWidget);
      this.repositionElements();
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
      FrameLayout.centerInRectangle(this.layout, this.getRectangle());
   }

   private void pick(String shortName, String base) {
      String resolved = Variants.resolve(this.shiny ? shortName + " shiny" : shortName, base);
      MorphApplier.applyWithFeedback(resolved);
      this.onClose();
   }

   private void pickOff() {
      MorphApplier.applyWithFeedback(null);
      this.onClose();
   }

   /**
    * 1.8.1 prototype: Done re-applies the currently worn morph when the Shiny toggle was
    * flipped while the screen was open — one press switches the morph shiny/non-shiny
    * (in BOTH directions) without re-picking the variant. With no morph worn (or the
    * toggle untouched) Done simply closes, as before.
    */
   private void onDone() {
      if (this.shinyOnOpen != shiny) {
         String current = SyncConfig.get().lastVariant;
         if (!current.isEmpty()) {
            String base = Variants.baseOf(Variants.normalize(current));
            String updated = shiny ? base + Variants.SHINY_SUFFIX : base;
            if (!updated.equals(current)) {
               MorphApplier.applyWithFeedback(updated);
            }
         }
      }
      this.onClose();
   }

   @Override
   public void onClose() {
      if (this.parent != null) {
         this.minecraft.setScreenAndShow(this.parent);
      } else {
         super.onClose();
      }
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
