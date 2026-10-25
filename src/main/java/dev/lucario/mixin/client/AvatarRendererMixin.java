package dev.lucario.mixin.client;

import dev.lucario.Variants;
import dev.lucario.client.AttackSwingState;
import dev.lucario.client.ClientMorphs;
import dev.lucario.client.CombatState;
import dev.lucario.client.DanceState;
import dev.lucario.client.LucarioModel;
import dev.lucario.client.LucarioModels;
import dev.lucario.client.LucarioRenderer;
import dev.lucario.client.LucarioStateAccess;
import dev.lucario.client.MotionBlend;
import dev.lucario.client.PreviewState;
import dev.lucario.client.RemoteEmotes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    /** Копируем в состояние рендера, в кого превращён этот игрок, и как он сейчас машет рукой. */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void lucario$copyState(Avatar entity, AvatarRenderState state,
                                   float tickProgress, CallbackInfo ci) {
        LucarioStateAccess access = (LucarioStateAccess) (Object) state;
        // Состояния переиспользуются между сущностями и кадрами — сбрасываем личную модель и бленды поз.
        access.lucario$setModel(null);
        access.lucario$setBattleBlend(0.0F);
        access.lucario$setDanceBlend(0.0F);
        access.lucario$setGlideBlend(0.0F);
        access.lucario$setEmoteAnim(null);
        access.lucario$setEmoteTime(0.0);
        String variant = ClientMorphs.get(entity.getUUID());
        access.lucario$setVariant(variant);
        if (variant == null) return;

        // 1.4.3: личный экземпляр модели на игрока. Раньше кэш был общий на вариант, и два игрока
        // с одинаковым образом (в т.ч. обычный и shiny) получали одни и те же кости — позы "сливались".
        LucarioModel model = LucarioModels.get(entity.getUUID(), Variants.baseOf(variant));
        if (model == null) {
            // Модель не загрузилась — откатываемся на ванильный скин вместо невидимости.
            access.lucario$setVariant(null);
            return;
        }
        access.lucario$setModel(model);

        access.lucario$setSprinting(entity.isSprinting());

        // Взмах рукой: удар, ломание блока, установка блока, использование предмета.
        // 1.6.4: the plain arm swing rides EVERY vanilla swing again — left
        // clicks AND right-click item use (doors, food, placing), like before
        // 1.6.2. Only the STRIKE overlay (body twist, chest lean, fist, off-arm
        // backswing) stays gated on real attacks: for the LOCAL player that means
        // a recent Minecraft#startAttack mark (700 ms window > the 6-tick swing).
        // Remote players keep the old full-strike behavior — their click type
        // is not observable client-side.
        boolean local = entity == Minecraft.getInstance().player;
        boolean swinging = entity.isSwinging();
        boolean attacking = swinging && (!local || AttackSwingState.recent(entity.getUUID(), 700L));
        access.lucario$setSwing(swinging ? entity.getSwingAnimation(tickProgress) : 0.0F);
        access.lucario$setStrike(attacking);
        HumanoidArm swingArm = entity.getMainArm();
        if (entity.getUsedItemHand() == InteractionHand.OFF_HAND) swingArm = swingArm.getOpposite();
        access.lucario$setSwingRight(swingArm == HumanoidArm.RIGHT);

        // Полёт на элитрах и наличие элитр на игроке
        access.lucario$setGliding(entity.isFallFlying());
        access.lucario$setHasElytra(entity.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
        // Скин элитр: как в ваниле — специальная текстура элитр, иначе плащ игрока, иначе стандартные элитры
        if (entity instanceof AbstractClientPlayer player) {
            var skin = player.getSkin();
            Identifier wingTexture = skin.elytra() != null ? skin.elytra().texturePath() : null;
            if (wingTexture == null && skin.cape() != null) wingTexture = skin.cape().texturePath();
            access.lucario$setElytraTexture(wingTexture);
        }

        // Едет верхом (лодка, вагонетка, свинья и т.п.) — для позы сидения
        access.lucario$setRiding(entity.isPassenger());

        // Patch 11: battle stance lingers ~2s after the entity attacked or was hurt.
        // NOTE (verify on build): `isSwinging()` / `hurtTime` are the official
        // mapping names on 26.3; on 26.2 `getAttackAnim` is used for the swing progress.
        // 1.6.2: right-click swings no longer count as combat for the local player.
        if (attacking || entity.hurtTime > 0) {
            CombatState.mark(entity.getUUID());
        }

        boolean combat = CombatState.recent(entity.getUUID(), 2000L)
                && !entity.isFallFlying()
                && !entity.isInWater()
                && !entity.isPassenger()
                && !entity.isDeadOrDying();
        // 1.7.0: эмоут — пер-игроковый. Свой — из DanceState/PreviewState (как раньше);
        // чужие — из RemoteEmotes (события приходят через сервис синхронизации, часы
        // стартуют в момент получения события). Ванильные проверки (урон/вода/полёт/
        // ездок/смерть) синхронизируются самим Minecraft — работают и для чужих игроков.
        String emoteAnim = null;
        double emoteTime = 0.0;
        if (local) {
            if (DanceState.active()) {
                emoteAnim = PreviewState.anim();
                emoteTime = DanceState.danceSeconds();
            }
        } else {
            RemoteEmotes.Emote remote = RemoteEmotes.get(entity.getUUID());
            if (remote != null && remote.active) {
                emoteAnim = remote.anim;
                emoteTime = remote.seconds(System.currentTimeMillis());
            }
        }
        boolean dancing = emoteAnim != null
                && entity.hurtTime <= 0
                && !entity.isFallFlying()
                && !entity.isInWater()
                && !entity.isPassenger()
                && !entity.isDeadOrDying();
        // 1.6.0: all pose switches ease through MotionBlend weights instead of snapping
        access.lucario$setBattleBlend(MotionBlend.battle(entity.getUUID(), combat));
        access.lucario$setDanceBlend(MotionBlend.dance(entity.getUUID(), dancing));
        access.lucario$setGlideBlend(MotionBlend.glide(entity.getUUID(), entity.isFallFlying() && !entity.isDeadOrDying()));
        // 1.7.0: имя и часы эмоута едут в состояние рендера — LucarioRenderer читает
        // пер-игроковые значения вместо глобальных PreviewState/DanceState.
        access.lucario$setEmoteAnim(dancing ? emoteAnim : null);
        access.lucario$setEmoteTime(emoteTime);

        // Ванильные слои рассчитаны на скелет Стива — для образа отключаем:
        // убираем броню/плащ/стрелы/шляпу из состояния (рендерятся они уже после extractRenderState),
        // а также очищаем уже извлечённое состояние предмета на голове (иначе шляпа "висит в воздухе").
        state.showCape = false;
        state.arrowCount = 0;
        state.stingerCount = 0;
        state.headEquipment = ItemStack.EMPTY;
        state.chestEquipment = ItemStack.EMPTY;
        state.legsEquipment = ItemStack.EMPTY;
        state.feetEquipment = ItemStack.EMPTY;
        state.headItem.clear();
    }

    /**
     * Вид от первого лица: вместо руки Стива рисуем руку Лукарио/Риолу.
     * Позицию, качание и взмах уже задал ванильный рендер — мы только меняем саму модель руки.
     */
    @Inject(method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$rightArm(PoseStack matrices, SubmitNodeCollector collector, int light,
                                  Identifier skin, boolean sleeve, CallbackInfo ci) {
        String variant = lucario$localVariant();
        if (variant != null) {
            LucarioRenderer.renderFirstPersonArm(matrices, collector, light, variant, true);
            ci.cancel();
        }
    }

    @Inject(method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$leftArm(PoseStack matrices, SubmitNodeCollector collector, int light,
                                 Identifier skin, boolean sleeve, CallbackInfo ci) {
        String variant = lucario$localVariant();
        if (variant != null) {
            LucarioRenderer.renderFirstPersonArm(matrices, collector, light, variant, false);
            ci.cancel();
        }
    }

    private static String lucario$localVariant() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : ClientMorphs.get(mc.player.getUUID());
    }
}
