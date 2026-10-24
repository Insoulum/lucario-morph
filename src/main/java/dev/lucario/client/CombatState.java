package dev.lucario.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Patch 11: remembers the last time a player was seen attacking or being hurt,
 * so the "battle_idle" stance can linger for a couple of seconds after the hit
 * instead of switching off the moment the swing ends.
 */
@Environment(EnvType.CLIENT)
public final class CombatState {
   private static final Map<UUID, Long> LAST_COMBAT = new ConcurrentHashMap<>();

   private CombatState() {
   }

   public static void mark(UUID id) {
      LAST_COMBAT.put(id, System.currentTimeMillis());
   }

   public static boolean recent(UUID id, long windowMs) {
      Long last = LAST_COMBAT.get(id);
      return last != null && System.currentTimeMillis() - last <= windowMs;
   }

   public static void clear() {
      LAST_COMBAT.clear();
   }
}
