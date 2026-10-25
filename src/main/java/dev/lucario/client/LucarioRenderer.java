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
 * Рисует Лукарио/Риолу вместо ванильной модели игрока: поза из Bedrock-анимаций + поворот головы,
 * плюс то, чего в анимациях нет: приседание, взмах рукой (удар/ломание блока), предметы в руках и рука от первого лица.
 * В 26.x рендер сабмит-пайплайн: вместо прямого рисования через VertexConsumerProvider мы ставим
 * позы костям и отправляем модель через SubmitNodeCollector.submitModel / submitModelPart / ItemStackRenderState.submit.
 */
@Environment(EnvType.CLIENT)
public final class LucarioRenderer {
    /** Один полный цикл шага у ванильных ног: cos(limbAngle * 0.6662). */
    private static final double STRIDE_CYCLE = 2.0 * Math.PI / 0.6662;
    private static final double BLINK_PERIOD_SECONDS = 4.0;

    // ---- настройки (можно подкрутить под вкус) ----
    /** Во сколько раз рука от первого лица крупнее, чем "честный" масштаб модели (рука Лукарио тоньше руки Стива). */
    private static final float FIRST_PERSON_ARM_SCALE = 1.8f;
    /** Насколько градусов рука от первого лица прижата вниз/к телу относительно позы покоя (убирает "разлёт" локтей). */
    private static final float FIRST_PERSON_ARM_ROLL_DEG = 20f;
    /** Смещение предмета от запястья вдоль руки (вниз), в единицах модели Bedrock: Лукарио/Мега, Риолу. */
    private static final float HAND_ITEM_OFFSET_LUCARIO = 4.5f;
    private static final float HAND_ITEM_OFFSET_RIOLU = 3.0f;
    /** Размер предмета в руке относительно обычного (1.0 = как у Стива). */
    private static final float HELD_ITEM_SCALE_LUCARIO = 0.6f;
    private static final float HELD_ITEM_SCALE_MEGA = 0.55f;
    private static final float HELD_ITEM_SCALE_RIOLU = 0.45f;
    /** Сдвиг предмета к телу (к центру модели), в единицах Bedrock: Лукарио/Мега, Риолу. */
    private static final float HELD_ITEM_INWARD_LUCARIO = 2.5f;
    private static final float HELD_ITEM_INWARD_RIOLU = 1.5f;
    /** Размер крыльев элитр относительно обычного и положение на спине (единицы Bedrock от кости груди). */
    private static final float ELYTRA_SCALE_LUCARIO = 0.55f;
    private static final float ELYTRA_SCALE_RIOLU = 0.33f;
    private static final float ELYTRA_UP_LUCARIO = 4.0f, ELYTRA_BACK_LUCARIO = 1.5f;
    private static final float ELYTRA_BACK_MEGA = 1.7f;
    private static final float ELYTRA_UP_RIOLU = 3.0f, ELYTRA_BACK_RIOLU = 0.65f;
    /** Руки вперёд при плавании/ползании/полёте: поворот плеча (рад) и сведение рук к оси тела. */
    private static final float ARMS_FORWARD_PITCH = -3.0f;
    private static final float ARMS_FORWARD_ROLL = 0.25f; // рад, разведение рук в стороны (чтобы не пересекали голову)
    /** Насколько в приседе медленнее машут ногами при ходьбе (доля от обычного взмаха). */
    private static final float CROUCH_WALK_BLEND = 0.43f;
    /** Циклов шага в секунду у ног во время полёта на элитрах. */
    private static final double FLIGHT_LEG_RATE = 1.25;
    /** На сколько единиц Bedrock Риолу приподнят в лодке/вагонетке и т.п. (иначе выглядывает из сиденья едва ли). */
    private static final float RIOLU_RIDE_LIFT = 3.0f;
    /** На сколько пикселей (1/16 блока) опустить модель в "наземных" позах (стоя/ходьба),
     *  чтобы подошвы не "левитировали" над землёй. Поднимите значение, если модель всё ещё парит,
     *  уменьшите или сделайте 0, если ступни начали утопать. В приседе не применяется — там своя
     *  точная посадка (crouchDrop), а общий сдвиг топил сложенные ступни в землю. */
    private static final float GROUND_SINK_PX = 0.5f;

