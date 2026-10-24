package dev.lucario.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.lucario.Variants;
import dev.lucario.client.anim.BedrockAnimation;
import java.util.Map.Entry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@Environment(EnvType.CLIENT)
public final class LucarioRenderer {
   private static final double STRIDE_CYCLE = 9.431379926718082;
   private static final double BLINK_PERIOD_SECONDS = 4.0;
   private static final float FIRST_PERSON_ARM_SCALE = 1.8F;
   private static final float FIRST_PERSON_ARM_ROLL_DEG = 20.0F;
   private static final float HAND_ITEM_OFFSET_LUCARIO = 4.5F;
   private static final float HAND_ITEM_OFFSET_RIOLU = 3.0F;
   private static final float HELD_ITEM_SCALE_LUCARIO = 0.6F;
   private static final float HELD_ITEM_SCALE_MEGA = 0.55F;
   private static final float HELD_ITEM_SCALE_RIOLU = 0.45F;
   private static final float HELD_ITEM_INWARD_LUCARIO = 2.5F;
   private static final float HELD_ITEM_INWARD_RIOLU = 1.5F;
   private static final float ELYTRA_SCALE_LUCARIO = 0.55F;
   private static final float ELYTRA_SCALE_RIOLU = 0.33F;
   private static final float ELYTRA_UP_LUCARIO = 4.0F;
   private static final float ELYTRA_BACK_LUCARIO = 1.5F;
   private static final float ELYTRA_BACK_MEGA = 1.7F;
   private static final float ELYTRA_UP_RIOLU = 3.0F;
   private static final float ELYTRA_BACK_RIOLU = 0.65F;
   private static final float ARMS_FORWARD_PITCH = -3.0F;
   private static final float ARMS_FORWARD_ROLL = 0.25F;
   private static final float CROUCH_WALK_BLEND = 0.43F;
   private static final double FLIGHT_LEG_RATE = 1.25;
   private static final float RIOLU_RIDE_LIFT = 3.0F;
   private static final float GROUND_SINK_PX = 0.5F;
   private static final Identifier ELYTRA_TEXTURE = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
   private static final float SWING_ARM_PITCH = 1.2F;
   private static final float SWING_BODY_TWIST = 0.2F;
   // 1.6.0: extra strike flourishes (scaled by the vanilla swing bell curve)
   private static final float SWING_BODY_LEAN = -0.1F;
   private static final float SWING_FINGER_CURL = -0.35F;
   private static final float OFF_ARM_BACKSWING = 0.25F;
   private static final float[] R_IDLE = new float[3];
   private static final float[] P_IDLE = new float[3];
   private static final float[] R_MOVE = new float[3];
   private static final float[] P_MOVE = new float[3];
   private static final float[] R_AUX = new float[3];
   private static final float[] P_AUX = new float[3];
   private static final float[] R_BLINK = new float[3];
   private static final float[] P_BLINK = new float[3];

   private LucarioRenderer() {
   }

