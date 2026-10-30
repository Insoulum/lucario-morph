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
 * Model cache. Called only from the render thread.
 *
 * IMPORTANT (1.4.3): the model is baked SEPARATELY for each player. The cache used to be shared
 * per base variant, and two players with the same morph (including normal and shiny - same base)
 * shared the same ModelParts. The 26.x submit pipeline reads bone poses later, at draw time,
 * so the pose of whoever was submitted last won - the models "copied" each other.
 * Bedrock geometry parsing (the heavy part) is still cached and shared; only the cheap
 * ModelPart tree is baked per player.
 */
@Environment(EnvType.CLIENT)
public final class LucarioModels {
    /** Parsed geometry/animations per base variant - expensive to parse, shared by everyone. */
    private static final Map<String, Optional<LucarioModel.Definition>> DEFINITIONS = new HashMap<>();
    /** Baked models per (player, variant) pair - each has its own ModelParts, poses never overlap. */
    private static final Map<UUID, Map<String, Optional<LucarioModel>>> PLAYERS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Map<String, Optional<LucarioModel>>> eldest) {
                    return size() > MAX_PLAYERS;
                }
            });
    /** Separate instance for the first-person arm: its bones must not touch other submits. */
    private static final Map<String, Optional<LucarioModel>> FIRST_PERSON = new HashMap<>();
    /** Cache growth guard (players come and go): the LRU evicts one stale player instead of the whole cache. */
    private static final int MAX_PLAYERS = 48;

    private LucarioModels() {}

    /** The player's personal model for third-person rendering. */
    public static LucarioModel get(UUID player, String baseVariant) {
        Map<String, Optional<LucarioModel>> perPlayer = PLAYERS.computeIfAbsent(player, id -> new HashMap<>());
        return perPlayer.computeIfAbsent(baseVariant, v -> bake(v)).orElse(null);
    }

    /** Model for the first-person arm (local player only, its own bone instance). */
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

    /** Full cache reset: called from the resource reload listener in LucarioClient. */
    public static void clear() {
        synchronized (LucarioModels.class) {
            DEFINITIONS.clear();
            PLAYERS.clear();
            FIRST_PERSON.clear();
        }
    }
}
