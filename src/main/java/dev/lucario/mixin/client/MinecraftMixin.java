package dev.lucario.mixin.client;

import dev.lucario.client.AttackSwingState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.6.3: marks the moment of every real attack (left click) so the Lucario
 * strike pose can ignore right-click swings (item use, block placing,
 * interaction). {@code startAttack} fires for both entity hits and air punches;
 * block mining uses {@code startDestroyBlock}/&#123;@code continueAttack&#125; and is
 * intentionally not marked.
 *
 * <p>1.6.3 fix: {@code startAttack} returns a boolean, so the handler must take
 * a {@link CallbackInfoReturnable} — a plain {@code CallbackInfo} fails to apply
 * at runtime ("CallbackInfoReturnable is required") and crashes the game on
 * startup. We only observe the attack, never cancel it.
 */
@Mixin({Minecraft.class})
public abstract class MinecraftMixin {
   @Inject(
      method = {"startAttack()Z"},
      at = {@At("HEAD")}
   )
   private void lucario$markAttack(CallbackInfoReturnable<Boolean> cir) {
      Minecraft mc = (Minecraft)(Object)this;
      if (mc.player != null) {
         AttackSwingState.mark(mc.player.getUUID());
      }
   }
}