   public static void render(LivingEntityRenderState state, LucarioStateAccess access, PoseStack matrices, SubmitNodeCollector collector, int light) {
      if (!state.isInvisible) {
         String variantKey = access.lucario$getVariant();
         LucarioModel model = access.lucario$getModel();
         if (model != null) {
            boolean riolu = Variants.isRiolu(variantKey);
            pose(model, state, access);
            matrices.pushPose();
            matrices.rotateDegrees(Axis.YP, 180.0F - state.bodyRot);
            boolean gliding = access.lucario$isGliding();
            // 1.6.0: smooth elytra transition — the dive rotation and the body offset
            // are scaled by the per-player glide blend weight instead of snapping in.
            float glideW = Mth.clamp(access.lucario$getGlideBlend(), 0.0F, 1.0F);
            boolean swimmingPose = state.hasPose(Pose.SWIMMING);
            if (swimmingPose) {
               float angle = !state.isInWater && !gliding ? -90.0F : -90.0F - state.xRot;
               matrices.rotateDegrees(Axis.XP, angle);
               matrices.translate(0.0F, -1.0F, 0.3F);
            } else if (glideW > 0.001F) {
               matrices.rotateDegrees(Axis.XP, (-90.0F - state.xRot) * glideW);
               matrices.translate(0.0F, -1.0F * glideW, 0.3F * glideW);
            }

            matrices.scale(-1.0F, -1.0F, 1.0F);
            matrices.scale(0.58655804F, 0.58655804F, 0.58655804F);
            matrices.translate(0.0F, -(24.0F - model.feetY) / 16.0F, 0.0F);
            boolean riding = !state.hasPose(Pose.SLEEPING) && !gliding && access.lucario$isRiding();
            boolean sleeping = state.hasPose(Pose.SLEEPING);
            boolean crouching = state.hasPose(Pose.CROUCHING);
            if (!sleeping && !riding && !crouching && !swimmingPose) {
               matrices.translate(0.0F, 0.05327691F * (1.0F - glideW), 0.0F);
            }

            if (riolu && riding) {
               matrices.translate(0.0F, -0.1875F, 0.0F);
            }

            Identifier texture = Variants.isShiny(variantKey) ? model.shinyTexture : model.texture;
            int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
            collector.submitModel(model.entityModel, state, matrices, texture, light, overlay, state.outlineColor);
            float itemScale = riolu ? HELD_ITEM_SCALE_RIOLU : (Variants.isMega(variantKey) ? HELD_ITEM_SCALE_MEGA : HELD_ITEM_SCALE_LUCARIO);
            float itemOffset = riolu ? HAND_ITEM_OFFSET_RIOLU : HAND_ITEM_OFFSET_LUCARIO;
            float itemInward = riolu ? HELD_ITEM_INWARD_RIOLU : HELD_ITEM_INWARD_LUCARIO;
            if (state instanceof ArmedEntityRenderState armed) {
               renderHeldItem(
                  model,
                  "hand_right",
                  true,
                  armed.rightHandItemState,
                  itemScale,
                  itemOffset,
                  itemInward,
                  matrices,
                  collector,
                  light,
                  overlay,
                  state.outlineColor
               );
               renderHeldItem(
                  model,
                  "hand_left",
                  false,
                  armed.leftHandItemState,
                  itemScale,
                  itemOffset,
                  itemInward,
                  matrices,
                  collector,
                  light,
                  overlay,
                  state.outlineColor
               );
            }

            if (access.lucario$hasElytra() && !Variants.hidesElytra(variantKey)) {
               renderElytra(
                  model,
                  riolu,
                  Variants.isMega(variantKey),
                  gliding,
                  state.hasPose(Pose.CROUCHING),
                  access.lucario$getElytraTexture(),
                  state,
                  matrices,
                  collector,
                  light,
                  overlay
               );
            }

            matrices.popPose();
         }
      }
   }

   private static BedrockAnimation first(LucarioModel m, String... names) {
      for (String n : names) {
         BedrockAnimation a = m.animation(n);
         if (a != null) {
            return a;
         }
      }

      return null;
   }

