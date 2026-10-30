package dev.lucario.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 1.7.0: OTHER players' emotes received via the sync service
 * ({@link EmoteSync} - WebSocket push, with a fallback HTTP poll when it is unavailable).
 * <p>
 * Previously an emote was local (see the note in {@link DanceState}): {@code dancing} in
 * {@code AvatarRendererMixin} was computed only for the local player. Emote state is now
 * per-player: each player has their own animation name and their own clock.
 * <p>
 * Record layout:
 * <ul>
 *   <li>{@code anim} - short animation name or {@link PreviewState#TPOSE};</li>
 *   <li>{@code startMs} - start moment BY THE OBSERVER'S LOCAL CLOCK: the event
 *       arrives after ~half an RTT, the clock is set at receipt, so the animation
 *       starts from the first keyframe with no clock synchronization between players
 *       (for looping emotes the phase does not matter anyway);</li>
 *   <li>{@code frozenSeconds} - time frozen at the stop (the frame is held
 *       during the crossfade - like the local {@link DanceState#stop(String)});</li>
 *   <li>{@code applyTs} - sender timestamp (epoch ms) for EVENT ORDER: all
 *       events of one player are stamped with that player's clock, so a stale snapshot
 *       from another mirror cannot override a fresher event;</li>
 *   <li>{@code touchMs} - local time of the last update: a guard against
 *       "hung" emotes (if updates stopped - the player crashed and the service TTL
 *       has not fired yet), the emote fades out on its own.</li>
 * </ul>
 * Thread safety: the map is a {@link ConcurrentHashMap}; writes happen only on
 * the main thread ({@code mc.execute} from the network listeners), reads - on the render thread.
 */
@Environment(EnvType.CLIENT)
public final class RemoteEmotes {
    /** Guard: an emote without updates for longer than this fades out on its own (service TTL - 30 s, refresh - 10 s). */
    static final long STALE_MS = 35_000L;
    /** How many seconds to hold the frame after a stop (crossfade ~0.2 s - with margin). */
    private static final long DEAD_KEEP_MS = 5_000L;

    /** One player emote record (immutable; the map keeps the latest variant). */
    public static final class Emote {
        public final String anim;
        final long startMs;
        public final boolean active;
        final float frozenSeconds;
        final double applyTs;
        final long touchMs;

        Emote(String anim, long startMs, boolean active, float frozenSeconds,
              double applyTs, long touchMs) {
            this.anim = anim;
            this.startMs = startMs;
            this.active = active;
            this.frozenSeconds = frozenSeconds;
            this.applyTs = applyTs;
            this.touchMs = touchMs;
        }

        /** Emote seconds: run while active; frozen after a stop (for the fade). */
        public double seconds(long nowMs) {
            return active ? (nowMs - startMs) / 1000.0 : frozenSeconds;
        }
    }

    private static final Map<UUID, Emote> MAP = new ConcurrentHashMap<>();

    private RemoteEmotes() {}

    public static Emote get(UUID id) {
        return MAP.get(id);
    }

    /** Whether the player's emote is active right now (with the hang guard applied). */
    public static boolean active(UUID id) {
        Emote e = MAP.get(id);
        return e != null && e.active && System.currentTimeMillis() - e.touchMs <= STALE_MS;
    }

    /**
     * Applies an event/snapshot from the service. Event order is protected by the sender
     * timestamp: a start is accepted only if STRICTLY newer, a stop - not older (the
     * service's TTL stop arrives 1 ms after the last refresh on the same sender clock - see service.py).
     */
    public static void apply(UUID id, String anim, boolean active, double ts) {
        if (id == null || anim == null || anim.isEmpty()) {
            MAP.remove(id);
            return;
        }
        long now = System.currentTimeMillis();
        Emote cur = MAP.get(id);
        if (cur != null && ts > 0 && ts < cur.applyTs) {
            return; // stale event (e.g. from a lagging mirror) - ignored
        }
        if (active) {
            if (cur != null && cur.active && cur.anim.equals(anim)) {
                // refresh "still playing": do NOT restart the clock
                MAP.put(id, new Emote(anim, cur.startMs, true, 0.0F,
                        Math.max(cur.applyTs, ts), now));
            } else {
                MAP.put(id, new Emote(anim, now, true, 0.0F, ts, now));
            }
        } else {
            if (cur == null) return;               // stop without a known start - nothing to fade out
            if (cur.active) {
                float frozen = (now - cur.startMs) / 1000.0F;
                MAP.put(id, new Emote(cur.anim, cur.startMs, false, frozen,
                        Math.max(cur.applyTs, ts), now));
            } else {
                MAP.put(id, new Emote(cur.anim, cur.startMs, false, cur.frozenSeconds,
                        Math.max(cur.applyTs, ts), now));
            }
        }
    }

    /** Snapshot from a mirror: ids missing from it are removed only if already faded out
     *  (active ones are kept - the mirror may lag; a stop will arrive as a separate event/TTL). */
    static void dropAbsentInactive(List<UUID> present) {
        for (Map.Entry<UUID, Emote> e : MAP.entrySet()) {
            if (!present.contains(e.getKey())) {
                Emote emote = e.getValue();
                long now = System.currentTimeMillis();
                if (!emote.active && now - emote.touchMs > DEAD_KEEP_MS) MAP.remove(e.getKey());
            }
        }
    }

    static List<UUID> ids() {
        return new ArrayList<>(MAP.keySet());
    }

    /** Hygiene: prune records that have not been updated for a while (player crash, mirror change). */
    static void prune(long nowMs) {
        MAP.values().removeIf(e -> nowMs - e.touchMs > Math.max(STALE_MS, DEAD_KEEP_MS));
    }

    public static void clear() {
        MAP.clear();
    }
}
