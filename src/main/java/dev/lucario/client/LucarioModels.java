package dev.lucario.client;

import dev.lucario.LucarioMod;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public final class LucarioModels {
   private static final int MAX_PLAYERS = 48;
   // Patch 5: proper access-order LRU instead of "wipe everything at 48 players".
   // The old code cleared the whole cache every time a busy server grew past the limit,
   // forcing a mass re-bake of all visible models (lag spike). The LRU evicts exactly
   // one least-recently-used player instead.
   private static final Map<UUID, Map<String, Optional<LucarioModel>>> PLAYERS = Collections.synchronizedMap(
      new LinkedHashMap<>(64, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<UUID, Map<String, Optional<LucarioModel>>> eldest) {
            return this.size() > MAX_PLAYERS;
         }
      });
   private static final Map<String, Optional<LucarioModel.Definition>> DEFINITIONS = new HashMap<>();
   private static final Map<String, Optional<LucarioModel>> FIRST_PERSON = new HashMap<>();

   private LucarioModels() {
   }

   public static LucarioModel get(UUID player, String baseVariant) {
      Map<String, Optional<LucarioModel>> perPlayer = PLAYERS.computeIfAbsent(player, id -> new HashMap<>());
      return perPlayer.computeIfAbsent(baseVariant, LucarioModels::bake).orElse(null);
   }

   public static LucarioModel getFirstPerson(String baseVariant) {
      return FIRST_PERSON.computeIfAbsent(baseVariant, LucarioModels::bake).orElse(null);
   }

   private static Optional<LucarioModel> bake(String baseVariant) {
      Optional<LucarioModel.Definition> def = DEFINITIONS.computeIfAbsent(baseVariant, v -> {
         try {
            return Optional.of(LucarioModel.loadDefinition(Minecraft.getInstance().getResourceManager(), v));
         } catch (Exception var2) {
            LucarioMod.LOGGER.error("Failed to load Lucario model '{}'", v, var2);
            return Optional.empty();
         }
      });
      if (def.isEmpty()) {
         return Optional.empty();
      } else {
         try {
            return Optional.of(LucarioModel.bake(def.get()));
         } catch (Exception var3) {
            LucarioMod.LOGGER.error("Failed to bake Lucario model '{}'", baseVariant, var3);
            return Optional.empty();
         }
      }
   }

   /** Patch 6: invoked from the client resource-reload listener (LucarioClient). */
   public static void clear() {
      synchronized (LucarioModels.class) {
         DEFINITIONS.clear();
         PLAYERS.clear();
         FIRST_PERSON.clear();
      }
   }
}
