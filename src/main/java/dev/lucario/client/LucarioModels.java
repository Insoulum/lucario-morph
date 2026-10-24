package dev.lucario.client;

import dev.lucario.LucarioMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 */
@Environment(EnvType.CLIENT)
public final class LucarioModels {
    /** Разобранная геометрия/анимации на базовый вариант — дорогой парсинг, шарится всеми. */
    private static final Map<String, Optional<LucarioModel.Definition>> DEFINITIONS = new HashMap<>();
    /** Выпеченные модели на пару (игрок, вариант) — у каждого свои ModelPart, позы не пересекаются. */
    private static final Map<UUID, Map<String, Optional<LucarioModel>>> PLAYERS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Map<String, Optional<LucarioModel>>> eldest) {
                    return size() > MAX_PLAYERS;
                }
            });
    /** Отдельный экземпляр для руки от первого лица: его кости не должны трогать чужие сабмиты. */
    private static final Map<String, Optional<LucarioModel>> FIRST_PERSON = new HashMap<>();
    /** Защита от роста кэша (игроки приходят и уходят): LRU вытесняет одного давнего игрока, не сбрасывая весь кэш. */
    private static final int MAX_PLAYERS = 48;

    private LucarioModels() {}

    /** Личная модель игрока для рендера в третьем лице. */
    public static LucarioModel get(UUID player, String baseVariant) {
        Map<String, Optional<LucarioModel>> perPlayer = PLAYERS.computeIfAbsent(player, id -> new HashMap<>());
        return perPlayer.computeIfAbsent(baseVariant, v -> bake(v)).orElse(null);
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
                LucarioMod.LOGGER.error("Failed to load Lucario model '{}'", v, e);
                return Optional.empty();
            }
        });
        if (def.isEmpty()) return Optional.empty();
        try {
            return Optional.of(LucarioModel.bake(def.get()));
        } catch (Exception e) {
            LucarioMod.LOGGER.error("Failed to bake Lucario model '{}'", baseVariant, e);
            return Optional.empty();
        }
    }

    /** Полный сброс кэша: вызывается из слушателя перезагрузки ресурсов в LucarioClient. */
    public static void clear() {
        synchronized (LucarioModels.class) {
            DEFINITIONS.clear();
            PLAYERS.clear();
            FIRST_PERSON.clear();
        }
    }
}
