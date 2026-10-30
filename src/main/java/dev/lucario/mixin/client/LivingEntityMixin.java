package dev.lucario.mixin.client;

import dev.lucario.client.ClientMorphs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * When crouching, Lucario/Riolu/Mega move their legs slightly faster: the crouch walk animation
 * is played on a slowed-down swing (see CROUCH_WALK_BLEND in LucarioRenderer), so the
 * step pace in the vanilla limb update is compensated with a multiplier.
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
