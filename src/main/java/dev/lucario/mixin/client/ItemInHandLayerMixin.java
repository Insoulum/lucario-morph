package dev.lucario.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla item layer (ItemInHandLayer) renders the held item in Steve's hand coordinates.
 * Lucario/Riolu/Mega have a different skeleton, so that item ends up "hanging in the air" next to the model;
 * we render our own held item ourselves (LucarioRenderer.renderHeldItem), already in Lucario's paw.
 * For morphed players the vanilla item layer is disabled entirely (otherwise the item would be duplicated).
 */
@Mixin(ItemInHandLayer.class)
public class ItemInHandLayerMixin {

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$hideVanillaHeldItem(PoseStack matrices, SubmitNodeCollector collector, int light,
                                             ArmedEntityRenderState state, float yaw, float pitch, CallbackInfo ci) {
        if ((Object) state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
            ci.cancel();
        }
    }
}