    private static final Identifier ELYTRA_TEXTURE = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
    /** Размах руки при ударе (рад) и скручивание корпуса. */
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

    // ------------------------------------------------------------------ третье лицо / другие игроки

    public static void render(LivingEntityRenderState state, LucarioStateAccess access,
                              PoseStack matrices, SubmitNodeCollector collector, int light) {
        if (state.isInvisible) return;
        String variantKey = access.lucario$getVariant();
        // Модель своя у каждого игрока (1.4.3): у одинаковых образов больше нет общих костей,
        // и поза одного не переписывает позу другого при отложенной отрисовке сабмитов.
        LucarioModel model = access.lucario$getModel();
        if (model == null) return;

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
            float angle = (state.isInWater || gliding) ? -90.0F - state.xRot : -90.0F;
            matrices.rotateDegrees(Axis.XP, angle);
            matrices.translate(0.0F, -1.0F, 0.3F);
        } else if (glideW > 0.001f) {
            matrices.rotateDegrees(Axis.XP, (-90.0F - state.xRot) * glideW);
            matrices.translate(0.0F, -1.0F * glideW, 0.3F * glideW);
        }
        matrices.scale(-1.0F, -1.0F, 1.0F);
        matrices.scale(LucarioModel.RENDER_SCALE, LucarioModel.RENDER_SCALE, LucarioModel.RENDER_SCALE);
        // y_java = 24 - y_bedrock; подошва в y_bedrock = feetY должна оказаться на земле
        matrices.translate(0.0F, -(24.0F - model.feetY) / 16.0F, 0.0F);
        // Риолу в транспорте (лодка, вагонетка и т.д.) сидит чуть выше, чтобы не тонусть в сиденье
        boolean riding = !state.hasPose(Pose.SLEEPING) && !gliding && access.lucario$isRiding();
        // "Наземные" позы (стоя/ходьба): чуть опускаем модель, чтобы не левитировала над землёй.
        // В плавании модель повёрнута горизонтально, в транспорте своя посадка, а в приседе
        // crouchDrop уже сажает подошвы точно на землю — лишний сдвиг топил ноги в землю.
        // 1.6.0: в полёте сдвиг плавно уходит вместе с glide-переходом (умножен на 1 - glideW).
        boolean sleeping = state.hasPose(Pose.SLEEPING);
        boolean crouching = state.hasPose(Pose.CROUCHING);
        if (GROUND_SINK_PX != 0.0F && !sleeping && !riding && !crouching && !swimmingPose) {
            // после scale(-1,-1,1) локальный +Y смотрит вниз; делим на RENDER_SCALE, чтобы константа
            // означала видимые пиксели мира (1/16 блока), а не единицы масштабированного фрейма
            matrices.translate(0.0F, GROUND_SINK_PX / 16.0F / LucarioModel.RENDER_SCALE * (1.0F - glideW), 0.0F);
        }
        if (riolu && riding) {
            matrices.translate(0.0F, -RIOLU_RIDE_LIFT / 16.0F, 0.0F);
        }

        Identifier texture = Variants.isShiny(variantKey) ? model.shinyTexture : model.texture;
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
        collector.submitModel(model.entityModel, state, matrices, texture, light, overlay, state.outlineColor);

