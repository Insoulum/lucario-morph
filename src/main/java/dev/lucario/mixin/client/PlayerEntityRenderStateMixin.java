package dev.lucario.mixin.client;

import dev.lucario.client.LucarioStateAccess;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Добавляет в состояние рендера игрока поля "какой образ", "бежит ли" и "взмах рукой". */
@Mixin(PlayerEntityRenderState.class)
public class PlayerEntityRenderStateMixin implements LucarioStateAccess {
    @Unique
    private String lucario$variant;
    @Unique
    private boolean lucario$sprinting;
    @Unique
    private float lucario$swing;
    @Unique
    private boolean lucario$swingRight = true;
    @Unique
    private boolean lucario$gliding;
    @Unique
    private boolean lucario$elytra;
    @Unique
    private net.minecraft.util.Identifier lucario$elytraTexture;
    @Unique
    private boolean lucario$riding;

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
    public net.minecraft.util.Identifier lucario$getElytraTexture() {
        return lucario$elytraTexture;
    }

    @Override
    public void lucario$setElytraTexture(net.minecraft.util.Identifier texture) {
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
}
