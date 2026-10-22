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
 * Ванильный слой предметов (ItemInHandLayer) рисует предмет в координатах руки Стива.
 * У Лукарио/Риолу/Мега скелет другой, поэтому этот предмет "висит в воздухе" рядом с моделью,
 * а свой предмет мы рисуем сами (LucarioRenderer.renderHeldItem) — уже в кисти Лукарио.
 * Для превращённых игроков ванильный слой предметов полностью отключаем (иначе предмет дублируется).
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
