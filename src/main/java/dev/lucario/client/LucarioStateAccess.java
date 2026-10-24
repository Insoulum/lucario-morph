package dev.lucario.client;

import net.minecraft.resources.Identifier;

public interface LucarioStateAccess {
   String lucario$getVariant();

   void lucario$setVariant(String var1);

   boolean lucario$isSprinting();

   void lucario$setSprinting(boolean var1);

   float lucario$getSwing();

   void lucario$setSwing(float var1);

   // 1.6.4: the current swing is a REAL attack (left click) — gates the strike
   // overlay (body twist, chest lean, fist, off-arm backswing). The plain arm
   // swing plays for every vanilla swing (right-click item use included).
   boolean lucario$isStrike();

   void lucario$setStrike(boolean var1);

   boolean lucario$isSwingRight();

   void lucario$setSwingRight(boolean var1);

   boolean lucario$isGliding();

   void lucario$setGliding(boolean var1);

   boolean lucario$hasElytra();

   void lucario$setHasElytra(boolean var1);

   Identifier lucario$getElytraTexture();

   void lucario$setElytraTexture(Identifier var1);

   boolean lucario$isRiding();

   void lucario$setRiding(boolean var1);

   LucarioModel lucario$getModel();

   void lucario$setModel(LucarioModel var1);

   // Patch 11 / 1.6.0: battle stance (attacked or was hurt recently) — smoothed weight
   float lucario$getBattleBlend();

   void lucario$setBattleBlend(float var1);

   // Patch 11 / 1.6.0: local dance emote — smoothed weight
   float lucario$getDanceBlend();

   void lucario$setDanceBlend(float var1);

   // 1.6.0: smoothed elytra glide weight (0..1) for the pose transition
   float lucario$getGlideBlend();

   void lucario$setGlideBlend(float var1);
}
