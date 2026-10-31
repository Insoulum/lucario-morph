package dev.lucario.client;

import dev.lucario.Variants;
import dev.lucario.client.anim.BedrockAnimation;
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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Draws Lucario/Riolu in place of the vanilla player model: pose from Bedrock animations + head rotation,
 * plus what the animations don't cover: sneaking, arm swing (punch/block breaking), held items and
 * the first-person arm. In 26.x rendering is a submit pipeline: instead of drawing directly through
 * VertexConsumerProvider, we pose the bones and submit the model via SubmitNodeCollector.
 */
@Environment(EnvType.CLIENT)
public final class LucarioRenderer {
    /** One full stride cycle of the vanilla legs: cos(limbAngle * 0.6662). */
    private static final double STRIDE_CYCLE = 2.0 * Math.PI / 0.6662;
    private static final double BLINK_PERIOD_SECONDS = 4.0;

    // ---- settings (tweak to taste) ----
    /** How much bigger the first-person arm is than the "honest" model scale (Lucario's arm is thinner than Steve's). */
    private static final float FIRST_PERSON_ARM_SCALE = 1.8f;
    /** How many degrees the first-person arm is pressed down/toward the body from the rest pose (stops "elbow spread"). */
    private static final float FIRST_PERSON_ARM_ROLL_DEG = 20f;
    /** Item offset from the wrist along the arm (downward), in Bedrock model units: Lucario/Mega, Riolu. */
    private static final float HAND_ITEM_OFFSET_LUCARIO = 4.5f;
    private static final float HAND_ITEM_OFFSET_RIOLU = 3.0f;
    /** Held item size relative to normal (1.0 = same as Steve). */
    private static final float HELD_ITEM_SCALE_LUCARIO = 0.6f;
    private static final float HELD_ITEM_SCALE_MEGA = 0.55f;
    private static final float HELD_ITEM_SCALE_RIOLU = 0.45f;
    /** Item shift toward the body (toward the model center), in Bedrock units: Lucario/Mega, Riolu. */
    private static final float HELD_ITEM_INWARD_LUCARIO = 2.5f;
    private static final float HELD_ITEM_INWARD_RIOLU = 1.5f;
    /** Elytra wing size relative to normal and position on the back (Bedrock units from the chest bone). */
    private static final float ELYTRA_SCALE_LUCARIO = 0.55f;
    private static final float ELYTRA_SCALE_RIOLU = 0.33f;
    private static final float ELYTRA_UP_LUCARIO = 4.0f, ELYTRA_BACK_LUCARIO = 1.5f;
    private static final float ELYTRA_BACK_MEGA = 1.7f;
    // 1.8.1-pre7: Riolu's wings hug the body a touch closer (0.65 -> 0.45 -> 0.3).
    private static final float ELYTRA_UP_RIOLU = 3.0f, ELYTRA_BACK_RIOLU = 0.3f;
    /** Cape cloth (1.8.1 prototype): size relative to the vanilla cloak box; the pivot position on
     *  the back (Bedrock units from the chest/torso bone -- the cloth hangs down and BACK from the
     *  pivot, so BACK sets the gap from the body surface); and the hem tilt away from the back
     *  (radians, more while sneaking). Shown while the picker's "Cape" toggle is Yes and no elytra
     *  is worn. */
    private static final float CAPE_SCALE_LUCARIO = 0.72f;
    private static final float CAPE_SCALE_RIOLU = 0.5f;
    private static final float CAPE_UP_LUCARIO = 4.0f, CAPE_BACK_LUCARIO = 2.7f;
    private static final float CAPE_BACK_MEGA = 2.7f;
    private static final float CAPE_UP_RIOLU = 3.0f, CAPE_BACK_RIOLU = 1.3f;
    private static final float CAPE_TILT_CROUCH = 0.40f;
    /** Standing drape per form (1.8.1-pre6): Riolu keeps the gentle original angle; regular
     *  Lucario tilts a bit more so the hem clears its fluffy hip fur (as Mega Lucario already
     *  does, only weaker); Mega Lucario drapes strongest of the three. */
    private static final float CAPE_TILT_RIOLU = 0.10f, CAPE_TILT_LUCARIO = 0.2f;
    /** Mega Lucario's chest fur is thick: a stronger standing drape keeps the hem clear of it
     *  (1.8.1 -- without the extra tilt the cloth sinks into the fur ruff at the hips). */
    private static final float CAPE_TILT_MEGA = 0.4f;
    /** Extra hem flare at full running speed (added to the standing tilt, scaled by the walk weight). */
    private static final float CAPE_TILT_RUN = 0.22f;
    /** Subtle billow of the hem BEHIND the back at the stride frequency (radians at full walk
     *  weight): the cloth lifts off the back and settles back onto its tilt. It never swings
     *  side-to-side; and since the standing tilt is 0.10, the hem stays clear of the body even
     *  at the lowest point of the flutter. */
    private static final float CAPE_FLAP = 0.05f;
    /** Arms forward when swimming/crawling/gliding: shoulder pitch (rad) and pulling the arms toward the body axis. */
    private static final float ARMS_FORWARD_PITCH = -3.0f;
    private static final float ARMS_FORWARD_ROLL = 0.25f; // rad, spreading the arms apart (so they don't clip the head)
    /** How much slower the legs swing while walking when sneaking (a fraction of the normal swing). */
    private static final float CROUCH_WALK_BLEND = 0.43f;
    /** Stride cycles per second of the legs during elytra flight. */
    private static final double FLIGHT_LEG_RATE = 1.25;
    /** How many Bedrock units Riolu is lifted in a boat/minecart etc. (otherwise it would barely peek over the seat).
     *  1.8.1-pre6: raised 3.0 -> 4.5 -- even with the old 3.0 lift the sitting legs still sank into the seat. */
    private static final float RIOLU_RIDE_LIFT = 4.5f;
    /** How many pixels (1/16-block pixels) to lower the model in "ground" poses (standing/walking)
     *  so the soles don't "hover" above the ground. Raise the value if the model still floats,
     *  lower it or set it to 0 if the feet start sinking. Not applied while sneaking -- that has its own
     *  precise seating (crouchDrop); the shared shift would sink the folded feet into the ground. */
    private static final float GROUND_SINK_PX = 0.5f;

