package dev.lucario.client;

/** Дополнительные данные, которые мы цепляем к PlayerEntityRenderState через миксин. */
public interface LucarioStateAccess {
    String lucario$getVariant();

    void lucario$setVariant(String variant);

    boolean lucario$isSprinting();

    void lucario$setSprinting(boolean sprinting);

    /** Прогресс взмаха рукой 0..1 (удар / ломание блока / использование); 0 = не машет. */
    float lucario$getSwing();

    void lucario$setSwing(float progress);

    /** true — машет правой рукой (с учётом ведущей руки и того, какой рукой бьют). */
    boolean lucario$isSwingRight();

    void lucario$setSwingRight(boolean right);

    /** Игрок сейчас летит на элитрах. */
    boolean lucario$isGliding();

    void lucario$setGliding(boolean gliding);

    /** В слоте нагрудника лежат элитры (рисуем крылья на спине). */
    boolean lucario$hasElytra();

    void lucario$setHasElytra(boolean elytra);

    /** Текстура элитр игрока (элитры/плащ из его скина); null — обычная ванильная. */
    net.minecraft.resources.Identifier lucario$getElytraTexture();

    void lucario$setElytraTexture(net.minecraft.resources.Identifier texture);

    /** Игрок сейчас едет верхом (лодка, вагонетка, свинья и т.п.) — включаем позу сидения. */
    boolean lucario$isRiding();

    void lucario$setRiding(boolean riding);

    /**
     * Личный экземпляр модели этого игрока (1.4.3). Разрешается один раз в extractRenderState,
     * где доступна сущность (и её UUID): у каждого игрока свои кости — позы одинаковых образов
     * больше не пересекаются при отложенной отрисовке.
     */
    LucarioModel lucario$getModel();

    void lucario$setModel(LucarioModel model);
}