        // предметы в руках — в той же системе координат, что и модель
        float itemScale = riolu ? HELD_ITEM_SCALE_RIOLU : (Variants.isMega(variantKey) ? HELD_ITEM_SCALE_MEGA : HELD_ITEM_SCALE_LUCARIO);
        float itemOffset = riolu ? HAND_ITEM_OFFSET_RIOLU : HAND_ITEM_OFFSET_LUCARIO;
        float itemInward = riolu ? HELD_ITEM_INWARD_RIOLU : HELD_ITEM_INWARD_LUCARIO;
        if (state instanceof ArmedEntityRenderState armed) {
            renderHeldItem(model, "hand_right", true, armed.rightHandItemState, itemScale, itemOffset, itemInward, matrices, collector, light, overlay, state.outlineColor);
            renderHeldItem(model, "hand_left", false, armed.leftHandItemState, itemScale, itemOffset, itemInward, matrices, collector, light, overlay, state.outlineColor);
        }
        // Элитры рисуем всем, кроме образов со "своими" крыльями на спине (костюмный Лукарио).
        if (access.lucario$hasElytra() && !Variants.hidesElytra(variantKey)) {
            renderElytra(model, riolu, Variants.isMega(variantKey), gliding, state.hasPose(Pose.CROUCHING), access.lucario$getElytraTexture(), state, matrices, collector, light, overlay);
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
     * Раскладывает анимации/позы по костям модели. Чистая функция от состояния: вызывается и на сабмите
     * (чтобы цепочки предметов/элитр посчитали матрицы по свежим позам), и из setupAnim модели —
     * пайплайн вызывает его при отрисовке сабмита, поэтому каждый игрок рисуется в своей позе.
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
        // плавание, ползание и полёт: обе руки вытянуты вперёд (по ходу движения)
        boolean armsForward = !sleeping && !dying && (gliding || s.hasPose(Pose.SWIMMING));
        // поездка верхом (лодка, вагонетка, свинья...): сидячая поза вместо стойки
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
            // Полёт: тело вытянуто по ходу движения, ноги сзади продолжают перебирать (как в ванилле)
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
        // 1.7.0: эмоут — пер-игроковый: имя и часы приходят в состоянии рендера (свой —
        // из DanceState/PreviewState, чужие — из RemoteEmotes через сервис синхронизации).
        // T-поза (сырой риг) тоже едет в этом поле. null — эмоута нет.
        String emoteName = access.lucario$getEmoteAnim();
        boolean tpose = emoteName != null && PreviewState.TPOSE.equals(emoteName);
        BedrockAnimation dance = tpose || emoteName == null ? null : m.animation(emoteName);
        if (dying || sleeping || water || riding || (dance == null && !tpose)) {
            danceW = 0.0F;
        }

        // 1.6.1: the emote runs on its own clock (starts at the toggle, freezes on stop)
        // instead of the entity's global age, so it always begins from its first keyframe.
        // 1.7.0: часы — тоже пер-игроковые (у чужих стартуют в момент получения события).
        double danceTime = access.lucario$getEmoteTime();

        // Patch 11: battle stance lingers ~2s after attacking or being hurt.
        // 1.6.0: it now crossfades in/out and ALSO plays while walking — the legs
        // blend between the stance and the walk cycle, so you can move with it on.
        float battleW = Mth.clamp(access.lucario$getBattleBlend(), 0.0F, 1.0F);
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
        float w = move == null ? 0f : Mth.clamp(s.walkAnimationSpeed * 1.5f, 0f, 1f);
        if (crouching) w *= CROUCH_WALK_BLEND;

        double blinkT = seconds % BLINK_PERIOD_SECONDS;
        boolean blinking = !dying && blink != null && blink.length > 0 && blinkT < blink.length;

        float headYaw = Mth.clamp(s.yRot, -50.0f, 50.0f);
        float headPitch = Mth.clamp(s.xRot, -45.0f, 45.0f);

        // взмах рукой: плечо машет вперёд, корпус слегка скручивается (как у ванильного игрока)
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

            // руки вперёд: берём для всей цепочки руки позу покоя без анимации плавания/ходьбы
            boolean legChain = isLegChain(name);
            boolean armChain = armsForward && isArmChain(name);
            BedrockAnimation boneIdle = armChain ? armIdle : idle;
            // в полёте анимация шага двигает только цепочку ног, в остальном — как обычно
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
                if (name.equals("body")) py -= m.crouchDrop; // y у Bedrock вверх: минус = ниже
            }

