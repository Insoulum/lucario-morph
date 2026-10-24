package dev.lucario.mixin.client;

import dev.lucario.client.ClientMorphs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({LivingEntity.class})
public abstract class LivingEntityMixin {
   @Unique
   private static final float SNEAK_PACE_MULTIPLIER = 1.4F;

   @ModifyArg(
      method = {"updateWalkAnimation(F)V"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/entity/WalkAnimationState;update(FFF)V"
      )},
      index = 0
   )
   private float lucario$sneakyLegPace(float targetSpeed) {
      LivingEntity self = (LivingEntity)(Object)this; // mixin: this is the target class at runtime
      return self.isCrouching() && self.level().isClientSide() && self instanceof Player player && ClientMorphs.get(player.getUUID()) != null
         ? targetSpeed * 1.4F
         : targetSpeed;
   }
}
