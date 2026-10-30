package dev.lucario.mixin.client;

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
import dev.lucario.client.PreviewState;
import dev.lucario.client.RemoteEmotes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    /** Copy into the render state which morph this player is in and how it is currently swinging its arm. */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void lucario$copyState(Avatar entity, AvatarRenderState state,
                                   float tickProgress, CallbackInfo ci) {
        LucarioStateAccess access = (LucarioStateAccess) (Object) state;
        // Render states are reused across entities and frames - reset the personal model and the pose blends.
        access.lucario$setModel(null);
        access.lucario$setBattleBlend(0.0F);
        access.lucario$setDanceBlend(0.0F);
        access.lucario$setGlideBlend(0.0F);
        access.lucario$setEmoteAnim(null);
        access.lucario$setEmoteTime(0.0);
        String variant = ClientMorphs.get(entity.getUUID());
        access.lucario$setVariant(variant);
        if (variant == null) return;

        // 1.4.3: a per-player model instance. The cache used to be shared per variant, so two players
        // with the same morph (including normal and shiny) got the same bones - poses "merged".
        LucarioModel model = LucarioModels.get(entity.getUUID(), Variants.baseOf(variant));
        if (model == null) {
            // Model failed to load - fall back to the vanilla skin instead of invisibility.
            access.lucario$setVariant(null);
            return;
        }
        access.lucario$setModel(model);

        access.lucario$setSprinting(entity.isSprinting());

        // Arm swing: attacking, breaking a block, placing a block, using an item.
        // 1.6.4: the plain arm swing rides EVERY vanilla swing again — left
        // clicks AND right-click item use (doors, food, placing), like before
        // 1.6.2. Only the STRIKE overlay (body twist, chest lean, fist, off-arm
        // backswing) stays gated on real attacks: for the LOCAL player that means
        // a recent Minecraft#startAttack mark (700 ms window > the 6-tick swing).
        // Remote players keep the old full-strike behavior — their click type
        // is not observable client-side.
        boolean local = entity == Minecraft.getInstance().player;
        boolean swinging = entity.isSwinging();
        boolean attacking = swinging && (!local || AttackSwingState.recent(entity.getUUID(), 700L));
        access.lucario$setSwing(swinging ? entity.getSwingAnimation(tickProgress) : 0.0F);
        access.lucario$setStrike(attacking);
        HumanoidArm swingArm = entity.getMainArm();
        if (entity.getUsedItemHand() == InteractionHand.OFF_HAND) swingArm = swingArm.getOpposite();
        access.lucario$setSwingRight(swingArm == HumanoidArm.RIGHT);

        // Elytra flight and whether the player has an elytra on
        access.lucario$setGliding(entity.isFallFlying());
        access.lucario$setHasElytra(entity.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
        // Elytra skin: like vanilla - the dedicated elytra texture, else the player's cape, else the default elytra
        if (entity instanceof AbstractClientPlayer player) {
            var skin = player.getSkin();
            Identifier wingTexture = skin.elytra() != null ? skin.elytra().texturePath() : null;
            if (wingTexture == null && skin.cape() != null) wingTexture = skin.cape().texturePath();
            access.lucario$setElytraTexture(wingTexture);
        }

        // Is riding (boat, minecart, pig, etc.) - for the sitting pose
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
        // 1.7.0: the emote is per-player. Own emote - from DanceState/PreviewState (as before);
        // other players' - from RemoteEmotes (events arrive via the sync service, the clock
        // starts when the event is received). Vanilla checks (damage/water/flight/
        // riding/death) are synchronized by Minecraft itself - they work for remote players too.
        String emoteAnim = null;
        double emoteTime = 0.0;
        if (local) {
            if (DanceState.active()) {
                emoteAnim = PreviewState.anim();
                emoteTime = DanceState.danceSeconds();
            }
        } else {
            RemoteEmotes.Emote remote = RemoteEmotes.get(entity.getUUID());
            if (remote != null && remote.active) {
                emoteAnim = remote.anim;
                emoteTime = remote.seconds(System.currentTimeMillis());
            }
        }
        boolean dancing = emoteAnim != null
                && entity.hurtTime <= 0
                && !entity.isFallFlying()
                && !entity.isInWater()
                && !entity.isPassenger()
                && !entity.isDeadOrDying();
        // 1.6.0: all pose switches ease through MotionBlend weights instead of snapping
        access.lucario$setBattleBlend(MotionBlend.battle(entity.getUUID(), combat));
        access.lucario$setDanceBlend(MotionBlend.dance(entity.getUUID(), dancing));
        access.lucario$setGlideBlend(MotionBlend.glide(entity.getUUID(), entity.isFallFlying() && !entity.isDeadOrDying()));
        // 1.7.0: the emote name and clock travel inside the render state - LucarioRenderer reads
        // the per-player values instead of the global PreviewState/DanceState.
        access.lucario$setEmoteAnim(dancing ? emoteAnim : null);
        access.lucario$setEmoteTime(emoteTime);

        // Vanilla layers are designed for the Steve skeleton - disable them for the morph:
        // strip armor/cape/arrows/hat from the state (they are rendered after extractRenderState anyway),
        // and also clear the already extracted head item state (otherwise the hat "floats in mid-air").
        state.showCape = false;
        state.arrowCount = 0;
        state.stingerCount = 0;
        state.headEquipment = ItemStack.EMPTY;
        state.chestEquipment = ItemStack.EMPTY;
        state.legsEquipment = ItemStack.EMPTY;
        state.feetEquipment = ItemStack.EMPTY;
        state.headItem.clear();
    }

    /**
     * First-person view: draw a Lucario/Riolu arm instead of the Steve arm.
     * Position, bob and swing are already set up by the vanilla renderer - we only swap the arm model itself.
     */
    @Inject(method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$rightArm(PoseStack matrices, SubmitNodeCollector collector, int light,
                                  Identifier skin, boolean sleeve, CallbackInfo ci) {
        String variant = lucario$localVariant();
        if (variant != null) {
            LucarioRenderer.renderFirstPersonArm(matrices, collector, light, variant, true);
            ci.cancel();
        }
    }

    @Inject(method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void lucario$leftArm(PoseStack matrices, SubmitNodeCollector collector, int light,
                                 Identifier skin, boolean sleeve, CallbackInfo ci) {
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