            // сидя: бёдра и колени раскладываются в прямые вытянутые ноги
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
                    // Рука у Лукарио лежит "в стороне" (поворот по Z), поэтому качаем её вокруг оси X родителя:
                    // собираем итоговый поворот кватернионом и переводим обратно в углы ModelPart.
                    Quaternionf q = new Quaternionf().rotationX(extraPitch)
                            .mul(new Quaternionf().rotationZ(extraRoll))
                            .mul(new Quaternionf().rotationZYX(roll, yaw, pitch));
                    Vector3f euler = q.getEulerAnglesZYX(new Vector3f());
                    pitch = euler.x;
                    yaw = euler.y;
                    roll = euler.z;
                }
            }

            // y у Bedrock направлен вверх, у Java-модели — вниз
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

    /** Повторяет то, что делает ModelPart.render: переносит матрицу в систему координат кости. */
    private static void applyChain(LucarioModel m, String bone, PoseStack matrices) {
        for (String n : m.chainTo(bone)) {
            ModelPart p = m.parts.get(n);
            matrices.translate(p.x / 16.0F, p.y / 16.0F, p.z / 16.0F);
            if (p.xRot != 0.0F || p.yRot != 0.0F || p.zRot != 0.0F) {
                matrices.rotate(new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot));
            }
        }
    }

    // ------------------------------------------------------------------ предметы в руках

    /**
     * Рисует предмет в кисти (hand_right / hand_left). Идём по цепочке костей от корня до кисти, повторяя то, что
     * делает ModelPart.render, а дальше встаём так же, как ванильный слой предметов встаёт на руку Стива:
     * "рука смотрит вдоль +Y, вперёд — минус Z". У Лукарио рука в покое смотрит вдоль -X (правая) / +X (левая),
     * поэтому добавляем поворот на 90 градусов вокруг Z.
     */
    private static void renderHeldItem(LucarioModel m, String handBone, boolean right, ItemStackRenderState item,
                                       float itemScale, float offsetUnits, float inwardUnits,
                                       PoseStack matrices, SubmitNodeCollector collector, int light, int overlay, int outlineColor) {
        if (item == null || item.isEmpty()) return;
        if (!m.parts.containsKey(handBone)) return;

        matrices.pushPose();
        // сначала сдвиг в системе координат всей модели: к центру тела (правая рука — в сторону +X, левая — в -X)
        matrices.translate((right ? 1.0F : -1.0F) * inwardUnits / 16.0F, 0.0F, 0.0F);
        applyChain(m, handBone, matrices);
        // от запястья вниз по руке к ладони
        matrices.translate((right ? -1.0F : 1.0F) * offsetUnits / 16.0F, 0.0F, 0.0F);
        // вернуть предмету обычный размер (модель масштабирована RENDER_SCALE) и уменьшить под размер персонажа
        float k = itemScale / LucarioModel.RENDER_SCALE;
        matrices.scale(k, k, k);
        matrices.rotateDegrees(Axis.ZP, right ? 90.0F : -90.0F);
        // дальше — как в ванильном слое предметов
        matrices.rotateDegrees(Axis.XP, -90.0F);
        matrices.rotateDegrees(Axis.YP, 180.0F);
        matrices.translate(0.0F, 0.125F, 0.0F);
        item.submit(matrices, collector, light, overlay, outlineColor);
        matrices.popPose();
    }

    // ------------------------------------------------------------------ элитры

    /** Крылья элитр на спине (геометрия и текстура ванильные); при полёте раскрываются.
     *  Кости крыльев — из личного набора модели (m.elytraLeft/Right), чтобы у двух игроков
     *  в элитрах позы крыльев тоже не "перетекали" друг в друга. */
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

    // ------------------------------------------------------------------ рука от первого лица

    /**
     * Вызывается вместо ванильного renderRightHand/renderLeftHand. В этот момент матрица уже стоит так, что рука Стива
     * (с плечом в точке (-5, 2, 0) / (5, 2, 0) пикселей) лежит правильно с учётом качания, взмаха и т.п.
     * Мы ставим плечо Лукарио в ту же точку и сабмитим только цепочку руки (плечо -> предплечье -> кисть -> пальцы).
     */
    public static void renderFirstPersonArm(PoseStack matrices, SubmitNodeCollector collector, int light,
                                            String variantKey, boolean right) {
        // Отдельный экземпляр (не личная модель игрока): кости руки не пересекаются ни с чьими сабмитами,
        // поза ставится здесь и читается при отрисовке этой же руки.
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

    /** Поза покоя (ground_idle) без движения и мигания — для руки от первого лица. */
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
