package dev.lucario.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 1.6.2: remembers the moment of the LAST real ATTACK (left click) per player.
 * <p>
 * Vanilla raises the same hand-swing animation for right-click actions
 * (using items, placing blocks, interacting), but the Lucario strike pose
 * should only play for actual attacks. {@code MinecraftMixin} marks this state
 * from {@code Minecraft#startAttack} (covers both entity hits and air punches;
 * block mining goes through a different path and intentionally does NOT count).
 * <p>
 * 1.6.2 wrongly zeroed the whole swing progress of the LOCAL player for
 * non-attack swings, which also killed the vanilla arm movement on right-click
 * item use. 1.6.4 restores that: the plain arm swing plays for every swing
 * again, and this state only gates the STRIKE overlay (body twist, chest lean,
 * fist, off-arm backswing) via {@code LucarioStateAccess#lucario$isStrike()}.
 * <p>
 * Remote players keep the old behavior (any synced swing shows the full strike),
 * because their click type is not observable client-side.
 */
@Environment(EnvType.CLIENT)
public final class AttackSwingState {
   private static final Map<UUID, Long> LAST_ATTACK = new ConcurrentHashMap<>();

   private AttackSwingState() {
   }

   public static void mark(UUID id) {
      LAST_ATTACK.put(id, System.currentTimeMillis());
   }

   public static boolean recent(UUID id, long windowMs) {
      Long last = LAST_ATTACK.get(id);
      return last != null && System.currentTimeMillis() - last <= windowMs;
   }

   public static void clear() {
      LAST_ATTACK.clear();
   }
}