   static void pose(LucarioModel m, LivingEntityRenderState s, LucarioStateAccess access) {
      boolean sprinting = access.lucario$isSprinting();
      boolean water = s.isInWater;
      // Patch 11: death plays the "faint" animation (loop=false holds the last frame).
      // NOTE (verify on build): LivingEntityRenderState.deathTime is the official field name on 26.3.
      boolean dying = s.deathTime > 0.0F && m.animation("faint") != null;
      boolean sleeping = !dying && s.hasPose(Pose.SLEEPING) && m.animation("sleep") != null;
      boolean gliding = !sleeping && !dying && access.lucario$isGliding();
      boolean armsForward = !sleeping && !dying && (gliding || s.hasPose(Pose.SWIMMING));
      boolean riding = !sleeping && !dying && !gliding && access.lucario$isRiding();
      boolean crouching = !sleeping && !dying && !water && !riding && s.hasPose(Pose.CROUCHING);
      BedrockAnimation idle;
      BedrockAnimation move;
      if (dying) {
         idle = m.animation("faint");
         move = null;
      } else if (sleeping) {
         idle = m.animation("sleep");
         move = null;
      } else if (water) {
         idle = first(m, "water_idle", "ground_idle");
         move = first(m, "water_swim", "ground_walk");
      } else {
         idle = m.animation("ground_idle");
         move = sprinting && !crouching ? first(m, "ground_run", "ground_runwip", "ground_walk") : m.animation("ground_walk");
      }

      if (gliding) {
         idle = m.animation("ground_idle") != null ? m.animation("ground_idle") : idle;
         move = first(m, "ground_walk");
      }

      if (riding) {
         move = null;
      }

      BedrockAnimation armIdle = m.animation("ground_idle") != null ? m.animation("ground_idle") : idle;
      BedrockAnimation blink = m.animation("blink");
      double seconds = s.ageInTicks / 20.0;
      double phase = s.walkAnimationPos / STRIDE_CYCLE;
      phase -= Math.floor(phase);

      // 1.6.0: smoothed animation weights (see MotionBlend) — poses crossfade instead of snapping.
      float glideW = Mth.clamp(access.lucario$getGlideBlend(), 0.0F, 1.0F);
      float danceW = Mth.clamp(access.lucario$getDanceBlend(), 0.0F, 1.0F);
      BedrockAnimation dance = m.animation("dance");
      if (dying || sleeping || water || riding || dance == null) {
         danceW = 0.0F;
      }

      // 1.6.1: the emote runs on its own clock (starts at the toggle, freezes on stop)
      // instead of the entity's global age, so it always begins from its first keyframe.
      double danceTime = DanceState.danceSeconds();

      // Patch 11: battle stance lingers ~2s after attacking or being hurt.
      // 1.6.0: it now crossfades in/out and ALSO plays while walking — the legs
      // blend between the stance and the walk cycle, so you can move with it on.
      float battleW = Mth.clamp(access.lucario$getBattleBlend(), 0.0F, 1.0F);
      BedrockAnimation battle = m.animation("battle_idle");
      if (dying || sleeping || water || riding || armsForward || danceW > 0.0F || battle == null) {
         battleW = 0.0F;
      }

      if (danceW > 0.0F) {
         crouching = false;
      }

      double walkTime = move != null && move.length > 0.0 ? phase * move.length : seconds;
      // 1.6.0: while gliding the legs crossfade from the walk cycle to a faster flutter
      double flightTime = move != null && move.length > 0.0 ? seconds * FLIGHT_LEG_RATE % 1.0 * move.length : seconds;
      float w = move == null ? 0.0F : Mth.clamp(s.walkAnimationSpeed * 1.5F, 0.0F, 1.0F);
      if (crouching) {
         w *= CROUCH_WALK_BLEND;
      }

      double blinkT = seconds % BLINK_PERIOD_SECONDS;
      boolean blinking = !dying && blink != null && blink.length > 0.0 && blinkT < blink.length;
      float headYaw = Mth.clamp(s.yRot, -50.0F, 50.0F);
      float headPitch = Mth.clamp(s.xRot, -45.0F, 45.0F);
      float swing = access.lucario$getSwing();
      boolean swinging = swing > 0.0F && swing < 1.0F && !sleeping && !dying;
      // 1.6.4: "strike" marks a REAL attack (left click). A plain vanilla swing
      // (right-click item use) only moves the striking arm — no body twist,
      // chest lean, fist or off-arm backswing.
      boolean strike = swinging && access.lucario$isStrike();
      boolean swingRight = access.lucario$isSwingRight();
      String swingBone = swingRight ? "arm_right" : "arm_left";
      String offArmBone = swingRight ? "arm_left" : "arm_right";
      String twistBone = m.parts.containsKey("chest") ? "chest" : "torso";
      float swingPitch = 0.0F;
      float twist = 0.0F;
      float strikeArc = 0.0F;
      float followArc = 0.0F;
      if (swinging) {
         float f = 1.0F - swing;
         f *= f;
         f *= f;
         f = 1.0F - f;
         float g = Mth.sin(f * (float) Math.PI);
         float h = Mth.sin(swing * (float) Math.PI);
         // 1.6.0: the arm swing rides the vanilla swing bell curve (smooth in AND out),
         // works while walking, and fades out when the dance emote takes over.
         // 1.6.4: the arm raise plays for EVERY swing (vanilla-like hand twitch on
         // right-click use too); the dramatic strike extras below only on real attacks.
         float attackW = 1.0F - danceW;
         swingPitch = -(g * SWING_ARM_PITCH + h * 0.35F) * attackW;
         if (strike) {
            strikeArc = g * attackW;
            followArc = h * attackW;
            twist = Mth.sin(Mth.sqrt(swing) * (float) (Math.PI * 2)) * SWING_BODY_TWIST * attackW;
            if (!swingRight) {
               twist = -twist;
            }
         }
      }

      for (Entry<String, ModelPart> e : m.parts.entrySet()) {
         String name = e.getKey();
         ModelPart part = e.getValue();
         float[] b = m.base.get(name);
         boolean legChain = isLegChain(name);
         boolean armChain = armsForward && isArmChain(name);
         BedrockAnimation boneIdle = armChain ? armIdle : idle;
         float bw = armChain || gliding && !legChain ? 0.0F : w;
         // base/idle pose — the battle stance crossfades over it below
         if (boneIdle != null) {
            boneIdle.sample(name, seconds, R_IDLE, P_IDLE);
         } else {
            clear(R_IDLE, P_IDLE);
         }

         // 1.6.0: smooth transition into the combat pose
         if (battleW > 0.0F) {
            battle.sample(name, seconds, R_AUX, P_AUX);
            lerp3(R_IDLE, P_IDLE, R_AUX, P_AUX, battleW);
         }

         // locomotion; while gliding the legs crossfade into the faster flutter phase
         if (move != null && bw > 0.0F) {
            double mt = glideW > 0.001F && legChain && move.length > 0.0
               ? Mth.lerp(glideW, (float)walkTime, (float)flightTime)
               : walkTime;
            move.sample(name, mt, R_MOVE, P_MOVE);
         } else {
            clear(R_MOVE, P_MOVE);
         }

         float rx = Mth.lerp(bw, R_IDLE[0], R_MOVE[0]);
         float ry = Mth.lerp(bw, R_IDLE[1], R_MOVE[1]);
         float rz = Mth.lerp(bw, R_IDLE[2], R_MOVE[2]);
         float px = Mth.lerp(bw, P_IDLE[0], P_MOVE[0]);
         float py = Mth.lerp(bw, P_IDLE[1], P_MOVE[1]);
         float pz = Mth.lerp(bw, P_IDLE[2], P_MOVE[2]);
         // 1.6.0: dance emote crossfades over everything and loops until toggled off
         if (danceW > 0.0F) {
            dance.sample(name, danceTime, R_AUX, P_AUX);
            rx = Mth.lerp(danceW, rx, R_AUX[0]);
            ry = Mth.lerp(danceW, ry, R_AUX[1]);
            rz = Mth.lerp(danceW, rz, R_AUX[2]);
            px = Mth.lerp(danceW, px, P_AUX[0]);
            py = Mth.lerp(danceW, py, P_AUX[1]);
            pz = Mth.lerp(danceW, pz, P_AUX[2]);
         }

         if (blinking && blink.sample(name, blinkT, R_BLINK, P_BLINK)) {
            rx += R_BLINK[0];
            ry += R_BLINK[1];
            rz += R_BLINK[2];
            px += P_BLINK[0];
            py += P_BLINK[1];
            pz += P_BLINK[2];
         }

         float pitch = b[3] + (float)Math.toRadians(rx) * 1.0F;
         float yaw = b[4] + (float)Math.toRadians(ry) * 1.0F;
         float roll = b[5] + (float)Math.toRadians(rz) * 1.0F;
         if (name.equals("head") && !dying) {
            yaw += (float)Math.toRadians(headYaw);
            pitch += (float)Math.toRadians(headPitch);
         }

         if (crouching) {
            Float extra = m.crouchPitch.get(name);
            if (extra != null) {
               pitch += extra;
            }

            if (name.equals("body")) {
               py -= m.crouchDrop;
            }
         }

         if (riding) {
            Float sit = m.sitPitch.get(name);
            if (sit != null) {
               pitch += sit;
            }
         }

         if (swinging && name.equals(twistBone)) {
            yaw += twist;
            // 1.6.0: chest leans into the strike
            pitch += SWING_BODY_LEAN * strikeArc;
         }

         // 1.6.0: the striking paw closes into a fist
         if (strikeArc != 0.0F && name.startsWith(swingRight ? "finger_right" : "finger_left")) {
            pitch += SWING_FINGER_CURL * strikeArc;
         }

         boolean shoulder = name.equals("arm_right") || name.equals("arm_left");
         if (shoulder) {
            float extraPitch = 0.0F;
            float extraRoll = 0.0F;
            if (armsForward) {
               // 1.6.0: arms ease forward with the glide blend (crawling stays instant)
               float forwardW = !gliding && s.hasPose(Pose.SWIMMING) ? 1.0F : glideW;
               extraPitch += ARMS_FORWARD_PITCH * forwardW;
               extraRoll = (name.equals("arm_right") ? ARMS_FORWARD_ROLL : -ARMS_FORWARD_ROLL) * forwardW;
            }

            if (swinging && name.equals(swingBone)) {
               extraPitch += swingPitch;
            } else if (followArc != 0.0F && name.equals(offArmBone)) {
               // 1.6.0: the free arm pulls back slightly for balance
               extraPitch += OFF_ARM_BACKSWING * followArc;
            }

            if (extraPitch != 0.0F || extraRoll != 0.0F) {
               Quaternionf q = new Quaternionf()
                  .rotationX(extraPitch)
                  .mul(new Quaternionf().rotationZ(extraRoll))
                  .mul(new Quaternionf().rotationZYX(roll, yaw, pitch));
               Vector3f euler = q.getEulerAnglesZYX(new Vector3f());
               pitch = euler.x;
               yaw = euler.y;
               roll = euler.z;
            }
         }

         part.setPos(b[0] + px, b[1] - py, b[2] + pz);
         part.setRotation(pitch, yaw, roll);
      }
   }

