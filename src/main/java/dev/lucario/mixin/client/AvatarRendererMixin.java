package dev.lucario.mixin.client;

import dev.lucario.client.ClientMorphs;
import dev.lucario.client.LucarioRenderer;
import dev.lucario.client.LucarioStateAccess;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    /** Копируем в состояние рендера, в кого превращён этот игрок, и как он сейчас машет рукой. */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void lucario$copyState(Avatar entity, AvatarRenderState state,
                                   float tickProgress, CallbackInfo ci) {
        LucarioStateAccess access = (LucarioStateAccess) (Object) state;
        String variant = ClientMorphs.get(entity.getUUID());
        access.lucario$setVariant(variant);
        if (variant == null) return;

        access.lucario$setSprinting(entity.isSprinting());

        // Взмах рукой: удар, ломание блока, установка блока, использование предмета.
        access.lucario$setSwing(entity.getSwingAnimation(tickProgress));
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
