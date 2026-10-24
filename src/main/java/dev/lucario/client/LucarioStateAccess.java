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

    /** true — текущий взмах является реальной атакой (ЛКМ); включает оверлей удара (скрутка корпуса, наклон груди, кулак, замах второй руки). */
    boolean lucario$isStrike();

    void lucario$setStrike(boolean strike);

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

    /** Сглаженный вес боевой стойки 0..1 (игрока недавно ударили или он сам атаковал). */
    float lucario$getBattleBlend();

    void lucario$setBattleBlend(float blend);

    /** Сглаженный вес танца 0..1 (локальный эмоут). */
    float lucario$getDanceBlend();

    void lucario$setDanceBlend(float blend);

    /** Сглаженный вес позы полёта на элитрах 0..1 (для перехода между позами). */
    float lucario$getGlideBlend();

    void lucario$setGlideBlend(float blend);
}
