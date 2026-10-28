package dev.lucario.client;

import dev.lucario.Variants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;

/**
 * Local emote clock. 1.6.1: the emote is picked in {@link AnimationPreviewScreen}
 * (opened by {@link KeyBindings#DANCE}); {@code toggle()} remains for compatibility but is
 * no longer wired to the key. <p>
 * Patch 11: originally the dance emote was driven by {@link KeyBindings#DANCE} directly.
 * <p>
 * 1.6.0: the emote now LOOPS. The dance animation itself is marked
 * {@code loop:true} in the asset and the sampler wraps time, so the state simply
 * stays active until the player presses the key again, starts moving, mounts,
 * enters water or starts gliding (see {@link #tick}).
 * <p>
 * 1.6.1: the emote clock starts at the toggle moment ({@link #danceSeconds()}),
 * so the animation always begins from its first keyframe instead of a random
 * phase of the entity's global age, and freezes when the emote stops so the
 * crossfade-out holds a still pose instead of twitching.
 * <p>
 * 1.6.2: starting the dance switches the camera to third person (back view)
 * if it was in first person, and switching back on stop; taking damage also
 * cancels the emote (checked in {@link #tick} and in the render-state mixin).
 * <p>
 * 1.7.0: эмоут СИНХРОНИЗИРУЕТСЯ ({@link EmoteSync}): старт и стоп публикуются на
 * сервис синхронизации (PUT во все зеркала, мгновенно), а чужие эмоуты приходят
 * обратно по WebSocket-каналу. Локальное поведение не изменилось: если синхронизация
 * выключена или зеркал нет — эмоут работает как раньше, только у себя.
 */
@Environment(EnvType.CLIENT)
public final class DanceState {
   private static volatile long untilMs = 0L;
   /** 1.6.1: wall-clock moment the emote was toggled on. */
   private static volatile long startMs = 0L;
   /** 1.6.1: emote time frozen at the moment it stopped (for the fade-out). */
   private static volatile float frozenSeconds = 0.0F;
   /** 1.6.2: whether WE switched the camera for the emote (never touch the user's own F5 choice). */
   private static boolean switchedCamera = false;
   private static CameraType cameraBefore = null;

   private DanceState() {
   }

   public static boolean active() {
      return System.currentTimeMillis() < untilMs;
   }

   /** 1.6.1: animation time of the emote in seconds — runs while active, frozen after stop. */
   public static float danceSeconds() {
      long until = untilMs;
      if (until == 0L || startMs == 0L) {
         return frozenSeconds;
      }

      return (System.currentTimeMillis() - startMs) / 1000.0F;
   }

   public static void start(long until) {
      // 1.6.1: restart the emote clock so the dance begins from its first keyframe
      startMs = System.currentTimeMillis();
      frozenSeconds = 0.0F;
      untilMs = until;
      // 1.6.2: show the emote from third person until it ends
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && mc.options.getCameraType().isFirstPerson()) {
         cameraBefore = mc.options.getCameraType();
         mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
         switchedCamera = true;
      }
      // 1.7.7-debug4: событие старта — рядом с ним в трассировке появится и его PUT.
      NetDebug.http("эмоут \"" + PreviewState.anim() + "\": старт (клавиша/превью)");
      // 1.7.0: публикуем старт эмоута (внутри — проверка согласия на синхронизацию)
      EmoteSync.publishStart(PreviewState.anim());
   }

   /**
    * 1.7.7-debug4: стоп с причиной — причина попадает в трассировку рядом с соответствующим PUT.
    * Все прежние вызовы stop() переведены на перегрузку со строкой-причиной.
    */
   public static void stop(String cause) {
      boolean wasActive = active();
      long until = untilMs;
      if (until != 0L && startMs != 0L) {
         frozenSeconds = (System.currentTimeMillis() - startMs) / 1000.0F;
      }

      untilMs = 0L;
      // 1.6.2: hand the camera back — but only if we switched it and the player
      // has not cycled views manually in the meantime
      if (switchedCamera) {
         switchedCamera = false;
         Minecraft mc = Minecraft.getInstance();
         if (mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            mc.options.setCameraType(cameraBefore != null ? cameraBefore : CameraType.FIRST_PERSON);
         }

         cameraBefore = null;
      }

      // 1.7.0: стоп тоже публикуем — и мгновенно. Даже если стоп-событие потеряется,
      // TTL сервиса погасит эмоут максимум за ~30 с (и пришлёт его как обычный стоп).
      if (wasActive) {
         NetDebug.http("эмоут: стоп (" + cause + ")");
         EmoteSync.publishStop();
      }
   }

   public static void toggle() {
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      if (player == null) {
         return;
      }

      if (active()) {
         stop("клавиша");
         return;
      }

      String variant = ClientMorphs.get(player.getUUID());
      if (variant == null) {
         player.sendSystemMessage(Component.translatable("lucariomod.dance.no_morph"));
         return;
      }

      LucarioModel model = LucarioModels.get(player.getUUID(), Variants.baseOf(variant));
      if (model == null) {
         return;
      }

      if (model.animation("dance") == null) {
         player.sendSystemMessage(Component.translatable("lucariomod.dance.unavailable"));
         return;
      }

      // 1.6.0: loop until toggled off / movement — no more stopping after one cycle.
      start(Long.MAX_VALUE);
      player.sendSystemMessage(Component.translatable("lucariomod.dance.started"));
   }

   /** Auto-cancel the emote when the player starts moving / leaves the ground state. */
   public static void tick(Minecraft mc) {
      if (!active()) {
         return;
      }

      LocalPlayer player = mc.player;
      if (player == null) {
         stop("игрок пропал");
         return;
      }

      if (player.isPassenger() || player.isInWater() || player.isFallFlying()) {
         stop("пассажир/вода/полёт");
         return;
      }

      // 1.6.2: taking damage cancels the emote
      if (player.hurtTime > 0) {
         stop("урон");
         return;
      }

      // NOTE (verified on 26.3): ClientInput.getMoveVector() returns the movement vector;
      // the old forwardImpulse/leftImpulse fields are gone.
      if (player.input != null) {
         Vec2 move = player.input.getMoveVector();
         if (move.x != 0.0F || move.y != 0.0F) {
            stop("движение");
         }
      }
   }
}
