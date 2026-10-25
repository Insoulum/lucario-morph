package dev.lucario.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 1.6.1: which emote animation the preview menu has selected for THIS client.
 * <p>
 * The {@code J} key opens the {@link AnimationPreviewScreen}; picking a button stores the
 * animation's short name here (e.g. {@code "dance"}, {@code "specialist_full"}) and turns the
 * emote on via {@link DanceState#start}. {@link LucarioRenderer} reads the value every frame
 * and crossfades the model into that animation while {@link DanceState#active()} is true.
 * <p>
 * The special value {@link #TPOSE} shows the un-posed skeleton (all sampled offsets zeroed) —
 * handy for checking bone rigging. Client-local only: nothing here is synced, the protocol
 * stays unchanged (see the note in {@link DanceState}).
 */
@Environment(EnvType.CLIENT)
public final class PreviewState {
    /** Sentinel animation name for the raw bind pose (T-pose button in the preview screen). */
    public static final String TPOSE = "__tpose__";

    /** Current emote: an animation short name or {@link #TPOSE}. Volatile: written by the GUI thread, read by the render thread. */
    private static volatile String anim = "dance";

    private PreviewState() {}

    public static String anim() {
        return anim;
    }

    /** True while the T-pose (raw bind pose) is selected instead of a real animation. */
    public static boolean isTPose() {
        return TPOSE.equals(anim);
    }

    /** Selects the emote to play; null/empty falls back to the plain dance. */
    public static void set(String name) {
        anim = name != null && !name.isEmpty() ? name : "dance";
    }
}
