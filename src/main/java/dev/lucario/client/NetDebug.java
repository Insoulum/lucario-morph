package dev.lucario.client;

import dev.lucario.LucarioMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 1.7.7-debug2: tracing of the sync network channel. EVERYTHING goes to both the log
 * and the chat: WebSocket events - tag [NET-WS] / chat prefix "[LM]", HTTP exchanges
 * with mirrors - tag [NET-HTTP] / chat prefix "[LM-HTTP]".
 *
 * <p>1.7.8: in release builds the chat is fully quiet - chat duplication ([LM] / [LM-HTTP])
 * works only with {@code debugChat: true} in the config (SyncConfig#debugChatOn); the log
 * (latest.log) is always written, including the status line on world join.</p>
 *
 * <p>Why in 1.7.7-debug HTTP was "log only": with frequent polling (the old 1.7.6 mode,
 * a poll every 5 s) the chat got cluttered. After push-first (1.7.7) there is little HTTP -
 * a safety resync every 60-120 s plus morph/emote publishing - so in debug2 every HTTP
 * event is duplicated into the chat as well. If the WS dropped and the chat got flooded
 * with poll lines every ~5 s - that is the diagnosis: the client is on the fallback HTTP path.</p>
 *
 * <p>Why it matters: in the service's /health the conns field counts ALL open TCP connections
 * (HTTP with keep-alive + WebSocket + other checks/monitors), not just WS clients. The mod sees
 * only its own sockets - by matching "what I opened" (this log) with "what the server sees"
 * (the /health line in the same chat and the server log with CONN_DEBUG=1), the source of the
 * extra connections can be found.</p>
 *
 * <p>Safe to call from any thread: chat() always schedules output through
 * Minecraft.execute (like AuthClient.message).</p>
 */
@Environment(EnvType.CLIENT)
final class NetDebug {

    /** 1.7.7-debug3: lines that arrived before the player spawned (mc.player == null): chat()
     *  used to silently drop them, and "WS: connecting/OPEN" after rejoining a world did NOT
     *  appear in chat (the connection starts before the spawn) even though the WS was alive
     *  (in the log it looked like "the chat is silent but live=1"). We buffer and flush when the player appears. */
    private static final Queue<String> PENDING_CHAT = new ConcurrentLinkedQueue<>();
    /** Buffer cap before spawn: the chat is not bottomless. */
    private static final int PENDING_LIMIT = 50;

    private NetDebug() {}

    /** WebSocket channel event: always to the log, to chat per debugChat (1.7.8). */
    static void ws(String msg) {
        LucarioMod.LOGGER.info("[NET-WS] {}", msg);
        if (SyncConfig.get().debugChatOn()) chat("[LM] " + msg);
    }

    /** HTTP event (requests to mirrors): always to the log, to chat per debugChat (1.7.8). */
    static void http(String msg) {
        LucarioMod.LOGGER.info("[NET-HTTP] {}", msg);
        if (SyncConfig.get().debugChatOn()) chat("[LM-HTTP] " + msg);
    }

    /** Short UUID form for log lines: first 8 characters + "...". */
    static String shortId(Object id) {
        String s = String.valueOf(id);
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
    }

    /** Clips a long URL (a request with a hundred UUIDs is not logged in full). */
    static String clip(String url) {
        return url.length() <= 110 ? url : url.substring(0, 110) + "…(" + url.length() + " симв)";
    }

    /** 1.7.7-debug4: the mod version from fabric.mod.json - a self-presentation line on
     *  every world join (1.7.8: always to the log, to chat only with debugChat), so the
     *  log always says which build it came from ("debug2 or debug3?" - no guessing). */
    static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("lucariomod")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
        } catch (Exception e) {
            return "?";   // without a loader (tests/tools) just do not crash the caller
        }
    }

    /** To chat from any thread (lock-free: always via mc.execute).
     *  Before spawn the line goes into a buffer - flushed after the player appears. */
    private static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (PENDING_CHAT.size() < PENDING_LIMIT) PENDING_CHAT.add(text);
            return;
        }
        mc.execute(() -> drainAndSend(text));
    }

    /** 1.7.7-debug3: call from the client tick - flush the buffer accumulated before spawn. */
    static void flushChat() {
        if (PENDING_CHAT.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.execute(() -> {
            String p;
            while ((p = PENDING_CHAT.poll()) != null && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(p));
            }
        });
    }

    /** Inside mc.execute: the buffer first (in order), then the line itself. */
    private static void drainAndSend(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (PENDING_CHAT.size() < PENDING_LIMIT) PENDING_CHAT.add(text);
            return;
        }
        String p;
        while ((p = PENDING_CHAT.poll()) != null) {
            mc.player.sendSystemMessage(Component.literal(p));
        }
        mc.player.sendSystemMessage(Component.literal(text));
    }
}