    private static final Identifier ELYTRA_TEXTURE = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
    /** Arm swing extent on a strike (rad) and torso twist. */
    private static final float SWING_ARM_PITCH = 1.2f;
    private static final float SWING_BODY_TWIST = 0.2f;
    // 1.6.0: extra strike flourishes (scaled by the vanilla swing bell curve)
    private static final float SWING_BODY_LEAN = -0.1f;
    private static final float SWING_FINGER_CURL = -0.35f;
    private static final float OFF_ARM_BACKSWING = 0.25f;

    private static final float[] R_IDLE = new float[3], P_IDLE = new float[3];
    private static final float[] R_MOVE = new float[3], P_MOVE = new float[3];
    private static final float[] R_AUX = new float[3], P_AUX = new float[3];
    private static final float[] R_BLINK = new float[3], P_BLINK = new float[3];

    private LucarioRenderer() {}

    // ------------------------------------------------------------------ third person / other players

    public static void render(LivingEntityRenderState state, LucarioStateAccess access,
                              PoseStack matrices, SubmitNodeCollector collector, int light) {
        if (state.isInvisible) return;
        String variantKey = access.lucario$getVariant();
        // Each player has their own model (1.4.3): identical morphs no longer share bones,
        // and one player's pose can't overwrite another's when the submits are drawn later.
        LucarioModel model = access.lucario$getModel();
        if (model == null) return;

        boolean riolu = Variants.isRiolu(variantKey);
        pose(model, state, access);

        matrices.pushPose();
        matrices.rotateDegrees(Axis.YP, 180.0F - state.bodyRot);
        boolean gliding = access.lucario$isGliding();
        // 1.6.0: smooth elytra transition — the dive rotation and the body offset
        // are scaled by the per-player glide blend weight instead of snapping in.
        float glideW = Math.clamp(access.lucario$getGlideBlend(), 0.0F, 1.0F);
        boolean swimmingPose = state.hasPose(Pose.SWIMMING);
        if (swimmingPose) {
            float angle = (state.isInWater || gliding) ? -90.0F - state.xRot : -90.0F;
            matrices.rotateDegrees(Axis.XP, angle);
            matrices.translate(0.0F, -1.0F, 0.3F);
        } else if (glideW > 0.001f) {
            matrices.rotateDegrees(Axis.XP, (-90.0F - state.xRot) * glideW);
            matrices.translate(0.0F, -1.0F * glideW, 0.3F * glideW);
        }
        matrices.scale(-1.0F, -1.0F, 1.0F);
        matrices.scale(LucarioModel.RENDER_SCALE, LucarioModel.RENDER_SCALE, LucarioModel.RENDER_SCALE);
        // y_java = 24 - y_bedrock; the sole at y_bedrock = feetY must end up on the ground
        matrices.translate(0.0F, -(24.0F - model.feetY) / 16.0F, 0.0F);
        // Riolu in a vehicle (boat, minecart, etc.) sits a bit higher so it doesn't sink into the seat
        boolean riding = !state.hasPose(Pose.SLEEPING) && !gliding && access.lucario$isRiding();
        // "Ground" poses (standing/walking): lower the model a bit so it doesn't hover above the ground.
        // While swimming the model is rotated horizontal, riding has its own seating, and while sneaking
        // crouchDrop already puts the soles exactly on the ground -- the extra shift sank the legs into the ground.
        // 1.6.0: in flight the shift fades out smoothly with the glide transition (multiplied by 1 - glideW).
        boolean sleeping = state.hasPose(Pose.SLEEPING);
        boolean crouching = state.hasPose(Pose.CROUCHING);
        if (GROUND_SINK_PX != 0.0F && !sleeping && !riding && !crouching && !swimmingPose) {
            // after scale(-1,-1,1) the local +Y points down; divide by RENDER_SCALE so the constant
            // means visible world pixels (1/16-block pixels), not units of the scaled frame
            matrices.translate(0.0F, GROUND_SINK_PX / 16.0F / LucarioModel.RENDER_SCALE * (1.0F - glideW), 0.0F);
        }
        if (riolu && riding) {
            matrices.translate(0.0F, -RIOLU_RIDE_LIFT / 16.0F, 0.0F);
        }

        Identifier texture = Variants.isShiny(variantKey) ? model.shinyTexture : model.texture;
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
        collector.submitModel(model.entityModel, state, matrices, texture, light, overlay, state.outlineColor);

        // held items -- in the same coordinate system as the model
        float itemScale = riolu ? HELD_ITEM_SCALE_RIOLU : (Variants.isMega(variantKey) ? HELD_ITEM_SCALE_MEGA : HELD_ITEM_SCALE_LUCARIO);
        float itemOffset = riolu ? HAND_ITEM_OFFSET_RIOLU : HAND_ITEM_OFFSET_LUCARIO;
        float itemInward = riolu ? HELD_ITEM_INWARD_RIOLU : HELD_ITEM_INWARD_LUCARIO;
        if (state instanceof ArmedEntityRenderState armed) {
            renderHeldItem(model, "hand_right", true, armed.rightHandItemState, itemScale, itemOffset, itemInward, matrices, collector, light, overlay, state.outlineColor);
            renderHeldItem(model, "hand_left", false, armed.leftHandItemState, itemScale, itemOffset, itemInward, matrices, collector, light, overlay, state.outlineColor);
        }
        // The elytra is drawn for everyone except morphs with their "own" back wings (the costume Lucario).
        if (access.lucario$hasElytra() && !Variants.hidesElytra(variantKey)) {
            renderElytra(model, riolu, Variants.isMega(variantKey), gliding, state.hasPose(Pose.CROUCHING), access.lucario$getElytraTexture(), state, matrices, collector, light, overlay);
        }
        // 1.8.1 prototype: the player's own Minecraft cape as a hanging cloth on the morph's back
        // (Lucario / Riolu / Mega alike) while THIS player's cape toggle is Yes - the live picker
        // toggle for the own morph, the flag published with the morph key ("_nocape") for other
        // players (1.8.1-pre8). Only when NO elytra is worn (the wings take the back) and the
        // account actually has a cape. The costume morphs (their own back wings) are excluded
        // like the elytra.
        Identifier capeTexture = access.lucario$getCapeTexture();
        if (access.lucario$capeVisible() && !access.lucario$hasElytra() && capeTexture != null
                && !Variants.hidesElytra(variantKey)) {
            renderCape(model, riolu, Variants.isMega(variantKey), state.hasPose(Pose.CROUCHING), capeTexture, state, matrices, collector, light, overlay);
        }
        matrices.popPose();
    }

