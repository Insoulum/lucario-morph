package dev.lucario.mixin.client;

import dev.lucario.client.ClientMorphs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * В приседе Лукарио/Риолу/Мега перебирают ногами немного чаще: анимация ходьбы в приседе
 * проигрывается на замедленном взмахе (см. CROUCH_WALK_BLEND в LucarioRenderer), поэтому
 * сам темп шага в ванильном обновлении конечностей компенсируем множителем.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Unique
    private static final float SNEAK_PACE_MULTIPLIER = 1.4f;

    @ModifyArg(method = "updateWalkAnimation(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/WalkAnimationState;update(FFF)V"),
            index = 0)
    private float lucario$sneakyLegPace(float targetSpeed) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isCrouching() && self.level().isClientSide()
                && self instanceof Player player
                && ClientMorphs.get(player.getUUID()) != null) {
            return targetSpeed * SNEAK_PACE_MULTIPLIER;
        }
        return targetSpeed;
    }
}
