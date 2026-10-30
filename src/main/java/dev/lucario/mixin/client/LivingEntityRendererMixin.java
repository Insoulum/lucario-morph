package dev.lucario.mixin.client;

import dev.lucario.client.LucarioRenderer;
import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    /**
     * At the start of the vanilla submit() we render Lucario/Riolu. The vanilla body is hidden (see getRenderType below),
     * while the name tag above the head is still drawn by the rest of the vanilla method.
     */
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"))
    private void lucario$render(LivingEntityRenderState state, PoseStack matrices,
                                SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if ((Object) state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
            LucarioRenderer.render(state, access, matrices, collector, state.lightCoords);
        }
    }

    /** null = "do not render the vanilla model". */
    @Inject(method = "getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            at = @At("HEAD"), cancellable = true)
    private void lucario$hideVanillaBody(LivingEntityRenderState state, boolean showBody, boolean translucent,
                                         boolean showOutline, CallbackInfoReturnable<RenderType> cir) {
        if ((Object) state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
            cir.setReturnValue(null);
        }
    }
}