    private static BedrockAnimation first(LucarioModel m, String... names) {
        for (String n : names) {
            BedrockAnimation a = m.animation(n);
            if (a != null) return a;
        }
        return null;
    }

    /**
     * Applies the animations/poses to the model's bones. A pure function of the state: called both at submit time
     * (so the held-item/elytra chains compute their matrices from fresh poses) and from the model's setupAnim --
     * the pipeline calls it when drawing the submit, so every player is drawn in their own pose.
     */
    static void pose(LucarioModel m, LivingEntityRenderState s, LucarioStateAccess access) {
        boolean sprinting = access.lucario$isSprinting();
        boolean water = s.isInWater;
        // Patch 11: death plays the "faint" animation (loop=false holds the last frame).
        // 1.6.1: Mega Lucario's pack ships it under the WIP name "battle_faintwip" — try both.
        BedrockAnimation faintAnim = first(m, "faint", "battle_faintwip");
        boolean dying = s.deathTime > 0.0F && faintAnim != null;
        boolean sleeping = !dying && s.hasPose(Pose.SLEEPING) && m.animation("sleep") != null;
        boolean gliding = !sleeping && !dying && access.lucario$isGliding();
        // swimming, crawling and gliding: both arms stretched forward (in the direction of travel)
        boolean armsForward = !sleeping && !dying && (gliding || s.hasPose(Pose.SWIMMING));
        // riding (boat, minecart, pig...): a sitting pose instead of the stance
        boolean riding = !sleeping && !dying && !gliding && access.lucario$isRiding();
        boolean crouching = !sleeping && !dying && !water && !riding && s.hasPose(Pose.CROUCHING);

        BedrockAnimation idle;
        BedrockAnimation move;
        if (dying) {
            idle = faintAnim;
            move = null;
        } else if (sleeping) {
            idle = m.animation("sleep");
            move = null;
        } else if (water) {
            idle = first(m, "water_idle", "ground_idle");
            move = first(m, "water_swim", "ground_walk");
        } else {
            idle = m.animation("ground_idle");
            move = sprinting && !crouching
                    ? first(m, "ground_run", "ground_runwip", "ground_walk")
                    : m.animation("ground_walk");
        }
        if (gliding) {
            // Gliding: body stretched along the direction of travel, legs behind keep stepping (like vanilla).
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
        float glideW = Math.clamp(access.lucario$getGlideBlend(), 0.0F, 1.0F);
        float danceW = Math.clamp(access.lucario$getDanceBlend(), 0.0F, 1.0F);
        // 1.7.0: the emote is per-player: the name and clock arrive in the render state (own --
        // from DanceState/PreviewState, other players' -- from RemoteEmotes via the sync service).
        // The T-pose (raw rig) is also carried in this field. null -- no emote.
        String emoteName = access.lucario$getEmoteAnim();
        boolean tpose = emoteName != null && PreviewState.TPOSE.equals(emoteName);
        BedrockAnimation dance = tpose || emoteName == null ? null : m.animation(emoteName);
        if (dying || sleeping || water || riding || (dance == null && !tpose)) {
            danceW = 0.0F;
        }

        // 1.6.1: the emote runs on its own clock (starts at the toggle, freezes on stop)
        // instead of the entity's global age, so it always begins from its first keyframe.
        // 1.7.0: the clock is per-player too (for remote players it starts when the event arrives).
        double danceTime = access.lucario$getEmoteTime();

        // Patch 11: battle stance lingers ~2s after attacking or being hurt.
        // 1.6.0: it now crossfades in/out and ALSO plays while walking — the legs
        // blend between the stance and the walk cycle, so you can move with it on.
        float battleW = Math.clamp(access.lucario$getBattleBlend(), 0.0F, 1.0F);
        // 1.6.1: Mega Lucario ships its stance under the WIP name "battle_idlewip" — try it first.
        BedrockAnimation battle = first(m, "battle_idlewip", "battle_idle");
        if (dying || sleeping || water || riding || armsForward || danceW > 0.0F || battle == null) {
            battleW = 0.0F;
        }

        if (danceW > 0.0F) {
            crouching = false;
        }

        double walkTime = (move != null && move.length > 0) ? phase * move.length : seconds;
        // 1.6.0: while gliding the legs crossfade from the walk cycle to a faster flutter
        double flightTime = (move != null && move.length > 0) ? seconds * FLIGHT_LEG_RATE % 1.0 * move.length : seconds;
        float w = move == null ? 0f : Math.clamp(s.walkAnimationSpeed * 1.5f, 0f, 1f);
        if (crouching) w *= CROUCH_WALK_BLEND;

        double blinkT = seconds % BLINK_PERIOD_SECONDS;
        boolean blinking = !dying && blink != null && blink.length > 0 && blinkT < blink.length;

        float headYaw = Math.clamp(s.yRot, -50.0f, 50.0f);
        float headPitch = Math.clamp(s.xRot, -45.0f, 45.0f);

        // arm swing: the shoulder swings forward and the torso twists slightly (like the vanilla player)
        float swing = access.lucario$getSwing();
        boolean swinging = swing > 0f && swing < 1f && !sleeping && !dying;
        // 1.6.4: "strike" marks a REAL attack (left click). A plain vanilla swing
        // (right-click item use) only moves the striking arm — no body twist,
        // chest lean, fist or off-arm backswing.
        boolean strike = swinging && access.lucario$isStrike();
        boolean swingRight = access.lucario$isSwingRight();
        String swingBone = swingRight ? "arm_right" : "arm_left";
        String offArmBone = swingRight ? "arm_left" : "arm_right";
        String twistBone = m.parts.containsKey("chest") ? "chest" : "torso";
        float swingPitch = 0f;
        float twist = 0f;
        float strikeArc = 0f;
        float followArc = 0f;
        if (swinging) {
            float f = 1.0f - swing;
            f *= f;
            f *= f;
            f = 1.0f - f;
            float g = Mth.sin(f * (float) Math.PI);
            float h = Mth.sin(swing * (float) Math.PI);
            // 1.6.0: the arm swing rides the vanilla swing bell curve (smooth in AND out),
            // works while walking, and fades out when the dance emote takes over.
            // 1.6.4: the arm raise plays for EVERY swing (vanilla-like hand twitch on
            // right-click use too); the dramatic strike extras below only on real attacks.
            float attackW = 1.0f - danceW;
            swingPitch = -(g * SWING_ARM_PITCH + h * 0.35f) * attackW;
            if (strike) {
                strikeArc = g * attackW;
                followArc = h * attackW;
                twist = Mth.sin(Mth.sqrt(swing) * (float) (Math.PI * 2.0)) * SWING_BODY_TWIST * attackW;
                if (!swingRight) twist = -twist;
            }
        }

        for (Map.Entry<String, ModelPart> e : m.parts.entrySet()) {
            String name = e.getKey();
            ModelPart part = e.getValue();
            float[] b = m.base.get(name);

            // arms forward: give the whole arm chain the rest pose without the swimming/walking animation
            boolean legChain = isLegChain(name);
            boolean armChain = armsForward && isArmChain(name);
            BedrockAnimation boneIdle = armChain ? armIdle : idle;
            // in flight the walk animation drives only the leg chain; otherwise, as usual
            float bw = !armChain && (!gliding || legChain) ? w : 0f;

            // base/idle pose — the battle stance crossfades over it below
            if (boneIdle != null) boneIdle.sample(name, seconds, R_IDLE, P_IDLE);
            else clear(R_IDLE, P_IDLE);
            // 1.6.0: smooth transition into the combat pose
            if (battleW > 0f) {
                battle.sample(name, seconds, R_AUX, P_AUX);
                lerp3(R_IDLE, P_IDLE, R_AUX, P_AUX, battleW);
            }

            // locomotion; while gliding the legs crossfade into the faster flutter phase
            if (move != null && bw > 0f) {
                double mt = glideW > 0.001f && legChain && move.length > 0
                        ? Mth.lerp(glideW, (float) walkTime, (float) flightTime)
                        : walkTime;
                move.sample(name, mt, R_MOVE, P_MOVE);
            } else clear(R_MOVE, P_MOVE);

            float rx = Mth.lerp(bw, R_IDLE[0], R_MOVE[0]);
            float ry = Mth.lerp(bw, R_IDLE[1], R_MOVE[1]);
            float rz = Mth.lerp(bw, R_IDLE[2], R_MOVE[2]);
            float px = Mth.lerp(bw, P_IDLE[0], P_MOVE[0]);
            float py = Mth.lerp(bw, P_IDLE[1], P_MOVE[1]);
            float pz = Mth.lerp(bw, P_IDLE[2], P_MOVE[2]);

            // 1.6.0: dance emote crossfades over everything and loops until toggled off.
            // 1.6.1: any preview animation (or the T-pose: bones reset to the bind pose).
            if (danceW > 0f) {
                if (tpose) {
                    clear(R_AUX, P_AUX);
                } else {
                    dance.sample(name, danceTime, R_AUX, P_AUX);
                }
                rx = Mth.lerp(danceW, rx, R_AUX[0]);
                ry = Mth.lerp(danceW, ry, R_AUX[1]);
                rz = Mth.lerp(danceW, rz, R_AUX[2]);
                px = Mth.lerp(danceW, px, P_AUX[0]);
                py = Mth.lerp(danceW, py, P_AUX[1]);
                pz = Mth.lerp(danceW, pz, P_AUX[2]);
            }

            if (blinking && blink.sample(name, blinkT, R_BLINK, P_BLINK)) {
                rx += R_BLINK[0]; ry += R_BLINK[1]; rz += R_BLINK[2];
                px += P_BLINK[0]; py += P_BLINK[1]; pz += P_BLINK[2];
            }

            float pitch = b[3] + (float) Math.toRadians(rx) * LucarioModel.ROT_SIGN_X;
            float yaw = b[4] + (float) Math.toRadians(ry) * LucarioModel.ROT_SIGN_Y;
            float roll = b[5] + (float) Math.toRadians(rz) * LucarioModel.ROT_SIGN_Z;

            if (name.equals("head") && !dying) {
                yaw += (float) Math.toRadians(headYaw);
                pitch += (float) Math.toRadians(headPitch);
            }

            if (crouching) {
                Float extra = m.crouchPitch.get(name);
                if (extra != null) pitch += extra;
                if (name.equals("body")) py -= m.crouchDrop; // Bedrock y points up: minus = lower
            }

            // sitting: the hips and knees unfold into straight, extended legs
            if (riding) {
                Float sit = m.sitPitch.get(name);
                if (sit != null) pitch += sit;
            }

            if (swinging && name.equals(twistBone)) {
                yaw += twist;
                // 1.6.0: chest leans into the strike
                pitch += SWING_BODY_LEAN * strikeArc;
            }

            // 1.6.0: the striking paw closes into a fist
            if (strikeArc != 0f && name.startsWith(swingRight ? "finger_right" : "finger_left")) {
                pitch += SWING_FINGER_CURL * strikeArc;
            }

            boolean shoulder = name.equals("arm_right") || name.equals("arm_left");
            if (shoulder) {
                float extraPitch = 0f;
                float extraRoll = 0f;
                if (armsForward) {
                    // 1.6.0: arms ease forward with the glide blend (crawling stays instant)
                    float forwardW = !gliding && s.hasPose(Pose.SWIMMING) ? 1.0f : glideW;
                    extraPitch += ARMS_FORWARD_PITCH * forwardW;
                    extraRoll = (name.equals("arm_right") ? ARMS_FORWARD_ROLL : -ARMS_FORWARD_ROLL) * forwardW;
                }
                if (swinging && name.equals(swingBone)) {
                    extraPitch += swingPitch;
                } else if (followArc != 0f && name.equals(offArmBone)) {
                    // 1.6.0: the free arm pulls back slightly for balance
                    extraPitch += OFF_ARM_BACKSWING * followArc;
                }
                if (extraPitch != 0f || extraRoll != 0f) {
                    // Lucario's arm rests "off to the side" (a Z rotation), so we swing it around the parent's X axis:
                    // compose the final rotation with a quaternion and convert it back to ModelPart angles.
                    Quaternionf q = new Quaternionf().rotationX(extraPitch)
                            .mul(new Quaternionf().rotationZ(extraRoll))
                            .mul(new Quaternionf().rotationZYX(roll, yaw, pitch));
                    Vector3f euler = q.getEulerAnglesZYX(new Vector3f());
                    pitch = euler.x;
                    yaw = euler.y;
                    roll = euler.z;
                }
            }

            // y points up in Bedrock and down in the Java model
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

    /** Reproduces what ModelPart.render does: moves the matrix into the bone's coordinate system. */
    private static void applyChain(LucarioModel m, String bone, PoseStack matrices) {
        for (String n : m.chainTo(bone)) {
            ModelPart p = m.parts.get(n);
            matrices.translate(p.x / 16.0F, p.y / 16.0F, p.z / 16.0F);
            if (p.xRot != 0.0F || p.yRot != 0.0F || p.zRot != 0.0F) {
                matrices.rotate(new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot));
            }
        }
    }

    // ------------------------------------------------------------------ held items

    /**
     * Draws an item in the hand (hand_right / hand_left). Walk the bone chain from the root to the hand, repeating what
     * ModelPart.render does, then orient the same way the vanilla item layer orients on Steve's arm:
     * "the arm points along +Y, forward is -Z". Lucario's arm at rest points along -X (right) / +X (left),
     * so we add a 90 degree rotation around Z.
     */
    private static void renderHeldItem(LucarioModel m, String handBone, boolean right, ItemStackRenderState item,
                                       float itemScale, float offsetUnits, float inwardUnits,
                                       PoseStack matrices, SubmitNodeCollector collector, int light, int overlay, int outlineColor) {
        if (item == null || item.isEmpty()) return;
        if (!m.parts.containsKey(handBone)) return;

        matrices.pushPose();
        // first, shift toward the body center in the whole model's coordinate system (right arm: +X, left: -X)
        matrices.translate((right ? 1.0F : -1.0F) * inwardUnits / 16.0F, 0.0F, 0.0F);
        applyChain(m, handBone, matrices);
        // from the wrist down the arm toward the palm
        matrices.translate((right ? -1.0F : 1.0F) * offsetUnits / 16.0F, 0.0F, 0.0F);
        // restore the item's normal size (the model is scaled by RENDER_SCALE) and shrink it to the character's size
        float k = itemScale / LucarioModel.RENDER_SCALE;
        matrices.scale(k, k, k);
        matrices.rotateDegrees(Axis.ZP, right ? 90.0F : -90.0F);
        // from here on, as in the vanilla item layer
        matrices.rotateDegrees(Axis.XP, -90.0F);
        matrices.rotateDegrees(Axis.YP, 180.0F);
        matrices.translate(0.0F, 0.125F, 0.0F);
        item.submit(matrices, collector, light, overlay, outlineColor);
        matrices.popPose();
    }

    // ------------------------------------------------------------------ elytra

    /** Elytra wings on the back (vanilla geometry and texture); they spread open in flight.
     *  The wing bones come from the model's own set (m.elytraLeft/Right), so two players
     *  wearing elytra don't have their wing poses "bleed" into each other either. */
    private static void renderElytra(LucarioModel m, boolean riolu, boolean mega, boolean gliding, boolean crouching, Identifier texture,
                                     LivingEntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, int light, int overlay) {
        String attach = m.parts.containsKey("chest") ? "chest" : "torso";
        if (!m.parts.containsKey(attach)) return;

        float pitch = 0.2617994F, roll = -0.2617994F, yaw = 0.0F, y0 = 0.0F;
        if (gliding) {
            pitch = 0.34906584F;
            roll = (float) (-Math.PI / 2.0);
        } else if (crouching) {
            pitch = 0.6981317F;
            roll = (float) (-Math.PI / 4.0);
            y0 = 3.0F;
            yaw = 0.08726646F;
        }
        m.elytraLeft.setPos(5.0F, y0, 0.0F);
        m.elytraLeft.setRotation(pitch, yaw, roll);
        m.elytraRight.setPos(-5.0F, y0, 0.0F);
        m.elytraRight.setRotation(pitch, -yaw, -roll);

        matrices.pushPose();
        applyChain(m, attach, matrices);
        matrices.translate(0.0F, -(riolu ? ELYTRA_UP_RIOLU : ELYTRA_UP_LUCARIO) / 16.0F,
                (riolu ? ELYTRA_BACK_RIOLU : mega ? ELYTRA_BACK_MEGA : ELYTRA_BACK_LUCARIO) / 16.0F);
        float k = (riolu ? ELYTRA_SCALE_RIOLU : ELYTRA_SCALE_LUCARIO) / LucarioModel.RENDER_SCALE;
        matrices.scale(k, k, k);
        matrices.translate(0.0F, 0.0F, 0.125F);
        collector.submitModel(m.elytraModel, state, matrices, texture != null ? texture : ELYTRA_TEXTURE, light, overlay, state.outlineColor);
        matrices.popPose();
    }

    // ------------------------------------------------------------------ cape

    /** The player's own cape drawn as a hanging cloth on the morph's back (1.8.1 prototype).
     *  The cloth hangs down and back from the pivot at its top edge; a slight tilt drapes the
     *  hem away from the body (more while sneaking; Mega Lucario drapes more still, so the
     *  hem clears its chest fur). */
    private static void renderCape(LucarioModel m, boolean riolu, boolean mega, boolean crouching, Identifier texture,
                                   LivingEntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, int light, int overlay) {
        String attach = m.parts.containsKey("chest") ? "chest" : "torso";
        if (!m.parts.containsKey(attach)) return;

        m.cape.setPos(0.0F, 0.0F, 0.0F);

        // Cloth motion (1.8.1): while moving, the cloth billows gently BEHIND the back -- the hem
        // lifts off and settles back onto its tilt at the stride frequency (a soft two-harmonic
        // flutter). No side-to-side swing: the cloth stays in one plane. Standing still (walk
        // weight 0) it just hangs on its tilt.
        float walkW = Math.clamp(state.walkAnimationSpeed * 1.5f, 0f, 1f);
        double phase = state.walkAnimationPos / STRIDE_CYCLE;
        float flap = (float) (Math.sin(phase * 2.0 * Math.PI) * 0.7
                + Math.sin(phase * 4.0 * Math.PI + 1.3) * 0.3);
        float tilt = crouching ? CAPE_TILT_CROUCH
                : (mega ? CAPE_TILT_MEGA : riolu ? CAPE_TILT_RIOLU : CAPE_TILT_LUCARIO)
                + CAPE_TILT_RUN * walkW + flap * CAPE_FLAP * walkW;

        matrices.pushPose();
        applyChain(m, attach, matrices);
        matrices.translate(0.0F, -(riolu ? CAPE_UP_RIOLU : CAPE_UP_LUCARIO) / 16.0F,
                (riolu ? CAPE_BACK_RIOLU : mega ? CAPE_BACK_MEGA : CAPE_BACK_LUCARIO) / 16.0F);
        // As in the vanilla cape layer: flip the cloth 180 degrees around Y. The cloth ends up
        // extending BACKWARD from the pivot and its design face looks AWAY from the body;
        // without the flip the cape renders inside-out (design toward the spine, i.e. it
        // hangs "back to front").
        matrices.rotateDegrees(Axis.YP, 180.0F);
        // The Y-flip also inverts part-space xRot, so the tilt constants are applied negated:
        // a positive xRot would press the hem INTO the back instead of draping it away.
        // The billow is folded into the tilt, so the hem only ever moves back-and-forth.
        m.cape.setRotation(-tilt, 0.0F, 0.0F);
        float k = (riolu ? CAPE_SCALE_RIOLU : CAPE_SCALE_LUCARIO) / LucarioModel.RENDER_SCALE;
        matrices.scale(k, k, k);
        collector.submitModel(m.capeModel, state, matrices, texture, light, overlay, state.outlineColor);
        matrices.popPose();
    }

    // ------------------------------------------------------------------ first-person arm

    /**
     * Called instead of the vanilla renderRightHand/renderLeftHand. At this point the matrix is already set so Steve's
     * arm (shoulder at (-5, 2, 0) / (5, 2, 0) pixels) lies correctly with view bobbing, arm swing, etc. taken into account.
     * We put Lucario's shoulder at the same point and submit only the arm chain (shoulder -> forearm -> hand -> fingers).
     */
    public static void renderFirstPersonArm(PoseStack matrices, SubmitNodeCollector collector, int light,
                                            String variantKey, boolean right) {
        // A separate instance (not the player's personal model): the arm bones don't overlap anyone else's submits;
        // the pose is set here and read when this same arm is drawn.
        LucarioModel m = LucarioModels.getFirstPerson(Variants.baseOf(variantKey));
        if (m == null) return;
        ModelPart shoulder = m.parts.get(right ? "arm_right" : "arm_left");
        if (shoulder == null) return;

        poseIdle(m, System.nanoTime() / 1.0e9);
        shoulder.setPos(0.0F, 0.0F, 0.0F);
        float roll = (float) Math.toRadians(FIRST_PERSON_ARM_ROLL_DEG) * (right ? -1.0F : 1.0F);
        shoulder.setRotation(shoulder.xRot, shoulder.yRot, shoulder.zRot + roll);

        Identifier texture = Variants.isShiny(variantKey) ? m.shinyTexture : m.texture;

        matrices.pushPose();
        matrices.translate((right ? -5.0F : 5.0F) / 16.0F, 2.0F / 16.0F, 0.0F);
        float s = LucarioModel.RENDER_SCALE * FIRST_PERSON_ARM_SCALE;
        matrices.scale(s, s, s);
        collector.submitModelPart(shoulder, matrices, RenderTypes.entityCutout(texture), light, OverlayTexture.NO_OVERLAY, null);
        matrices.popPose();
    }

    /** Rest pose (ground_idle) without movement or blinking -- for the first-person arm. */
    private static void poseIdle(LucarioModel m, double seconds) {
        BedrockAnimation idle = m.animation("ground_idle");
        for (Map.Entry<String, ModelPart> e : m.parts.entrySet()) {
            String name = e.getKey();
            ModelPart part = e.getValue();
            float[] b = m.base.get(name);
            if (idle != null) idle.sample(name, seconds, R_IDLE, P_IDLE);
            else clear(R_IDLE, P_IDLE);
            part.setPos(b[0] + P_IDLE[0], b[1] - P_IDLE[1], b[2] + P_IDLE[2]);
            part.setRotation(
                    b[3] + (float) Math.toRadians(R_IDLE[0]) * LucarioModel.ROT_SIGN_X,
                    b[4] + (float) Math.toRadians(R_IDLE[1]) * LucarioModel.ROT_SIGN_Y,
                    b[5] + (float) Math.toRadians(R_IDLE[2]) * LucarioModel.ROT_SIGN_Z);
        }
    }

    private static void clear(float[] r, float[] p) {
        r[0] = r[1] = r[2] = 0;
        p[0] = p[1] = p[2] = 0;
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
