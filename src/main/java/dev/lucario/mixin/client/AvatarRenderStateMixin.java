package dev.lucario.mixin.client;

import dev.lucario.client.LucarioModel;
import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({AvatarRenderState.class})
public class AvatarRenderStateMixin implements LucarioStateAccess {
   @Unique
   private String lucario$variant;
   @Unique
   private boolean lucario$sprinting;
   @Unique
   private float lucario$swing;
   @Unique
   private boolean lucario$strike;
   @Unique
   private boolean lucario$swingRight = true;
   @Unique
   private boolean lucario$gliding;
   @Unique
   private boolean lucario$elytra;
   @Unique
   private Identifier lucario$elytraTexture;
   @Unique
   private boolean lucario$riding;
   @Unique
   private LucarioModel lucario$model;
   @Unique
   private float lucario$battleBlend;
   @Unique
   private float lucario$danceBlend;
   @Unique
   private float lucario$glideBlend;

   @Override
   public String lucario$getVariant() {
      return this.lucario$variant;
   }

   @Override
   public void lucario$setVariant(String variant) {
      this.lucario$variant = variant;
   }

   @Override
   public boolean lucario$isSprinting() {
      return this.lucario$sprinting;
   }

   @Override
   public void lucario$setSprinting(boolean sprinting) {
      this.lucario$sprinting = sprinting;
   }

   @Override
   public float lucario$getSwing() {
      return this.lucario$swing;
   }

   @Override
   public void lucario$setSwing(float progress) {
      this.lucario$swing = progress;
   }

   @Override
   public boolean lucario$isStrike() {
      return this.lucario$strike;
   }

   @Override
   public void lucario$setStrike(boolean strike) {
      this.lucario$strike = strike;
   }

   @Override
   public boolean lucario$isSwingRight() {
      return this.lucario$swingRight;
   }

   @Override
   public void lucario$setSwingRight(boolean right) {
      this.lucario$swingRight = right;
   }

   @Override
   public boolean lucario$isGliding() {
      return this.lucario$gliding;
   }

   @Override
   public void lucario$setGliding(boolean gliding) {
      this.lucario$gliding = gliding;
   }

   @Override
   public boolean lucario$hasElytra() {
      return this.lucario$elytra;
   }

   @Override
   public void lucario$setHasElytra(boolean elytra) {
      this.lucario$elytra = elytra;
   }

   @Override
   public Identifier lucario$getElytraTexture() {
      return this.lucario$elytraTexture;
   }

   @Override
   public void lucario$setElytraTexture(Identifier texture) {
      this.lucario$elytraTexture = texture;
   }

   @Override
   public boolean lucario$isRiding() {
      return this.lucario$riding;
   }

   @Override
   public void lucario$setRiding(boolean riding) {
      this.lucario$riding = riding;
   }

   @Override
   public LucarioModel lucario$getModel() {
      return this.lucario$model;
   }

   @Override
   public void lucario$setModel(LucarioModel model) {
      this.lucario$model = model;
   }

   @Override
   public float lucario$getBattleBlend() {
      return this.lucario$battleBlend;
   }

   @Override
   public void lucario$setBattleBlend(float blend) {
      this.lucario$battleBlend = blend;
   }

   @Override
   public float lucario$getDanceBlend() {
      return this.lucario$danceBlend;
   }

   @Override
   public void lucario$setDanceBlend(float blend) {
      this.lucario$danceBlend = blend;
   }

   @Override
   public float lucario$getGlideBlend() {
      return this.lucario$glideBlend;
   }

   @Override
   public void lucario$setGlideBlend(float blend) {
      this.lucario$glideBlend = blend;
   }
}