   private static boolean isArmChain(String n) {
      return n.startsWith("arm_") || n.startsWith("hand_") || n.startsWith("finger_");
   }

   private static boolean isLegChain(String n) {
      return n.startsWith("leg_") || n.startsWith("foot_");
   }

   private static void applyChain(LucarioModel m, String bone, PoseStack matrices) {
      for (String n : m.chainTo(bone)) {
         ModelPart p = m.parts.get(n);
         matrices.translate(p.x / 16.0F, p.y / 16.0F, p.z / 16.0F);
         if (p.xRot != 0.0F || p.yRot != 0.0F || p.zRot != 0.0F) {
            matrices.rotate(new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot));
         }
      }
   }

   private static void renderHeldItem(
      LucarioModel m,
      String handBone,
      boolean right,
      ItemStackRenderState item,
      float itemScale,
      float offsetUnits,
      float inwardUnits,
      PoseStack matrices,
      SubmitNodeCollector collector,
      int light,
      int overlay,
      int outlineColor
   ) {
      if (item != null && !item.isEmpty()) {
         if (m.parts.containsKey(handBone)) {
            matrices.pushPose();
            matrices.translate((right ? 1.0F : -1.0F) * inwardUnits / 16.0F, 0.0F, 0.0F);
            applyChain(m, handBone, matrices);
            matrices.translate((right ? -1.0F : 1.0F) * offsetUnits / 16.0F, 0.0F, 0.0F);
            float k = itemScale / 0.58655804F;
            matrices.scale(k, k, k);
            matrices.rotateDegrees(Axis.ZP, right ? 90.0F : -90.0F);
            matrices.rotateDegrees(Axis.XP, -90.0F);
            matrices.rotateDegrees(Axis.YP, 180.0F);
            matrices.translate(0.0F, 0.125F, 0.0F);
            item.submit(matrices, collector, light, overlay, outlineColor);
            matrices.popPose();
         }
      }
   }

   private static void renderElytra(
      LucarioModel m,
      boolean riolu,
      boolean mega,
      boolean gliding,
      boolean crouching,
      Identifier texture,
      LivingEntityRenderState state,
      PoseStack matrices,
      SubmitNodeCollector collector,
      int light,
      int overlay
   ) {
      String attach = m.parts.containsKey("chest") ? "chest" : "torso";
      if (m.parts.containsKey(attach)) {
         float pitch = (float) (Math.PI / 12);
         float roll = (float) (-Math.PI / 12);
         float yaw = 0.0F;
         float y0 = 0.0F;
         if (gliding) {
            pitch = (float) (Math.PI / 9);
            roll = (float) (-Math.PI / 2);
         } else if (crouching) {
            pitch = (float) (Math.PI * 2.0 / 9.0);
            roll = (float) (-Math.PI / 4);
            y0 = 3.0F;
            yaw = 0.08726646F;
         }

         m.elytraLeft.setPos(5.0F, y0, 0.0F);
         m.elytraLeft.setRotation(pitch, yaw, roll);
         m.elytraRight.setPos(-5.0F, y0, 0.0F);
         m.elytraRight.setRotation(pitch, -yaw, -roll);
         matrices.pushPose();
         applyChain(m, attach, matrices);
         matrices.translate(0.0F, -(riolu ? ELYTRA_UP_RIOLU : ELYTRA_UP_LUCARIO) / 16.0F, (riolu ? ELYTRA_BACK_RIOLU : (mega ? ELYTRA_BACK_MEGA : ELYTRA_BACK_LUCARIO)) / 16.0F);
         float k = (riolu ? ELYTRA_SCALE_RIOLU : ELYTRA_SCALE_LUCARIO) / 0.58655804F;
         matrices.scale(k, k, k);
         matrices.translate(0.0F, 0.0F, 0.125F);
         collector.submitModel(m.elytraModel, state, matrices, texture != null ? texture : ELYTRA_TEXTURE, light, overlay, state.outlineColor);
         matrices.popPose();
      }
   }

   public static void renderFirstPersonArm(PoseStack matrices, SubmitNodeCollector collector, int light, String variantKey, boolean right) {
      LucarioModel m = LucarioModels.getFirstPerson(Variants.baseOf(variantKey));
      if (m != null) {
         ModelPart shoulder = m.parts.get(right ? "arm_right" : "arm_left");
         if (shoulder != null) {
            poseIdle(m, System.nanoTime() / 1.0E9);
            shoulder.setPos(0.0F, 0.0F, 0.0F);
            float roll = (float)Math.toRadians(FIRST_PERSON_ARM_ROLL_DEG) * (right ? -1.0F : 1.0F);
            shoulder.setRotation(shoulder.xRot, shoulder.yRot, shoulder.zRot + roll);
            Identifier texture = Variants.isShiny(variantKey) ? m.shinyTexture : m.texture;
            matrices.pushPose();
            matrices.translate((right ? -5.0F : 5.0F) / 16.0F, 0.125F, 0.0F);
            float s = 1.0558045F;
            matrices.scale(s, s, s);
            collector.submitModelPart(shoulder, matrices, RenderTypes.entityCutout(texture), light, OverlayTexture.NO_OVERLAY, null);
            matrices.popPose();
         }
      }
   }

   private static void poseIdle(LucarioModel m, double seconds) {
      BedrockAnimation idle = m.animation("ground_idle");

      for (Entry<String, ModelPart> e : m.parts.entrySet()) {
         String name = e.getKey();
         ModelPart part = e.getValue();
         float[] b = m.base.get(name);
         if (idle != null) {
            idle.sample(name, seconds, R_IDLE, P_IDLE);
         } else {
            clear(R_IDLE, P_IDLE);
         }

         part.setPos(b[0] + P_IDLE[0], b[1] - P_IDLE[1], b[2] + P_IDLE[2]);
         part.setRotation(
            b[3] + (float)Math.toRadians(R_IDLE[0]) * 1.0F, b[4] + (float)Math.toRadians(R_IDLE[1]) * 1.0F, b[5] + (float)Math.toRadians(R_IDLE[2]) * 1.0F
         );
      }
   }

   private static void clear(float[] r, float[] p) {
      r[0] = r[1] = r[2] = 0.0F;
      p[0] = p[1] = p[2] = 0.0F;
   }

   /** 1.6.0: in-place crossfade of a sampled rotation/position pair towards another. */
   private static void lerp3(float[] r, float[] p, float[] r2, float[] p2, float w) {
      r[0] = Mth.lerp(w, r[0], r2[0]);
      r[1] = Mth.lerp(w, r[1], r2[1]);
      r[2] = Mth.lerp(w, r[2], r2[2]);
      p[0] = Mth.lerp(w, p[0], p2[0]);
      p[1] = Mth.lerp(w, p[1], p2[1]);
      p[2] = Mth.lerp(w, p[2], p2[2]);
   }
}
