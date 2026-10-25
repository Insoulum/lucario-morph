package dev.lucario.client;

import dev.lucario.Variants;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 1.6.1: the emote menu behind the {@code J} key (the old "press J to dance" is gone —
 * J now opens this screen instead, see {@link KeyBindings#tick}).
 * <p>
 * Lists every animation of the CURRENT morph's model as a button grid (the model comes from
 * {@link LucarioModels}, so the list follows the worn variant automatically). Animation names
 * that are engine-driven — locomotion cycles, idles, blinking, water tracks and other
 * WIP/twitch bones — are hidden via {@link #HIDDEN}: this screen is for manually playable
 * emotes only.
 * <p>
 * Picking a button stores the name in {@link PreviewState} and starts the emote clock
 * ({@link DanceState#start(Long.MAX_VALUE)}), so the pose crossfades in the renderer exactly
 * like the old dance did: it loops until movement/mount/water/damage cancels it
 * ({@link DanceState#tick}) and freezes on the last frame during the fade-out.
 * The "T-pose" button plays the raw bind pose (rigging check). An active emote is marked
 * with a {@code ▶} prefix; labels use the {@code gui.lucariomod.anim.*} translation keys with
 * a fallback to the raw animation name.
 * <p>
 * Built with the same 26.3 layout API as the other screens (StringWidget + LinearLayout +
 * visitWidgets/addRenderableWidget); no custom render() needed.
 */
@Environment(EnvType.CLIENT)
public class AnimationPreviewScreen extends Screen {
    /** Engine-driven / WIP animations that make no sense as manual emotes — not shown in the grid. */
    private static final Set<String> HIDDEN = Set.of(
        "water_swim", "water_idle",
        "twitch1", "twitch2", "lookaround",
        "ground_walk", "ground_idle", "ground_run", "ground_runwip",
        "cry", "blink", "angerspike",
        "silly_smile", "sad", "happy", "hurt", "angry"
    );

    private final LinearLayout layout = LinearLayout.vertical().spacing(4);
    private final Screen parent;

    public AnimationPreviewScreen() {
        this(null);
    }

    public AnimationPreviewScreen(Screen parent) {
        super(Component.translatable("gui.lucariomod.preview.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.layout.removeChildren();
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.title, this.font));

        LocalPlayer player = this.minecraft != null ? this.minecraft.player : null;
        String variant = player != null ? ClientMorphs.get(player.getUUID()) : null;
        LucarioModel model = player != null && variant != null
            ? LucarioModels.get(player.getUUID(), Variants.baseOf(variant))
            : null;

        if (model != null && !model.animations.isEmpty()) {
            List<String> names = new ArrayList<>(model.animations.keySet());
            names.removeIf(HIDDEN::contains);
            names.sort(String.CASE_INSENSITIVE_ORDER);

            // 1.6.0 (picker): adaptive grid — narrower GUIs get fewer, smaller columns
            int cols = this.width >= 480 ? 4 : this.width >= 340 ? 3 : 2;
            int btnW = Math.min(150, (this.width - 16 - (cols - 1) * 4) / cols);
            String current = PreviewState.anim();

            for (int i = 0; i < names.size(); i += cols) {
                LinearLayout row = this.layout.addChild(LinearLayout.horizontal().spacing(4));
                for (int j = i; j < Math.min(names.size(), i + cols); j++) {
                    String name = names.get(j);
                    // ▶ marks the emote that is playing right now
                    boolean active = name.equals(current) && DanceState.active();
                    Component label = Component.empty()
                        .append(active ? "▶ " : "")
                        .append(Component.translatableWithFallback("gui.lucariomod.anim." + name, name));
                    row.addChild(Button.builder(label, b -> this.play(name)).size(btnW, 20).build());
                }
            }

            LinearLayout bottom = this.layout.addChild(LinearLayout.horizontal().spacing(4));
            bottom.defaultCellSetting().paddingTop(6);
            boolean tposeActive = PreviewState.isTPose() && DanceState.active();
            bottom.addChild(
                Button.builder(
                    Component.empty()
                        .append(tposeActive ? "▶ " : "")
                        .append(Component.translatable("gui.lucariomod.preview.tpose")),
                    b -> this.play(PreviewState.TPOSE)
                ).size(btnW, 20).build()
            );
            bottom.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).size(btnW, 20).build());
        } else {
            // No morph (or the model failed to load) — animations belong to the model, nothing to preview
            this.layout.addChild(new StringWidget(Component.translatable("gui.lucariomod.preview.no_morph"), this.font));
            this.layout.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).size(180, 20).build());
        }

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private void play(String name) {
        PreviewState.set(name);
        DanceState.start(Long.MAX_VALUE);
        this.onClose();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());
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
