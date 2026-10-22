package dev.lucario.client;

import dev.lucario.Variants;
import dev.lucario.client.anim.BedrockAnimation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Рисует Лукарио/Риолу вместо ванильной модели игрока: поза из Bedrock-анимаций + поворот головы,
 * плюс то, чего в анимациях нет: приседание, взмах рукой (удар/ломание блока), предметы в руках и рука от первого лица.
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

    private static final Identifier ELYTRA_TEXTURE = Identifier.ofVanilla("textures/entity/equipment/wings/elytra.png");
    private static ModelPart elytraLeft;
    private static ModelPart elytraRight;
    /** Размах руки при ударе (рад) и скручивание корпуса. */
    private static final float SWING_ARM_PITCH = 1.2f;
    private static final float SWING_BODY_TWIST = 0.2f;

    private static final float[] R_IDLE = new float[3], P_IDLE = new float[3];
    private static final float[] R_MOVE = new float[3], P_MOVE = new float[3];
    private static final float[] R_BLINK = new float[3], P_BLINK = new float[3];

    private LucarioRenderer() {}

    // ------------------------------------------------------------------ третье лицо / другие игроки

    public static void render(LivingEntityRenderState state, LucarioStateAccess access,
                              MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        if (state.invisible) return;
        String variantKey = access.lucario$getVariant();
        LucarioModel model = LucarioModels.get(Variants.baseOf(variantKey));
        if (model == null) return;

        boolean riolu = Variants.isRiolu(variantKey);
        pose(model, state, access);

        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - state.bodyYaw));
        boolean gliding = access.lucario$isGliding();
        if (state.isInPose(EntityPose.SWIMMING) || gliding) {
            float angle = (state.touchingWater || gliding) ? -90.0F - state.pitch : -90.0F;
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle));
            matrices.translate(0.0F, -1.0F, 0.3F);
        }
        matrices.scale(-1.0F, -1.0F, 1.0F);
        matrices.scale(LucarioModel.RENDER_SCALE, LucarioModel.RENDER_SCALE, LucarioModel.RENDER_SCALE);
        // y_java = 24 - y_bedrock; подошва в y_bedrock = feetY должна оказаться на земле
        matrices.translate(0.0F, -(24.0F - model.feetY) / 16.0F, 0.0F);
        // Риолу в транспорте (лодка, вагонетка и т.д.) сидит чуть выше, чтобы не тонусть в сиденье
        boolean riding = !state.isInPose(EntityPose.SLEEPING) && !gliding && access.lucario$isRiding();
        if (riolu && riding) {
            matrices.translate(0.0F, -RIOLU_RIDE_LIFT / 16.0F, 0.0F);
        }

        RenderLayer layer = RenderLayer.getEntityCutoutNoCull(
                Variants.isShiny(variantKey) ? model.shinyTexture : model.texture);
        VertexConsumer vc = vertexConsumers.getBuffer(layer);
        int overlay = LivingEntityRenderer.getOverlay(state, 0.0F);
        model.root.render(matrices, vc, light, overlay, -1);

        // предметы в руках — в той же системе координат, что и модель
        float itemScale = riolu ? HELD_ITEM_SCALE_RIOLU : (Variants.isMega(variantKey) ? HELD_ITEM_SCALE_MEGA : HELD_ITEM_SCALE_LUCARIO);
        float itemOffset = riolu ? HAND_ITEM_OFFSET_RIOLU : HAND_ITEM_OFFSET_LUCARIO;
        float itemInward = riolu ? HELD_ITEM_INWARD_RIOLU : HELD_ITEM_INWARD_LUCARIO;
        if (state instanceof ArmedEntityRenderState armed) {
            renderHeldItem(model, "hand_right", true, armed.rightHandItemState, itemScale, itemOffset, itemInward, matrices, vertexConsumers, light, overlay);
            renderHeldItem(model, "hand_left", false, armed.leftHandItemState, itemScale, itemOffset, itemInward, matrices, vertexConsumers, light, overlay);
        }
        if (access.lucario$hasElytra()) {
            renderElytra(model, riolu, Variants.isMega(variantKey), gliding, state.isInPose(EntityPose.CROUCHING), access.lucario$getElytraTexture(), matrices, vertexConsumers, light, overlay);
        }
        matrices.pop();
    }

    private static BedrockAnimation first(LucarioModel m, String... names) {
        for (String n : names) {
            BedrockAnimation a = m.animation(n);
            if (a != null) return a;
        }
        return null;
    }

    private static void pose(LucarioModel m, LivingEntityRenderState s, LucarioStateAccess access) {
        boolean sprinting = access.lucario$isSprinting();
        boolean water = s.touchingWater;
        boolean sleeping = s.isInPose(EntityPose.SLEEPING) && m.animation("sleep") != null;
        boolean gliding = !sleeping && access.lucario$isGliding();
        // плавание, ползание и полёт: обе руки вытянуты вперёд (по ходу движения)
        boolean armsForward = !sleeping && (gliding || s.isInPose(EntityPose.SWIMMING));
        // поездка верхом (лодка, вагонетка, свинья...): сидячая поза вместо стойки
        boolean riding = !sleeping && !gliding && access.lucario$isRiding();
        boolean crouching = !sleeping && !water && !riding && s.isInPose(EntityPose.CROUCHING);

        BedrockAnimation idle;
        BedrockAnimation move;
        if (sleeping) {
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

        double seconds = s.age / 20.0;

        double phase = s.limbSwingAnimationProgress / STRIDE_CYCLE;
        phase -= Math.floor(phase);
        if (gliding) {
            // в полёте ноги машут с собственной частотой (скорость игрока на фазу не влияет)
            phase = seconds * FLIGHT_LEG_RATE % 1.0;
        }
        double moveTime = (move != null && move.length > 0) ? phase * move.length : seconds;
        float w = move == null ? 0f : MathHelper.clamp(s.limbSwingAmplitude * 1.5f, 0f, 1f);
        if (crouching) w *= CROUCH_WALK_BLEND;

        double blinkT = seconds % BLINK_PERIOD_SECONDS;
        boolean blinking = blink != null && blink.length > 0 && blinkT < blink.length;

        float headYaw = MathHelper.clamp(s.relativeHeadYaw, -50.0f, 50.0f);
        float headPitch = MathHelper.clamp(s.pitch, -45.0f, 45.0f);

        // взмах рукой: плечо машет вперёд, корпус слегка скручивается (как у ванильного игрока)
        float swing = access.lucario$getSwing();
        boolean swinging = swing > 0f && swing < 1f && !sleeping;
        boolean swingRight = access.lucario$isSwingRight();
        String swingBone = swingRight ? "arm_right" : "arm_left";
        String twistBone = m.parts.containsKey("chest") ? "chest" : "torso";
        float swingPitch = 0f;
        float twist = 0f;
        if (swinging) {
            float f = 1.0f - swing;
            f *= f;
            f *= f;
            f = 1.0f - f;
            float g = MathHelper.sin(f * (float) Math.PI);
            float h = MathHelper.sin(swing * (float) Math.PI);
            swingPitch = -(g * SWING_ARM_PITCH + h * 0.35f);
            twist = MathHelper.sin(MathHelper.sqrt(swing) * (float) (Math.PI * 2.0)) * SWING_BODY_TWIST;
            if (!swingRight) twist = -twist;
        }

        for (Map.Entry<String, ModelPart> e : m.parts.entrySet()) {
            String name = e.getKey();
            ModelPart part = e.getValue();
            float[] b = m.base.get(name);

            // руки вперёд: берём для всей цепочки руки позу покоя без анимации плавания/ходьбы
            boolean armChain = armsForward && isArmChain(name);
            BedrockAnimation boneIdle = armChain ? armIdle : idle;
            // в полёте анимация шага двигает только цепочку ног, в остальном — как обычно
            float bw = !armChain && (!gliding || isLegChain(name)) ? w : 0f;

            if (boneIdle != null) boneIdle.sample(name, seconds, R_IDLE, P_IDLE);
            else clear(R_IDLE, P_IDLE);
            if (move != null && bw > 0f) move.sample(name, moveTime, R_MOVE, P_MOVE);
            else clear(R_MOVE, P_MOVE);

            float rx = MathHelper.lerp(bw, R_IDLE[0], R_MOVE[0]);
            float ry = MathHelper.lerp(bw, R_IDLE[1], R_MOVE[1]);
            float rz = MathHelper.lerp(bw, R_IDLE[2], R_MOVE[2]);
            float px = MathHelper.lerp(bw, P_IDLE[0], P_MOVE[0]);
            float py = MathHelper.lerp(bw, P_IDLE[1], P_MOVE[1]);
            float pz = MathHelper.lerp(bw, P_IDLE[2], P_MOVE[2]);

            if (blinking && blink.sample(name, blinkT, R_BLINK, P_BLINK)) {
                rx += R_BLINK[0]; ry += R_BLINK[1]; rz += R_BLINK[2];
                px += P_BLINK[0]; py += P_BLINK[1]; pz += P_BLINK[2];
            }

            float pitch = b[3] + (float) Math.toRadians(rx) * LucarioModel.ROT_SIGN_X;
            float yaw = b[4] + (float) Math.toRadians(ry) * LucarioModel.ROT_SIGN_Y;
            float roll = b[5] + (float) Math.toRadians(rz) * LucarioModel.ROT_SIGN_Z;

            if (name.equals("head")) {
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

            if (swinging && name.equals(twistBone)) yaw += twist;

            boolean shoulder = name.equals("arm_right") || name.equals("arm_left");
            if (shoulder) {
                float extraPitch = 0f;
                float extraRoll = 0f;
                if (armsForward) {
                    extraPitch += ARMS_FORWARD_PITCH;
                    extraRoll = name.equals("arm_right") ? ARMS_FORWARD_ROLL : -ARMS_FORWARD_ROLL;
                }
                if (swinging && name.equals(swingBone)) extraPitch += swingPitch;
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
            part.setOrigin(b[0] + px, b[1] - py, b[2] + pz);
            part.setAngles(pitch, yaw, roll);
        }
    }

    private static boolean isArmChain(String n) {
        return n.startsWith("arm_") || n.startsWith("hand_") || n.startsWith("finger_");
    }

    private static boolean isLegChain(String n) {
        return n.startsWith("leg_") || n.startsWith("foot_");
    }

    /** Повторяет то, что делает ModelPart.render: переносит матрицу в систему координат кости. */
    private static void applyChain(LucarioModel m, String bone, MatrixStack matrices) {
        for (String n : m.chainTo(bone)) {
            ModelPart p = m.parts.get(n);
            matrices.translate(p.originX / 16.0F, p.originY / 16.0F, p.originZ / 16.0F);
            if (p.pitch != 0.0F || p.yaw != 0.0F || p.roll != 0.0F) {
                matrices.multiply(new Quaternionf().rotationZYX(p.roll, p.yaw, p.pitch));
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
    private static void renderHeldItem(LucarioModel m, String handBone, boolean right, ItemRenderState item,
                                       float itemScale, float offsetUnits, float inwardUnits,
                                       MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay) {
        if (item == null || item.isEmpty()) return;
        if (!m.parts.containsKey(handBone)) return;

        matrices.push();
        // сначала сдвиг в системе координат всей модели: к центру тела (правая рука — в сторону +X, левая — в -X)
        matrices.translate((right ? 1.0F : -1.0F) * inwardUnits / 16.0F, 0.0F, 0.0F);
        applyChain(m, handBone, matrices);
        // от запястья вниз по руке к ладони
        matrices.translate((right ? -1.0F : 1.0F) * offsetUnits / 16.0F, 0.0F, 0.0F);
        // вернуть предмету обычный размер (модель масштабирована RENDER_SCALE) и уменьшить под размер персонажа
        float k = itemScale / LucarioModel.RENDER_SCALE;
        matrices.scale(k, k, k);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(right ? 90.0F : -90.0F));
        // дальше — как в ванильном HeldItemFeatureRenderer
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90.0F));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F));
        matrices.translate(0.0F, 0.125F, 0.0F);
        item.render(matrices, vcp, light, overlay);
        matrices.pop();
    }

    // ------------------------------------------------------------------ элитры

    private static void ensureElytra() {
        if (elytraLeft != null) return;
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        Dilation d = new Dilation(1.0F);
        // Кубы направлены ВНУТРЬ от осей (как в ваниле): сложенные крылья сходятся у позвоночника,
        // а в полёте разворачиваются наружу. Раньше кубы шли наружу — половины висели по сторонам с зазором.
        root.addChild("left_wing", ModelPartBuilder.create().uv(22, 0).mirrored(true)
                .cuboid(-10.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), ModelTransform.NONE);
        root.addChild("right_wing", ModelPartBuilder.create().uv(22, 0)
                .cuboid(0.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), ModelTransform.NONE);
        ModelPart r = TexturedModelData.of(data, 64, 32).createModel();
        elytraLeft = r.getChild("left_wing");
        elytraRight = r.getChild("right_wing");
    }

    /** Крылья элитр на спине (геометрия и текстура ванильные); при полёте раскрываются. */
    private static void renderElytra(LucarioModel m, boolean riolu, boolean mega, boolean gliding, boolean crouching, Identifier texture,
                                     MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay) {
        String attach = m.parts.containsKey("chest") ? "chest" : "torso";
        if (!m.parts.containsKey(attach)) return;
        ensureElytra();

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
        elytraLeft.setOrigin(5.0F, y0, 0.0F);
        elytraLeft.setAngles(pitch, yaw, roll);
        elytraRight.setOrigin(-5.0F, y0, 0.0F);
        elytraRight.setAngles(pitch, -yaw, -roll);

        matrices.push();
        applyChain(m, attach, matrices);
        matrices.translate(0.0F, -(riolu ? ELYTRA_UP_RIOLU : ELYTRA_UP_LUCARIO) / 16.0F,
                (riolu ? ELYTRA_BACK_RIOLU : mega ? ELYTRA_BACK_MEGA : ELYTRA_BACK_LUCARIO) / 16.0F);
        float k = (riolu ? ELYTRA_SCALE_RIOLU : ELYTRA_SCALE_LUCARIO) / LucarioModel.RENDER_SCALE;
        matrices.scale(k, k, k);
        matrices.translate(0.0F, 0.0F, 0.125F);
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(texture != null ? texture : ELYTRA_TEXTURE));
        elytraLeft.render(matrices, vc, light, overlay, -1);
        elytraRight.render(matrices, vc, light, overlay, -1);
        matrices.pop();
    }

    // ------------------------------------------------------------------ рука от первого лица

    /**
     * Вызывается вместо ванильной renderRightArm/renderLeftArm. В этот момент матрица уже стоит так, что рука Стива
     * (с плечом в точке (-5, 2, 0) / (5, 2, 0) пикселей) лежит правильно с учётом качания, взмаха и т.п.
     * Мы ставим плечо Лукарио в ту же точку и рисуем только цепочку руки (плечо -> предплечье -> кисть -> пальцы).
     */
    public static void renderFirstPersonArm(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                            String variantKey, boolean right) {
        LucarioModel m = LucarioModels.get(Variants.baseOf(variantKey));
        if (m == null) return;
        ModelPart shoulder = m.parts.get(right ? "arm_right" : "arm_left");
        if (shoulder == null) return;

        poseIdle(m, System.nanoTime() / 1.0e9);
        shoulder.setOrigin(0.0F, 0.0F, 0.0F);
        float roll = (float) Math.toRadians(FIRST_PERSON_ARM_ROLL_DEG) * (right ? -1.0F : 1.0F);
        shoulder.setAngles(shoulder.pitch, shoulder.yaw, shoulder.roll + roll);

        RenderLayer layer = RenderLayer.getEntityCutoutNoCull(
                Variants.isShiny(variantKey) ? m.shinyTexture : m.texture);
        VertexConsumer vc = vertexConsumers.getBuffer(layer);

        matrices.push();
        matrices.translate((right ? -5.0F : 5.0F) / 16.0F, 2.0F / 16.0F, 0.0F);
        float s = LucarioModel.RENDER_SCALE * FIRST_PERSON_ARM_SCALE;
        matrices.scale(s, s, s);
        shoulder.render(matrices, vc, light, OverlayTexture.DEFAULT_UV, -1);
        matrices.pop();
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
            part.setOrigin(b[0] + P_IDLE[0], b[1] - P_IDLE[1], b[2] + P_IDLE[2]);
            part.setAngles(
                    b[3] + (float) Math.toRadians(R_IDLE[0]) * LucarioModel.ROT_SIGN_X,
                    b[4] + (float) Math.toRadians(R_IDLE[1]) * LucarioModel.ROT_SIGN_Y,
                    b[5] + (float) Math.toRadians(R_IDLE[2]) * LucarioModel.ROT_SIGN_Z);
        }
    }

    private static void clear(float[] r, float[] p) {
        r[0] = r[1] = r[2] = 0;
        p[0] = p[1] = p[2] = 0;
    }
}
