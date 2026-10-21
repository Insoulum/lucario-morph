package dev.lucario.mixin.client;

import dev.lucario.client.LucarioRenderer;
import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    /**
     * В начале ванильного render() рисуем Лукарио/Риолу. Ванильное тело скрыто (см. getRenderLayer ниже),
     * а имя над головой всё так же рисует остаток ванильного метода.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"))
    private void lucario$render(LivingEntityRenderState state, MatrixStack matrices,
                                VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if ((Object) state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
            LucarioRenderer.render(state, access, matrices, vertexConsumers, light);
        }
    }

    /** null = "ванильную модель не рисовать". */
    @Inject(method = "getRenderLayer(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/render/RenderLayer;",
            at = @At("HEAD"), cancellable = true)
    private void lucario$hideVanillaBody(LivingEntityRenderState state, boolean showBody, boolean translucent,
                                         boolean showOutline, CallbackInfoReturnable<RenderLayer> cir) {
        if ((Object) state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
            cir.setReturnValue(null);
        }
    }
}
