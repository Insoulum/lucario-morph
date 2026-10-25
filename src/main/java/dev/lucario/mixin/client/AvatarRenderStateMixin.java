package dev.lucario.mixin.client;

import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Добавляет в состояние рендера игрока поля "какой образ", "бежит ли", "взмах рукой" и личную модель. */
@Mixin(AvatarRenderState.class)
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
    private dev.lucario.client.LucarioModel lucario$model;
    @Unique
    private float lucario$battleBlend;
    @Unique
    private float lucario$danceBlend;
    @Unique
    private float lucario$glideBlend;
    @Unique
    private String lucario$emoteAnim;
    @Unique
    private double lucario$emoteTime;

    @Override
    public String lucario$getVariant() {
        return lucario$variant;
    }

    @Override
    public void lucario$setVariant(String variant) {
        this.lucario$variant = variant;
    }

    @Override
    public boolean lucario$isSprinting() {
        return lucario$sprinting;
    }

    @Override
    public void lucario$setSprinting(boolean sprinting) {
        this.lucario$sprinting = sprinting;
    }

    @Override
    public float lucario$getSwing() {
        return lucario$swing;
    }

    @Override
    public void lucario$setSwing(float progress) {
        this.lucario$swing = progress;
    }

    @Override
    public boolean lucario$isStrike() {
        return lucario$strike;
    }

    @Override
    public void lucario$setStrike(boolean strike) {
        this.lucario$strike = strike;
    }

    @Override
    public boolean lucario$isSwingRight() {
        return lucario$swingRight;
    }

    @Override
    public void lucario$setSwingRight(boolean right) {
        this.lucario$swingRight = right;
    }

    @Override
    public boolean lucario$isGliding() {
        return lucario$gliding;
    }

    @Override
    public void lucario$setGliding(boolean gliding) {
        this.lucario$gliding = gliding;
    }

    @Override
    public boolean lucario$hasElytra() {
        return lucario$elytra;
    }

    @Override
    public void lucario$setHasElytra(boolean elytra) {
        this.lucario$elytra = elytra;
    }

    @Override
    public Identifier lucario$getElytraTexture() {
        return lucario$elytraTexture;
    }

    @Override
    public void lucario$setElytraTexture(Identifier texture) {
        this.lucario$elytraTexture = texture;
    }

    @Override
    public boolean lucario$isRiding() {
        return lucario$riding;
    }

    @Override
    public void lucario$setRiding(boolean riding) {
        this.lucario$riding = riding;
    }

    @Override
    public dev.lucario.client.LucarioModel lucario$getModel() {
        return lucario$model;
    }

    @Override
    public void lucario$setModel(dev.lucario.client.LucarioModel model) {
        this.lucario$model = model;
    }

    @Override
    public float lucario$getBattleBlend() {
        return lucario$battleBlend;
    }

    @Override
    public void lucario$setBattleBlend(float blend) {
        this.lucario$battleBlend = blend;
    }

    @Override
    public float lucario$getDanceBlend() {
        return lucario$danceBlend;
    }

    @Override
    public void lucario$setDanceBlend(float blend) {
        this.lucario$danceBlend = blend;
    }

    @Override
    public float lucario$getGlideBlend() {
        return lucario$glideBlend;
    }

    @Override
    public void lucario$setGlideBlend(float blend) {
        this.lucario$glideBlend = blend;
    }

    @Override
    public String lucario$getEmoteAnim() {
        return lucario$emoteAnim;
    }

    @Override
    public void lucario$setEmoteAnim(String anim) {
        this.lucario$emoteAnim = anim;
    }

    @Override
    public double lucario$getEmoteTime() {
        return lucario$emoteTime;
    }

    @Override
    public void lucario$setEmoteTime(double seconds) {
        this.lucario$emoteTime = seconds;
    }
}
