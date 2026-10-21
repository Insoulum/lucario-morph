package dev.lucario.mixin.client;

import dev.lucario.client.ClientMorphs;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * В приседе Лукарио/Риолу/Мега перебирают ногами немного чаще: анимация ходьбы в приседе
 * проигрывается на замедленном взмахе (см. CROUCH_WALK_BLEND в LucarioRenderer), поэтому
 * сам темп шага в ванильном updateLimbs компенсируем множителем.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Unique
    private static final float SNEAK_PACE_MULTIPLIER = 1.4f;

    @ModifyArg(method = "updateLimbs(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LimbAnimator;updateLimbs(FFF)V"),
            index = 0)
    private float lucario$sneakyLegPace(float targetSpeed) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isSneaking() && self.getWorld().isClient
                && self instanceof PlayerEntity player
                && ClientMorphs.get(player.getUuid()) != null) {
            return targetSpeed * SNEAK_PACE_MULTIPLIER;
        }
        return targetSpeed;
    }
}
