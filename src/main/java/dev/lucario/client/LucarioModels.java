package dev.lucario.client;

import dev.lucario.LucarioMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Ленивый кэш моделей. Вызывается только из потока рендера. */
@Environment(EnvType.CLIENT)
public final class LucarioModels {
    private static final Map<String, Optional<LucarioModel>> CACHE = new HashMap<>();

    private LucarioModels() {}

    public static LucarioModel get(String baseVariant) {
        return CACHE.computeIfAbsent(baseVariant, v -> {
            try {
                return Optional.of(LucarioModel.load(Minecraft.getInstance().getResourceManager(), v));
            } catch (Exception e) {
                LucarioMod.LOGGER.error("Не удалось загрузить модель Лукарио '{}'", v, e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static void clear() {
        CACHE.clear();
    }
}
