package dev.lucario.mixin.client;

import dev.lucario.client.ClientMorphs;
import dev.lucario.client.LucarioRenderer;
import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    /** Копируем в состояние рендера, в кого превращён этот игрок, и как он сейчас машет рукой. */
    @Inject(method = "updateRenderState(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"))
    private void lucario$copyState(AbstractClientPlayerEntity player, PlayerEntityRenderState state,
                                   float tickProgress, CallbackInfo ci) {
        LucarioStateAccess access = (LucarioStateAccess) (Object) state;
        access.lucario$setVariant(ClientMorphs.get(player.getUuid()));
        access.lucario$setSprinting(player.isSprinting());

        // Взмах рукой: удар, ломание блока, установка блока, использование предмета.
        access.lucario$setSwing(player.getHandSwingProgress(tickProgress));
        Arm swingArm = player.getMainArm();
        if (player.preferredHand == Hand.OFF_HAND) swingArm = swingArm.getOpposite();
        access.lucario$setSwingRight(swingArm == Arm.RIGHT);

        // Полёт на элитрах и наличие элитр на игроке
        access.lucario$setGliding(player.isGliding());
        access.lucario$setHasElytra(player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA));
        // Скин элитр: как в ваниле — специальная текстура элитр, иначе плащ игрока, иначе стандартные элитры
        var skin = player.getSkinTextures();
        Identifier wingTexture = skin.elytraTexture() != null ? skin.elytraTexture() : skin.capeTexture();
        access.lucario$setElytraTexture(wingTexture);

        // Едет верхом (лодка, вагонетка, свинья и т.п.) — для позы сидения
        access.lucario$setRiding(player.hasVehicle());
    }

    /** Ванильные слои (броня, плащ, элитры...) рассчитаны на скелет Стива — для образа отключаем. Предметы рисуем сами. */
    @Inject(method = "shouldRenderFeatures(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Z",
            at = @At("HEAD"), cancellable = true)
    private void lucario$noFeatures(PlayerEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
        if (((LucarioStateAccess) (Object) state).lucario$getVariant() != null) cir.setReturnValue(false);
    }

    /**
     * Вид от первого лица: вместо руки Стива рисуем руку Лукарио/Риолу.
     * Позицию, качание и взмах уже задал ванильный HeldItemRenderer — мы только меняем саму модель руки.
     */
    @Inject(method = "renderRightArm(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/util/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$rightArm(MatrixStack matrices, VertexConsumerProvider vcp, int light,
                                  Identifier skin, boolean sleeve, CallbackInfo ci) {
        String variant = lucario$localVariant();
        if (variant != null) {
            LucarioRenderer.renderFirstPersonArm(matrices, vcp, light, variant, true);
            ci.cancel();
        }
    }

    @Inject(method = "renderLeftArm(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/util/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$leftArm(MatrixStack matrices, VertexConsumerProvider vcp, int light,
                                 Identifier skin, boolean sleeve, CallbackInfo ci) {
        String variant = lucario$localVariant();
        if (variant != null) {
            LucarioRenderer.renderFirstPersonArm(matrices, vcp, light, variant, false);
            ci.cancel();
        }
    }

    private static String lucario$localVariant() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? null : ClientMorphs.get(mc.player.getUuid());
    }
}
