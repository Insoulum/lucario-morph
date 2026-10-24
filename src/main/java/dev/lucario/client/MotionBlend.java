package dev.lucario.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 1.6.0: per-player smoothed animation weights (0..1) so pose changes no longer
 * snap between states. Each weight eases towards its boolean target with an
 * exponential curve — a short time-constant while turning on (~0.1-0.15 s) and a
 * slightly longer one while turning off (~0.2-0.3 s), which reads as a natural
 * transition instead of a teleport between poses.
 * <p>
 * Values are updated once per frame from {@code AvatarRendererMixin#copyState}
 * (which runs on the render thread for every rendered morphed player) and are
 * consumed by {@link LucarioRenderer#pose}. Time-based easing (not tick-based)
 * keeps the motion frame-rate independent.
 * <p>
 * 1.6.1 FIX: the integration timestamp is now PER WEIGHT, not per player.
 * The three blend queries run back-to-back inside one extract pass, so with the
 * old shared timestamp only the first query (battle) received the real frame
 * delta — dance and glide got a ~0 ms step every frame and their weights crept
 * at a tiny fraction of the intended speed (glide never leaned in fully, the
 * dance faded in/out over tens of seconds and kept swaying long after it was
 * toggled off).
 */
@Environment(EnvType.CLIENT)
public final class MotionBlend {
   private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
   private static final long MAX_STEP_MS = 100L;
   private static final float EPSILON = 0.002F;

   private MotionBlend() {
   }

   /** Elytra glide pose weight: quick lean-in, soft return. */
   public static float glide(UUID id, boolean gliding) {
      return blend(id, gliding, 0, 90.0F, 220.0F);
   }

   /** Battle-stance weight (battle_idle): eases in after a fight, lingers out. */
   public static float battle(UUID id, boolean combat) {
      return blend(id, combat, 1, 140.0F, 320.0F);
   }

   /** Dance emote weight: crossfades the emote in/out of the current pose. */
   public static float dance(UUID id, boolean dancing) {
      return blend(id, dancing, 2, 160.0F, 220.0F);
   }

   /** Drops all per-player state (called on disconnect, patch 8 hygiene). */
   public static void clear() {
      STATES.clear();
   }

   private static float blend(UUID id, boolean on, int index, float tauInMs, float tauOutMs) {
      long now = System.currentTimeMillis();
      State s = STATES.computeIfAbsent(id, k -> new State());
      synchronized (s) {
         float target = on ? 1.0F : 0.0F;
         if (s.lastMs[index] == 0L) {
            // First observation of this weight: snap to the target so a player
            // seen mid-state does not animate from zero.
            s.weight[index] = target;
         } else {
            long dt = Math.min(Math.max(0L, now - s.lastMs[index]), MAX_STEP_MS);
            float tau = on ? tauInMs : tauOutMs;
            float a = 1.0F - (float)Math.exp(-(float)dt / tau);
            s.weight[index] += (target - s.weight[index]) * a;
            if (Math.abs(target - s.weight[index]) < EPSILON) {
               s.weight[index] = target;
            }
         }

         s.lastMs[index] = now;
         return s.weight[index];
      }
   }

   private static final class State {
      final float[] weight = new float[3];
      /** 1.6.1: one timestamp PER WEIGHT — a shared one starved dance/glide of frame time. */
      final long[] lastMs = new long[3];
   }
}
