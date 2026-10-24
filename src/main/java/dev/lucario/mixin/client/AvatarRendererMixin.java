package dev.lucario.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lucario.Variants;
import dev.lucario.client.AttackSwingState;
import dev.lucario.client.ClientMorphs;
import dev.lucario.client.CombatState;
import dev.lucario.client.DanceState;
import dev.lucario.client.LucarioModel;
import dev.lucario.client.LucarioModels;
import dev.lucario.client.LucarioRenderer;
import dev.lucario.client.LucarioStateAccess;
import dev.lucario.client.MotionBlend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({AvatarRenderer.class})
public abstract class AvatarRendererMixin {
   @Inject(
      method = {"extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V"},
      at = {@At("TAIL")}
   )
   private void lucario$copyState(Avatar entity, AvatarRenderState state, float tickProgress, CallbackInfo ci) {
      LucarioStateAccess access = (LucarioStateAccess)state;
      access.lucario$setModel(null);
      access.lucario$setBattleBlend(0.0F);
      access.lucario$setDanceBlend(0.0F);
      access.lucario$setGlideBlend(0.0F);
      String variant = ClientMorphs.get(entity.getUUID());
      access.lucario$setVariant(variant);
      if (variant != null) {
         LucarioModel model = LucarioModels.get(entity.getUUID(), Variants.baseOf(variant));
         if (model == null) {
            access.lucario$setVariant(null);
         } else {
            access.lucario$setModel(model);
            access.lucario$setSprinting(entity.isSprinting());
            // 1.6.4: the plain arm swing rides EVERY vanilla swing again — left
            // clicks AND right-click item use (doors, food, placing), like before
            // 1.6.2. 1.6.2 wrongly zeroed it for the local player. Only the STRIKE
            // overlay (body twist, chest lean, fist, off-arm backswing) stays gated
            // on real attacks: for the LOCAL player that means a recent
            // Minecraft#startAttack mark (700 ms window > the 6-tick swing).
            // Remote players keep the old full-strike behavior — their click type
            // is not observable client-side.
            boolean local = entity == Minecraft.getInstance().player;
            boolean swinging = entity.isSwinging();
            boolean attacking = swinging && (!local || AttackSwingState.recent(entity.getUUID(), 700L));
            access.lucario$setSwing(swinging ? entity.getSwingAnimation(tickProgress) : 0.0F);
            access.lucario$setStrike(attacking);
            HumanoidArm swingArm = entity.getMainArm();
            if (entity.getUsedItemHand() == InteractionHand.OFF_HAND) {
               swingArm = swingArm.getOpposite();
            }

            access.lucario$setSwingRight(swingArm == HumanoidArm.RIGHT);
            access.lucario$setGliding(entity.isFallFlying());
            access.lucario$setHasElytra(entity.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
            if (entity instanceof AbstractClientPlayer player) {
               PlayerSkin skin = player.getSkin();
               Identifier wingTexture = skin.elytra() != null ? skin.elytra().texturePath() : null;
               if (wingTexture == null && skin.cape() != null) {
                  wingTexture = skin.cape().texturePath();
               }

               access.lucario$setElytraTexture(wingTexture);
            }

            access.lucario$setRiding(entity.isPassenger());
            // Patch 11: battle stance lingers ~2s after the entity attacked or was hurt.
            // NOTE (verify on build): `isSwinging()` / `hurtTime` are the official
            // mapping names on 26.3; on 26.2 `getAttackAnim` is used for the swing progress.
            // 1.6.2: right-click swings no longer count as combat for the local player.
            if (attacking || entity.hurtTime > 0) {
               CombatState.mark(entity.getUUID());
            }

            boolean combat = CombatState.recent(entity.getUUID(), 2000L)
               && !entity.isFallFlying()
               && !entity.isInWater()
               && !entity.isPassenger()
               && !entity.isDeadOrDying();
            // 1.6.0: local dance emote of the client player (loops until toggled/moved)
            boolean dancing = DanceState.active()
               && entity == Minecraft.getInstance().player
               && entity.hurtTime <= 0
               && !entity.isFallFlying()
               && !entity.isInWater()
               && !entity.isPassenger()
               && !entity.isDeadOrDying();
            // 1.6.0: all pose switches ease through MotionBlend weights instead of snapping
            access.lucario$setBattleBlend(MotionBlend.battle(entity.getUUID(), combat));
            access.lucario$setDanceBlend(MotionBlend.dance(entity.getUUID(), dancing));
            access.lucario$setGlideBlend(MotionBlend.glide(entity.getUUID(), entity.isFallFlying() && !entity.isDeadOrDying()));
            state.showCape = false;
            state.arrowCount = 0;
            state.stingerCount = 0;
            state.headEquipment = ItemStack.EMPTY;
            state.chestEquipment = ItemStack.EMPTY;
            state.legsEquipment = ItemStack.EMPTY;
            state.feetEquipment = ItemStack.EMPTY;
            state.headItem.clear();
         }
      }
   }

   @Inject(
      method = {"renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lucario$rightArm(PoseStack matrices, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
      String variant = lucario$localVariant();
      if (variant != null) {
         LucarioRenderer.renderFirstPersonArm(matrices, collector, light, variant, true);
         ci.cancel();
      }
   }

   @Inject(
      method = {"renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lucario$leftArm(PoseStack matrices, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
      String variant = lucario$localVariant();
      if (variant != null) {
         LucarioRenderer.renderFirstPersonArm(matrices, collector, light, variant, false);
         ci.cancel();
      }
   }

   private static String lucario$localVariant() {
      Minecraft mc = Minecraft.getInstance();
      return mc.player == null ? null : ClientMorphs.get(mc.player.getUUID());
   }
}
