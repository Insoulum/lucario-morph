package dev.lucario.client;

/** Extra data we attach to PlayerEntityRenderState via a mixin. */
public interface LucarioStateAccess {
    String lucario$getVariant();

    void lucario$setVariant(String variant);

    boolean lucario$isSprinting();

    void lucario$setSprinting(boolean sprinting);

    /** Arm swing progress 0..1 (attack / block breaking / item use); 0 = not swinging. */
    float lucario$getSwing();

    void lucario$setSwing(float progress);

    /** true - the current swing is a real attack (left click); enables the strike overlay (body twist, chest lean, fist, off-arm backswing). */
    boolean lucario$isStrike();

    void lucario$setStrike(boolean strike);

    /** true - the right arm is swinging (accounting for the main arm and which hand strikes). */
    boolean lucario$isSwingRight();

    void lucario$setSwingRight(boolean right);

    /** The player is currently gliding with an elytra. */
    boolean lucario$isGliding();

    void lucario$setGliding(boolean gliding);

    /** An elytra is in the chest slot (draw the wings on the back). */
    boolean lucario$hasElytra();

    void lucario$setHasElytra(boolean elytra);

    /** The player's elytra texture (elytra/cape from their skin); null - the default vanilla one. */
    net.minecraft.resources.Identifier lucario$getElytraTexture();

    void lucario$setElytraTexture(net.minecraft.resources.Identifier texture);

    /** The player is currently riding (boat, minecart, pig, etc.) - enable the sitting pose. */
    boolean lucario$isRiding();

    void lucario$setRiding(boolean riding);

    /**
     * This player's personal model instance (1.4.3). Resolved once in extractRenderState,
     * where the entity (and its UUID) is available: each player gets their own bones - poses
     * of identical morphs no longer overlap during deferred rendering.
     */
    LucarioModel lucario$getModel();

    void lucario$setModel(LucarioModel model);

    /** Smoothed battle stance weight 0..1 (the player was recently hurt or attacked themselves). */
    float lucario$getBattleBlend();

    void lucario$setBattleBlend(float blend);

    /** Smoothed dance weight 0..1 (local emote). */
    float lucario$getDanceBlend();

    void lucario$setDanceBlend(float blend);

    /** Smoothed elytra flight pose weight 0..1 (for transitions between poses). */
    float lucario$getGlideBlend();

    void lucario$setGlideBlend(float blend);

    /**
     * 1.7.0: THIS player's emote: the short animation name or {@code PreviewState.TPOSE};
     * null - no emote. Own ones come from DanceState/PreviewState, others' from RemoteEmotes.
     * The renderer used to read global states - now different players' poses are independent.
     */
    String lucario$getEmoteAnim();

    void lucario$setEmoteAnim(String anim);

    /** 1.7.0: this player's emote clock in seconds (starts when toggled on, freezes on stop). */
    double lucario$getEmoteTime();

    void lucario$setEmoteTime(double seconds);
}
