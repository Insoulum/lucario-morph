package dev.lucario.client;

import dev.lucario.LucarioMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Кэш моделей. Вызывается только из потока рендера.
 *
 * ВАЖНО (1.4.3): модель выпекается ОТДЕЛЬНО для каждого игрока. Раньше кэш был общий
 * на базовый вариант, и два игрока с одинаковым образом (в т.ч. обычный и shiny — база та же)
 * делили одни и те же ModelPart. Сабмит-пайплайн 26.x читает позы костей позже, при отрисовке,
 * поэтому побеждала поза того, кто был засабмичен последним, — и модели "копировали" друг друга.
 * Разбор Bedrock-геометрии (тяжёлая часть) по-прежнему кэшируется и шарится; на игрока
 * выпекается только дешёвое дерево ModelPart.
 *
 * ВАЖНО (1.4.4): кэш игроков ключуется парой (UUID, базовый вариант). Раньше ключом был только
 * UUID: после первой выпечки computeIfAbsent навсегда возвращал модель того варианта, который
 * был применён первым, — при смене образа (например, /lucario mega) тело в третьем лице
 * оставалось прежним (а рука от первого лица была правильной, потому что кэшируется по варианту).
 */
@Environment(EnvType.CLIENT)
public final class LucarioModels {
    /** Разобранная геометрия/анимации на базовый вариант — дорогой парсинг, шарится всеми. */
    private static final Map<String, Optional<LucarioModel.Definition>> DEFINITIONS = new HashMap<>();
    /** Выпеченная модель на (игрок, базовый вариант) — у каждого игрока свои ModelPart,
     *  а у каждого варианта своя модель (см. 1.4.4 в доке класса). */
    private static final Map<UUID, Map<String, Optional<LucarioModel>>> PLAYERS = new HashMap<>();
    /** Отдельный экземпляр для руки от первого лица: его кости не должны трогать чужие сабмиты. */
    private static final Map<String, Optional<LucarioModel>> FIRST_PERSON = new HashMap<>();
    /** Защита от роста кэша (игроки приходят и уходят): при превышении просто сбрасываем. */
    private static final int MAX_PLAYERS = 48;

    private LucarioModels() {}

    /** Личная модель игрока и варианта для рендера в третьем лице.
     *  При смене образа игрок получает новую модель — кэш не "прилипает" к первому варианту. */
    public static LucarioModel get(UUID player, String baseVariant) {
        if (PLAYERS.size() >= MAX_PLAYERS) PLAYERS.clear();
        return PLAYERS.computeIfAbsent(player, id -> new HashMap<>())
                .computeIfAbsent(baseVariant, v -> bake(v))
                .orElse(null);
    }

    /** Модель для руки от первого лица (только локальный игрок, свой экземпляр костей). */
    public static LucarioModel getFirstPerson(String baseVariant) {
        return FIRST_PERSON.computeIfAbsent(baseVariant, v -> bake(v)).orElse(null);
    }

    private static Optional<LucarioModel> bake(String baseVariant) {
        Optional<LucarioModel.Definition> def = DEFINITIONS.computeIfAbsent(baseVariant, v -> {
            try {
                return Optional.of(LucarioModel.loadDefinition(Minecraft.getInstance().getResourceManager(), v));
            } catch (Exception e) {
                LucarioMod.LOGGER.error("Не удалось загрузить модель Лукарио '{}'", v, e);
                return Optional.empty();
            }
        });
        if (def.isEmpty()) return Optional.empty();
        try {
            return Optional.of(LucarioModel.bake(def.get()));
        } catch (Exception e) {
            LucarioMod.LOGGER.error("Не удалось выпечь модель Лукарио '{}'", baseVariant, e);
            return Optional.empty();
        }
    }

    public static void clear() {
        DEFINITIONS.clear();
        PLAYERS.clear();
        FIRST_PERSON.clear();
    }
}
