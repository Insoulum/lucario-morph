package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lucario.LucarioMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1.7.0: emote sync between players through the sync service (LucarioSync server
 * 1.5.0+). Works on any server - just like morph sync.
 * <p>
 * Three channels and their roles:
 * <ol>
 *   <li><b>Publishing self (HTTP PUT to every mirror)</b> - the moment an emote starts/stops.
 *       We do not wait: the event flies out immediately; if the mirrors do not answer, the
 *       intent is retried every 3 s until it gets through (a newer event displaces the older one).</li>
 *   <li><b>Refresh (PUT every 10 s while an emote is playing)</b> - keeps the record alive:
 *       a record on the service has a TTL of 30 s. A crash/lost connection ends the emote
 *       for everyone within at most ~30 s, and the "switch-off" ARRIVES as a regular stop event.</li>
 *   <li><b>Receiving (WebSocket /ws of the preferred mirror)</b> - instant push of events
 *       for "nearby only" ({@code mc.level.players()} = tracked players = the visibility zone).
 *       The socket is subscribed with a list of UUIDs ({@code watch}); when the list changes
 *       (someone enters/leaves the zone) it is re-sent, and the server immediately replies
 *       with a snapshot of the current emotes - nobody slips past a frame. Since 1.7.2 the
 *       same socket also delivers morphs: the "morph" event and the morph half of the
 *       snapshot ("morphs") - a morph change is visible instantly, HTTP polling is the fallback.</li>
 * </ol>
 * <p>
 * Show/stop guarantees: push -> fallback HTTP polling every 2 s while the WS is closed ->
 * a snapshot on every watch-list update -> the service TTL (30 s) + the local safety net
 * {@link RemoteEmotes#STALE_MS}. Event order is protected by the sender's timestamp (ts),
 * so a lagging mirror cannot override a fresh stop.
 * <p>
 * Everything works only while sync is active ({@code syncActive()}): there is no separate
 * toggle - emotes ride along with morphs (a decision by the mod owner). Since 1.8.1-pre9
 * it also requires the {@link SyncClient} "someone is watching" gate ({@code minPlayers}):
 * alone in the world the channel is torn down and zero traffic goes out.
 */
@Environment(EnvType.CLIENT)
public final class EmoteSync {
    private static final Gson GSON = new Gson();
    /** Refresh of the active emote: 3x more often than the service TTL (30 s) - a margin of 3 misses. */
    private static final long REFRESH_MS = 10_000L;
    /** Retry of an undelivered publish (start/stop) until the mirrors answer 2xx. */
    private static final long RETRY_MS = 3_000L;
    /** Fallback HTTP polling of emotes while the WebSocket is closed. */
    private static final long POLL_MS = 2_000L;
    /** Old-version mirror (404 on /v1/anims or /ws) - poll less often to avoid hammering it. */
    private static final long POLL_TOO_OLD_MS = 30_000L;
    /** WebSocket reconnect backoff: 1, 2, 4, 8, 16, 30 (cap) seconds. */
    private static final long WS_BACKOFF_BASE_MS = 1_000L;
    private static final long WS_BACKOFF_MAX_MS = 30_000L;
    /** Old mirror: the WS handshake answered 404/400 - try less often. */
    private static final long WS_OLD_SERVER_MS = 60_000L;
    /** Maximum "nearby" players in the watch list (the server limit is 100). */
    private static final int WATCH_MAX = 100;

    // ============================================================ publishing self
    /** Our current emote (for refresh); null - no emote. */
    private static volatile String liveAnim = null;
    private static volatile long nextRefreshMs = 0L;
    /** A start/stop not yet acknowledged by every mirror. */
    private static volatile Intent pendingIntent = null;
    private static volatile long nextPublishMs = 0L;

    private static final class Intent {
        final String anim;   // "" = stop
        final long ts;       // our clock: the server echoes it back in events - total ordering
        final boolean start;

        Intent(String anim, boolean start, long ts) {
            this.anim = anim;
            this.start = start;
            this.ts = ts;
        }
    }

    // ============================================================ WebSocket receive
    private static volatile WebSocket ws = null;
    private static volatile boolean wsOpen = false;
    /** 1.7.7: batch access for SyncClient - the full HTTP poll of morphs is only needed
     *  while there is no socket (with a live WS, changes arrive via push). The field is
     *  volatile; the race is harmless: one extra or missed poll breaks nothing. */
    static boolean wsOpen() { return wsOpen; }
    /** 1.7.1: a handshake is in flight - tick() must not start parallel connects
     *  (previously every tick within a 5 s window spawned another socket: live "orphans"
     *  piled up on the service and inflated the live field of /health - the famous live: 20 with two players). */
    private static volatile boolean wsConnecting = false;
    private static long wsAttempt = 0;
    private static volatile long nextWsTryMs = 0L;
    private static volatile long lastWsFailLogMs = 0L;

    /**
     * 1.7.5: send chain. The JDK WebSocket forbids sendText while the previous send
     * has not completed yet ("Send pending"): after the handshake, hello and watch went
     * out back to back, and watch almost always ran into the still-pending hello.
     * Now every message is queued and goes out strictly after the previous one;
     * a failure of the previous send does not kill the queue.
     */
    private static CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

    // ============================================================ fallback polling
    private static volatile long nextPollMs = 0L;
    private static volatile boolean pollTooOld = false;

    /** Current watch list (nearby players, sorted - for stable comparison). */
    private static volatile List<UUID> lastWatch = List.of();
    private static long lastPruneMs = 0L;

    private EmoteSync() {}

    // ============================================================ public hooks

    /** An emote has started (J key / menu / command). Called from the main thread. */
    public static void publishStart(String anim) {
        if (anim == null || anim.isEmpty()) anim = "dance";
        liveAnim = anim;
        nextRefreshMs = System.currentTimeMillis() + REFRESH_MS;
        enqueueIntent(anim, true);
    }

    /** An emote has stopped (movement/damage/water/switching). Called from the main thread. */
    public static void publishStop() {
        if (liveAnim == null) return;   // nothing was playing - nothing to send (and we do not bother the mirrors)
        liveAnim = null;
        nextRefreshMs = 0L;
        enqueueIntent("", false);
    }

    /** On world join: the WS channel and polling restart right after spawn. */
    public static void onJoin() {
        nextWsTryMs = 0L;
        nextPollMs = 0L;
    }

    /** 1.8.1-pre9: the "someone is watching" gate opened (a viewer joined) - retry the WS
     *  channel and the fallback poll right away instead of waiting out the backoff. */
    static void nudge() {
        nextWsTryMs = 0L;
        nextPollMs = 0L;
    }

    /**
     * On leaving the world. We do NOT publish a stop: the emote visually continues after
     * changing worlds too (that is how the mod works), and if the player never returns, the
     * service TTL expires the record (the same ~30 s, chosen behavior). Network state is reset.
     */
    public static void onDisconnect() {
        pendingIntent = null;
        nextPublishMs = 0L;
        lastWatch = List.of();
        closeWs();
        RemoteEmotes.clear();
    }

    /** Called every client tick (after {@link SyncClient#tick}). */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) return;
        SyncConfig cfg = SyncConfig.get();
        // 1.8.1-pre9: the "someone is watching" gate rides along with the consent check:
        // alone in the world the channel is torn down (symmetric with turning sync off).
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty() || !SyncClient.syncGate()) {
            // 1.7.5: turning sync off on the server tears the channel down completely (symmetric
            // with turning it on): previously a live WS survived until rejoin - incoming events
            // kept arriving and live in /health never dropped. Outgoing traffic had died
            // on its own; our own records are expired by the service TTL.
            closeWs();               // idempotent
            pendingIntent = null;
            lastWatch = List.of();   // after sync is re-enabled, watch will be sent again
            RemoteEmotes.clear();
            return;
        }
        UUID self = mc.player == null ? null : mc.player.getUUID();
        NetDebug.flushChat(); // 1.7.7-debug3: flush chat lines accumulated before spawn
        if (self == null) return;   // not spawned yet
        long now = System.currentTimeMillis();

        // 1) refresh of the active emote ("I am still playing") - keeps the record alive against the TTL.
        //    1.7.7-debug4: the reason sits next to every PUT - the log shows WHY the request went out.
        if (liveAnim != null && now >= nextRefreshMs) {
            nextRefreshMs = now + REFRESH_MS;
            NetDebug.http("эмоут \"" + liveAnim + "\": refresh — держим запись живой (TTL 30 с)");
            enqueueIntent(liveAnim, true);
        }

        // 2) retry of an undelivered publish
        Intent intent = pendingIntent;
        if (intent != null && now >= nextPublishMs) fireIntent(intent, self, true);

        // 3) watch list of nearby players: on change (someone enters/leaves the zone) - send it
        List<UUID> watch = nearbyIds(mc, self);
        if (!watch.equals(lastWatch)) {
            lastWatch = watch;
            sendWatch(watch);
        }

        // 4) WebSocket: reconnect on schedule
        if (!wsOpen && now >= nextWsTryMs) connectWs(cfg, self);

        // 5) fallback HTTP polling while the WS is closed (and for old-version mirrors - the same,
        //    but less often: they answer 404 on /v1/anims)
        if (!wsOpen && now >= nextPollMs) {
            nextPollMs = now + (pollTooOld ? POLL_TOO_OLD_MS : POLL_MS);
            if (!watch.isEmpty()) pollSnapshot(self, watch);
        }

        // 6) hygiene: prune records not updated for a long time
        if (now - lastPruneMs > 10_000L) {
            lastPruneMs = now;
            RemoteEmotes.prune(now);
        }
    }

    // ============================================================ publishing (HTTP PUT)

    private static void enqueueIntent(String anim, boolean start) {
        Intent intent = new Intent(anim, start, System.currentTimeMillis());
        pendingIntent = intent;
        nextPublishMs = 0L;
        UUID self = selfUuid();
        if (self != null) fireIntent(intent, self, false);   // not spawned - tick() will retry
    }

    private static void fireIntent(Intent intent, UUID self, boolean retry) {
        // 1.7.7-debug3: the "one attempt per window" gate - the next attempt is scheduled
        // BEFORE the requests are fired. Previously nextPublishMs was updated only in the
        // callbacks, i.e. ~100-190 ms after sending, and in that time tick() managed to call
        // fireIntent 2-4 more times (20 ticks/s): every start/stop/refresh event went to the
        // mirrors as a batch of duplicates (the debug2 log shows exactly the batches "of 3-4
        // PUT /v1/anims per keypress"), and each parallel PUT held an HTTP connection of its
        // own, inflating conns in /health (2->6 jumps for a single player).
        nextPublishMs = System.currentTimeMillis() + RETRY_MS;
        SyncConfig cfg = SyncConfig.get();
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty() || !SyncClient.syncGate()) {
            pendingIntent = null;
            return;
        }
        // 1.7.7-debug4: every PUT in the trace carries a reason - start/stop/refresh/retry.
        // The line is printed only when the request REALLY goes out (after the consent gate),
        // so "publishing" does not lie, neither with sync off nor with empty mirrors.
        NetDebug.http("эмоут " + (intent.start ? "старт \"" + intent.anim + "\"" : "стоп")
                + ": PUT (зеркал: " + cfg.serviceUrls.size() + ")"
                + (retry ? " — повтор недоставленного" : ""));
        String body = GSON.toJson(Map.of(
                "anim", intent.anim,
                "state", intent.start ? "start" : "stop",
                "ts", intent.ts));
        int total = cfg.serviceUrls.size();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (String raw : cfg.serviceUrls) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(raw + "/v1/anims/" + self))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json");
                String rec = cfg.serviceTokens.get(raw);
                if (rec != null && !rec.isEmpty()) rb.header("X-Lucario-Token", rec);
                HttpRequest req = rb.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                long t0 = System.currentTimeMillis();
                SyncClient.HTTP.sendAsync(req, SyncClient.boundedHandler()).whenComplete((resp, err) -> {
                    NetDebug.http("PUT " + raw + "/v1/anims/" + NetDebug.shortId(self) + " → "
                            + (err != null ? "ошибка: " + err : "HTTP " + resp.statusCode())
                            + " (" + (System.currentTimeMillis() - t0) + " мс)");
                    if (err != null) {
                        failed.incrementAndGet();
                    } else if (resp.statusCode() == 401) {
                        failed.incrementAndGet();
                        AuthClient.onUnauthorized();   // the record is protected - authorize, same as with morphs
                    } else if (resp.statusCode() / 100 != 2) {
                        failed.incrementAndGet();
                    } else {
                        ok.incrementAndGet();
                    }
                    // Comparing with pendingIntent prevents a newer event from being taken off the retry.
                    if (ok.get() + failed.get() == total) {
                        if (failed.get() > 0) {
                            nextPublishMs = System.currentTimeMillis() + RETRY_MS;
                        } else if (pendingIntent == intent) {
                            pendingIntent = null;
                        }
                    }
                });
            } catch (Exception e) {
                failed.incrementAndGet();
                nextPublishMs = System.currentTimeMillis() + RETRY_MS;
            }
        }
    }

    // ============================================================ receive: WebSocket

    private static void connectWs(SyncConfig cfg, UUID self) {
        List<String> mirrors = new ArrayList<>(cfg.serviceUrls);
        String pref = SyncClient.preferredMirror();
        if (pref != null && mirrors.remove(pref)) mirrors.add(0, pref);
        String mirror = mirrors.isEmpty() ? null : mirrors.get(0);
        String url = wsUrl(mirror);
        if (url == null) {
            NetDebug.ws("WS: не строю адрес (зеркало без http/https) — попробую через 30 с");
            nextWsTryMs = System.currentTimeMillis() + WS_BACKOFF_MAX_MS;
            return;
        }
        // 1.7.1: the "connecting" mark is set BEFORE the handshake starts, and nextWsTryMs
        // is pushed out in advance to the timeout horizon + margin: if the callback never
        // arrives at all, the retry comes in 10 s, not 20 attempts per second.
        NetDebug.ws("WS: подключаюсь к " + url + " (попытка " + (wsAttempt + 1) + ")");
        wsConnecting = true;
        nextWsTryMs = System.currentTimeMillis() + 10_000L;
        try {
            SyncClient.HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(url), newListener())
                    .whenComplete((socket, err) -> {
                        wsConnecting = false;
                        if (err != null) {
                            onWsFailure(err);
                            return;
                        }
                        // 1.7.1: close the previous active socket (if any) - otherwise it
                        // keeps living as an "orphan" until the server timeout kills it.
                        WebSocket old = ws;
                        ws = socket;
                        wsOpen = true;
                        wsAttempt = 0;
                        if (old != null && old != socket) closeQuietly(old);
                        NetDebug.ws("WS: ОТКРЫТ → " + url);
                        SyncClient.debugHealth(); // 1.7.7-debug2: server counters into the trace right away
                        SyncClient.onWsOpened(); // 1.7.7-debug4: do not duplicate a fresh full fetch
                        // greeting + the current list of interests; from then on the server pushes on its own
                        sendText(socket, GSON.toJson(Map.of("type", "hello", "uuid", self.toString())));
                        sendWatch(lastWatch);
                    });
        } catch (Exception e) {
            wsConnecting = false;
            onWsFailure(e);
        }
    }

    /** 1.7.1: quietly close a replaced/orphaned socket (errors ignored - it is already dead). */
    private static void closeQuietly(WebSocket socket) {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "replaced");
        } catch (Exception ignored) {
        }
    }

    /**
     * A fresh listener for EVERY connection: the buffer for assembling fragmented messages is
     * local state, and when the old and the new socket overlap they must not share it.
     */
    private static WebSocket.Listener newListener() {
        return new WebSocket.Listener() {
            private final StringBuilder buf = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    String msg = buf.toString();
                    buf.setLength(0);
                    onWsMessage(msg);
                }
                socket.request(1);
                return null;
            }

            @Override
            public void onOpen(WebSocket socket) {
                socket.request(1);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
                NetDebug.ws("WS: закрыт " + (socket == ws ? "активный" : "[осиротевший]")
                        + ": code=" + statusCode
                        + (reason == null || reason.isEmpty() ? "" : ", reason=" + reason));
                // 1.7.1: only the ACTIVE socket emits the "channel down, reconnect" signal.
                // An orphaned connection dying is routine and must not wake the channel.
                if (socket == ws) {
                    onWsFailure(new IllegalStateException("ws closed: " + statusCode));
                } else {
                    socket.abort();
                }
                return null;
            }

            @Override
            public void onError(WebSocket socket, Throwable error) {
                NetDebug.ws("WS: ошибка " + (socket == ws ? "активного" : "[осиротевший]")
                        + ": " + error);
                if (socket == ws) {
                    onWsFailure(error);
                } else {
                    socket.abort();
                }
            }
        };
    }

    private static String wsUrl(String mirror) {
        if (mirror == null || mirror.isEmpty()) return null;
        if (mirror.startsWith("https://")) return "wss://" + mirror.substring("https://".length()) + "/ws";
        if (mirror.startsWith("http://")) return "ws://" + mirror.substring("http://".length()) + "/ws";
        return null;   // odd address in the config - we do not build a WS channel to it
    }

    private static void onWsFailure(Throwable t) {
        wsOpen = false;
        long now = System.currentTimeMillis();
        // Old mirror (no /ws): 404/400 on the handshake - try less often, polling covers it.
        boolean oldServer = false;
        Throwable c = t;
        while (c != null) {
            if (c instanceof WebSocketHandshakeException hse && hse.getResponse() != null) {
                int code = hse.getResponse().statusCode();
                if (code == 404 || code == 400) oldServer = true;
                break;
            }
            c = c.getCause();
        }
        if (oldServer) {
            pollTooOld = true;
            nextWsTryMs = now + WS_OLD_SERVER_MS;
            NetDebug.ws("WS: канал потерян — зеркало старой версии (" + t + "); переподключение через 60 с");
        } else {
            long backoff = Math.min(WS_BACKOFF_BASE_MS * (1L << Math.min(wsAttempt, 5L)),
                    WS_BACKOFF_MAX_MS);
            wsAttempt++;
            nextWsTryMs = now + backoff;
            NetDebug.ws("WS: канал потерян (" + t + "); переподключение через " + (backoff / 1000) + " с");
        }
        if (now - lastWsFailLogMs > 60_000L) {
            lastWsFailLogMs = now;
            LucarioMod.LOGGER.info("[Lucario Emotes] WS-канал недоступен ({}), работает запасной опрос: {}",
                    oldServer ? "зеркало старой версии" : "ошибка", t.toString());
        }
        SyncClient.debugHealth(); // 1.7.7-debug2: after losing the WS - what the server sees (conns should drop)
    }

    private static void sendText(WebSocket socket, String text) {
        synchronized (EmoteSync.class) {
            sendChain = sendChain
                    .handle((unused, prevErr) -> (WebSocket) null)   // a previous failure does not kill the queue
                    .thenCompose(v -> socket.sendText(text, true))   // goes out strictly after the previous one
                    .whenComplete((r, err) -> {
                        // a send failure = the channel is dead: tear it down and reconnect with backoff
                        if (err != null && socket == ws && wsOpen) onWsFailure(err);
                    })
                    .thenApply(w -> (Void) null);
        }
    }

    private static void sendWatch(List<UUID> ids) {
        WebSocket socket = ws;
        if (socket == null || !wsOpen) return;
        JsonArray arr = new JsonArray();
        for (UUID id : ids) arr.add(id.toString());
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "watch");
        obj.add("uuids", arr);
        sendText(socket, GSON.toJson(obj));
    }

    /** Server messages: a push of the "anim" event, a push of the "morph" event (1.7.2) and the "state"
     *  snapshot after watch - it now carries both emotes (the anims field) and morphs (the morphs field, 1.7.2). */
    private static void onWsMessage(String msg) {
        try {
            JsonObject obj = GSON.fromJson(msg, JsonObject.class);
            if (obj == null || !obj.has("type")) return;
            String type = obj.get("type").getAsString();
            if (type.equals("anim")) {
                UUID id = uuid(obj.get("uuid"));
                String anim = obj.has("anim") && !obj.get("anim").isJsonNull()
                        ? obj.get("anim").getAsString() : "";
                boolean active = obj.has("active") && obj.get("active").getAsBoolean();
                double ts = obj.has("ts") && obj.get("ts").isJsonPrimitive()
                        ? obj.get("ts").getAsDouble() : 0.0;
                if (id != null) {
                    Minecraft.getInstance().execute(() -> RemoteEmotes.apply(id, anim, active, ts));
                }
            } else if (type.equals("morph")) {
                // 1.7.2: someone else's morph changed on the service - apply it instantly (targeted push:
                // the event arrives only to the sockets watching this player).
                UUID id = uuid(obj.get("uuid"));
                String variant = obj.has("variant") && !obj.get("variant").isJsonNull()
                        ? obj.get("variant").getAsString() : "";
                if (id != null) {
                    Minecraft.getInstance().execute(() -> SyncClient.applyRemoteMorph(id, variant));
                }
            } else if (type.equals("state") && obj.has("anims") && obj.get("anims").isJsonObject()) {
                JsonObject anims = obj.getAsJsonObject("anims");
                Minecraft.getInstance().execute(() -> applySnapshot(anims));
                if (obj.has("morphs") && obj.get("morphs").isJsonObject()) {
                    JsonObject morphs = obj.getAsJsonObject("morphs");
                    Minecraft.getInstance().execute(() -> applyMorphSnapshot(morphs));
                }
            }
        } catch (Exception ignored) {
            // a malformed message must not kill the channel
        }
    }

    /**
     * 1.7.2: the morph half of the "state" snapshot (the morphs field). It covers the watch list:
     * whoever is missing from the snapshot has no record on the service (clear the cache). Morphs
     * of players OUTSIDE the watch list are not touched - regular HTTP polling handles them.
     */
    private static void applyMorphSnapshot(JsonObject morphs) {
        for (Map.Entry<String, JsonElement> e : morphs.entrySet()) {
            UUID id = uuidString(e.getKey());
            if (id == null) continue;
            String v = e.getValue() != null && e.getValue().isJsonPrimitive()
                    ? e.getValue().getAsString() : "";
            SyncClient.applyRemoteMorph(id, v);
        }
        for (UUID id : lastWatch) {
            if (!morphs.has(id.toString())) SyncClient.applyRemoteMorph(id, "");
        }
    }

    // ============================================================ receive: fallback HTTP polling

    private static void pollSnapshot(UUID self, List<UUID> watch) {
        StringBuilder q = new StringBuilder("/v1/anims?uuids=");
        for (int i = 0; i < watch.size(); i++) {
            if (i > 0) q.append(',');
            q.append(watch.get(i));
        }
        SyncClient.fetch(q.toString()).whenComplete((obj, err) -> {
            if (err != null || obj == null) return;   // no mirror answered - tick() will retry
            JsonObject anims = obj.has("anims") && obj.get("anims").isJsonObject()
                    ? obj.getAsJsonObject("anims") : new JsonObject();
            pollTooOld = false;   // the mirrors understand /v1/anims
            Minecraft.getInstance().execute(() -> applySnapshot(anims));
        });
    }

    /** A snapshot (WS "state" or HTTP polling): apply the records, prune the absent dead ones. */
    private static void applySnapshot(JsonObject anims) {
        UUID self = selfUuid();
        List<UUID> present = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            // the record key is the uuid itself
            UUID id = uuidString(e.getKey());
            if (id == null || id.equals(self)) continue;
            if (!e.getValue().isJsonObject()) continue;
            JsonObject rec = e.getValue().getAsJsonObject();
            String anim = rec.has("anim") && !rec.get("anim").isJsonNull()
                    ? rec.get("anim").getAsString() : "";
            boolean active = rec.has("active") && rec.get("active").getAsBoolean();
            double ts = rec.has("ts") && rec.get("ts").isJsonPrimitive()
                    ? rec.get("ts").getAsDouble() : 0.0;
            present.add(id);
            RemoteEmotes.apply(id, anim, active, ts);
        }
        RemoteEmotes.dropAbsentInactive(present);
    }

    // ============================================================ watch list

    /**
     * "Nearby" = the players tracked by the client ({@code mc.level.players()}): exactly those
     * the game already keeps in its visibility zone. The list is sorted by UUID - list
     * comparison (and therefore the watch send rate) does not depend on the render order.
     */
    private static List<UUID> nearbyIds(Minecraft mc, UUID self) {
        Iterable<AbstractClientPlayer> players = mc.level.players();
        List<AbstractClientPlayer> list = new ArrayList<>();
        for (AbstractClientPlayer p : players) {
            if (p.getUUID().equals(self)) continue;
            list.add(p);
        }
        if (list.size() > WATCH_MAX && mc.player != null) {
            double sx = mc.player.getX(), sy = mc.player.getY(), sz = mc.player.getZ();
            list.sort((a, b) -> Double.compare(
                    (a.getX() - sx) * (a.getX() - sx) + (a.getY() - sy) * (a.getY() - sy)
                            + (a.getZ() - sz) * (a.getZ() - sz),
                    (b.getX() - sx) * (b.getX() - sx) + (b.getY() - sy) * (b.getY() - sy)
                            + (b.getZ() - sz) * (b.getZ() - sz)));
            list.subList(WATCH_MAX, list.size()).clear();
        }
        List<UUID> ids = new ArrayList<>(list.size());
        for (AbstractClientPlayer p : list) ids.add(p.getUUID());
        ids.sort(UUID::compareTo);
        return List.copyOf(ids);
    }

    // ============================================================ utilities

    private static void closeWs() {
        WebSocket socket = ws;
        ws = null;
        wsOpen = false;
        wsConnecting = false;   // 1.7.1: reset so a fresh world join can connect right away
        if (socket != null) {
            NetDebug.ws("WS: закрываю локально (выход из мира / синк выключен)");
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
                socket.abort();   // 1.7.5: a pending send blocks the close frame - tear down TCP
            }
        }
    }

    private static UUID uuidString(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID uuid(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        return uuidString(e.getAsString());
    }

    private static UUID selfUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }
}
