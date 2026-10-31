package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lucario.LucarioMod;
import dev.lucario.Variants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Morph sync through an external HTTP service. Since 1.8.0 this is the only channel of
 * morph visibility: the mod is client-only and is not installed on the server.
 *
 * <p>How it works (the "Figura-like" scheme):</p>
 * <ul>
 *   <li>the client publishes its morph (UUID -> variant) to every mirror from the config - on
 *       morph change and on joining a world; if the mirrors were unreachable at that moment,
 *       the publish is retried every 30 seconds until it gets through;</li>
 *   <li>every {@code heartbeatMinutes} minutes the client quietly re-publishes the current
 *       morph (a "heartbeat") so the record does not go stale on mirrors with a short TTL
 *       (records without updates are considered abandoned and deleted); 0 - disable;</li>
 *   <li>1.7.7: a full HTTP poll of every player on the server - the fallback path. While the
 *       WS is open ({@link EmoteSync}), changes arrive instantly via push, and the poll runs
 *       only once every 60-120 s - a safety net against frames lost on reconnect and against
 *       old mirrors that have WS but no targeted morph push yet (services before 1.5.1).
 *       WS closed - the poll returns to {@code syncIntervalSeconds} and again carries the
 *       local {@link ClientMorphs} cache;</li>
 *   <li>1.7.3: if no morph is selected, the client publishes the {@link #PRESENT_MARKER}
 *       marker ("mod present, default skin") - the record is not deleted, and the backend
 *       can tell "mod without a morph" from "no mod". Incoming markers (events, snapshots,
 *       polls) are treated as "no morph"; it must not be rendered - the whitelist drops it.</li>
 * </ul>
 *
 * <p>Publishing goes to <b>all</b> mirrors at once, reading - from the first one to answer
 * (the mirror that answered successfully last time has priority). If a mirror protected a
 * UUID with a record token, the publish sends the {@code X-Lucario-Token} header; on 401
 * Mojang authorization starts automatically ({@link AuthClient}: an attempt right away,
 * then retries 2x30 s, 2x1 min and every 5 minutes). All data is validated by the {@link Variants} whitelist,
 * so garbage from someone else's mirror will not render. Any response body is capped by the
 * {@link #MAX_FETCH_BYTES} limit: a malicious mirror cannot take the client down with OOM.</p>
 */
@Environment(EnvType.CLIENT)
public final class SyncClient {
    /** Shared connection pool (visible to AuthClient - authorization goes to the same mirrors and to Mojang). */
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final Gson GSON = new Gson();
    /** Maximum UUIDs in a single read request. */
    private static final int BATCH = 100;
    /** Maximum mirror response body (1 KiB is enough for 100 UUIDs with room to spare; the cap guards against OOM). */
    private static final int MAX_FETCH_BYTES = 1024 * 1024;
    /** Pause between attempts to re-publish the morph when the mirrors did not answer. */
    private static final long RETRY_BACKOFF_MS = 30_000L;

    /**
     * 1.7.3: the "mod present, no morph selected" marker. Published instead of deleting
     * the record; the 1.5.3+ server stores it as a regular record. Not a real variant:
     * the {@link Variants} whitelist rejects it on receive, so it never renders.
     */
    public static final String PRESENT_MARKER = "__present__";

    /** The mirror that answered successfully most recently - tried first. */
    private static volatile String preferredMirror = null;
    private static long nextCycleMs = 0L;
    /** 1.7.7: safety-net full poll while the WS is alive - base 90 s +/-33% (60-120 s total).
     *  Heals push frames lost across reconnects and mirrors that have WS but no morph
     *  push yet (services before 1.5.1). */
    private static final long RESYNC_MS = 90_000L;
    private static long nextResyncMs = 0L;
    /** Next "heartbeat" - a quiet re-publish of the current morph (epoch ms; 0 = schedule not started). */
    private static volatile long nextHeartbeatMs = 0L;
    private static final AtomicBoolean lastCycleFailed = new AtomicBoolean(false);
    private static volatile long lastPublishFailLogMs = 0L;
    /** Flag: "on world join the remembered morph must be applied" (once the player has spawned). */
    private static volatile boolean pendingApply = false;
    /** Morph that could not yet be published to every mirror (candidate for retry). */
    private static volatile String pendingPublish = null;
    private static volatile long nextPublishAttemptMs = 0L;
    /** 1.7.7-debug2: throttling of /health requests (the probe itself also takes a conn and request quota). */
    private static volatile long lastHealthMs = 0L;
    /** 1.7.7-debug4: moment of the last FULL fetch (cycle or resync). Needed so the
     *  safety-net resync on WS open does not duplicate a fresh full GET: on every
     *  world join the regular cycle had already run (WS connects in ~0.5 s), and then
     *  the "safety net" did a SECOND identical GET /v1/morphs - there was nothing to insure. */
    private static volatile long lastFullFetchMs = 0L;

    private SyncClient() {}

    // ============================================================ publishing self

    /**
     * Publishes the player's state to every configured mirror. The variant is a key from
     * {@link Variants}; null/empty string ("default skin") is published as
     * {@link #PRESENT_MARKER} - a permanent "mod present" registration (1.7.3).
     */
    public static void publish(String variant) {
        String key = variant == null ? "" : variant;
        if (key.isEmpty()) key = PRESENT_MARKER;
        if (!PRESENT_MARKER.equals(key) && !Variants.isValid(key)) return;
        pendingPublish = key;
        nextPublishAttemptMs = System.currentTimeMillis() + 5_000L;
        doPublish(key);
    }

    /** One attempt to publish {@code key} to every mirror. */
    private static void doPublish(String key) {
        SyncConfig cfg = SyncConfig.get();
        // Publishing is also an external exchange: explicit consent (opt-in) is required.
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) {
            pendingPublish = null;
            return;
        }
        UUID self = selfUuid();
        if (self == null) return; // the player has not spawned yet - tick() will retry

        String body = GSON.toJson(Map.of("variant", key));
        int total = cfg.serviceUrls.size();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (String raw : cfg.serviceUrls) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(raw + "/v1/morphs/" + self))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json");
                String rec = cfg.serviceTokens.get(raw); // this mirror's record token (issued automatically)
                if (rec != null && !rec.isEmpty()) rb.header("X-Lucario-Token", rec);
                HttpRequest req = rb.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                long t0 = System.currentTimeMillis();
                HTTP.sendAsync(req, boundedHandler()).whenComplete((resp, err) -> {
                    NetDebug.http("PUT " + raw + "/v1/morphs/" + NetDebug.shortId(self) + " → "
                            + (err != null ? "ошибка: " + err : "HTTP " + resp.statusCode())
                            + " (" + (System.currentTimeMillis() - t0) + " мс)");
                    if (err != null) {
                        failed.incrementAndGet();
                        logPublishFail(raw, err);
                    } else if (resp.statusCode() == 401) {
                        failed.incrementAndGet();
                        logPublishFail(raw, new IllegalStateException(
                                "HTTP 401 — запись защищена токеном, авторизуюсь автоматически"));
                        AuthClient.onUnauthorized();
                    } else if (resp.statusCode() / 100 != 2) {
                        failed.incrementAndGet();
                        logPublishFail(raw, new IllegalStateException("HTTP " + resp.statusCode()));
                    } else {
                        ok.incrementAndGet();
                    }
                    // Every mirror has answered: on success cancel the retry; otherwise schedule one.
                    // Comparing with pendingPublish is needed so a newer publish is not taken
                    // off the retry (the player may have changed morph while this request was in flight).
                    if (ok.get() + failed.get() == total) {
                        if (failed.get() > 0) {
                            nextPublishAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
                        } else if (key.equals(pendingPublish)) {
                            pendingPublish = null; // delivered to every mirror
                        }
                    }
                });
            } catch (Exception e) {
                failed.incrementAndGet();
                logPublishFail(raw, e);
            }
        }
    }

    /** Called by AuthClient after successful authorization: immediately pushes the pending publish through. */
    public static void onAuthSuccess() {
        nextPublishAttemptMs = 0L;
    }

    private static void logPublishFail(String mirror, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastPublishFailLogMs < 60_000L) return; // do not spam the log
        synchronized (SyncClient.class) {
            if (now - lastPublishFailLogMs < 60_000L) return;
            lastPublishFailLogMs = now;
        }
        LucarioMod.LOGGER.warn("[Lucario Sync] Зеркало {} не приняло образ: {}", mirror, t.toString());
    }

    // ============================================================ game loop

    /** Called when connecting to a world (the player may not have spawned yet). */
    public static void onJoin() {
        pendingApply = true;
        nextCycleMs = 0L; // first poll - as early as possible
        nextResyncMs = 0L; // 1.7.7: first full fetch - even if the WS is already open
        nextHeartbeatMs = 0L; // the "heartbeat" schedule starts over (after the join publish)
        // 1.7.7-debug4: build self-identification - every log line now states which version
        // it was taken from and which mirrors the client lives with (no more guessing "debug2 or debug3?").
        SyncConfig cfg0 = SyncConfig.get();
        NetDebug.http("клиент LucarioMorph " + NetDebug.modVersion()
                + " | синк: " + (cfg0.syncActive() ? "вкл" : "выкл")
                + " | зеркал: " + cfg0.serviceUrls.size() + " " + cfg0.serviceUrls);
        debugHealth(); // 1.7.7-debug2: starting point of "what the server sees"
    }

    /** 1.7.7-debug4: the WS has just opened. If a full fetch happened within the last 30 s
     *  (the regular cycle on join always beats it: the WS is still connecting) and the resync
     *  would be due immediately at this point (nextResyncMs in the past or 0) -
     *  push it out to the full interval: a snapshot of the current state arrives over the WS
     *  anyway (watch -> "state"), and a fresh full GET has already happened. */
    static void onWsOpened() {
        long now = System.currentTimeMillis();
        if (now - lastFullFetchMs < 30_000L && nextResyncMs <= now) {
            nextResyncMs = now + RESYNC_MS;
            NetDebug.http("WS открыт: полный забор уже был " + (now - lastFullFetchMs)
                    + " мс назад — ресинк через " + (RESYNC_MS / 1000) + " с");
        }
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) return;
        SyncConfig cfg = SyncConfig.get();

        // Apply the remembered morph as soon as the player has spawned (the UUID comes from the server).
        if (pendingApply && mc.player != null) {
            pendingApply = false;
            String v = cfg.rememberVariant ? cfg.lastVariant : "";
            if (!v.isEmpty() && Variants.isValid(v)) {
                ClientMorphs.set(mc.player.getUUID(), v);
                // 1.8.1-pre8: every publish carries the cape flag (at join the session toggle is
                // its default "visible", so this composes to the bare key).
                publish(Variants.withCape(v, VariantPickerScreen.capeVisible)); // dropped without consent (syncActive())
            } else {
                // 1.7.3: no morph - publish the "mod present" marker (permanent registration)
                publish(PRESENT_MARKER);
            }
        }

        // Follow-up publish: the mirrors were unreachable when the morph changed.
        String pending = pendingPublish;
        if (pending != null && System.currentTimeMillis() >= nextPublishAttemptMs) {
            nextPublishAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            doPublish(pending);
        }

        // Scheduled record-authorization retries (triggered by a 401 from a mirror).
        AuthClient.tick();

        // Everything that talks to the external service (polling and the "heartbeat") requires explicit opt-in consent.
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) return;
        long now = System.currentTimeMillis();

        // "Heartbeat": every heartbeatMinutes minutes quietly re-publish the current morph
        // so the record does not go stale on mirrors with a short TTL (0 - disabled).
        // If it fails - no big deal, we retry on the next tick; no retries needed here.
        // Skip the tick if a follow-up publish is already in progress (pendingPublish) - do not duplicate the PUT.
        int hb = cfg.heartbeatMinutes == null ? 0 : cfg.heartbeatMinutes;
        if (hb > 0) {
            if (nextHeartbeatMs == 0L) {
                nextHeartbeatMs = now + hb * 60_000L; // schedule starts after the join publish
            } else if (now >= nextHeartbeatMs) {
                nextHeartbeatMs = now + hb * 60_000L;
                UUID self = selfUuid();
                String current = self == null ? null : ClientMorphs.get(self);
                if (pendingPublish == null) {
                    // 1.7.3: the heartbeat also refreshes the "mod present" marker (default skin), not only morphs.
                    // 1.8.1-pre8: the heartbeat re-publishes the key WITH the cape flag (ClientMorphs
                    // keeps the bare key for the local player - compose here).
                    doPublish(current == null || current.isEmpty() ? PRESENT_MARKER
                            : Variants.withCape(current, VariantPickerScreen.capeVisible));
                }
            }
        }

        // 1.7.7: the full poll is the fallback path. While the WS is open (EmoteSync), morph
        // changes arrive instantly via push; frequent polling here would only duplicate them
        // and load the service. Live WS -> rare safety-net resync, WS closed ->
        // regular polling at syncIntervalSeconds.
        boolean wsUp = EmoteSync.wsOpen();
        if (wsUp) {
            if (now < nextResyncMs) return;
        } else if (now < nextCycleMs) return;
        // Random jitter added to the interval so many clients do not hit the mirrors
        // in sync: +/-15% for the regular poll, +/-33% for the safety net (60-120 s total).
        long base = wsUp ? RESYNC_MS : cfg.syncIntervalSeconds * 1000L;
        long jitter = (long) ((ThreadLocalRandom.current().nextDouble() - 0.5) * base
                * (wsUp ? 0.66 : 0.3));
        if (wsUp) nextResyncMs = now + base + jitter;
        else nextCycleMs = now + base + jitter;

        debugHealth(); // 1.7.7-debug2: alongside every poll cycle - what the server sees

        UUID self = selfUuid();
        List<UUID> ids = new ArrayList<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            if (self == null || !self.equals(id)) ids.add(id);
        }
        if (ids.isEmpty()) return;
        lastFullFetchMs = now; // 1.7.7-debug4: the full fetch goes out right now
        for (int i = 0; i < ids.size(); i += BATCH) {
            fetchAndApply(mc, List.copyOf(ids.subList(i, Math.min(ids.size(), i + BATCH))), self);
        }
    }

    /** 1.7.7-debug2: GET /health of the preferred mirror - a line into the "what the server sees" trace.
     *  conns in /health counts ALL TCP connections of the process (HTTP keep-alive + WS + monitors +
     *  third-party checks), live - only the mod's WS clients. The conns-live difference (minus
     *  our own HTTP requests, visible in the trace) is exactly the third-party connections; their names (IP:port)
     *  are shown only by the server log with CONN_DEBUG=1. Throttled to 30 s so the probe itself
     *  does not inflate conns or eat the request quota. */
    static void debugHealth() {
        SyncConfig cfg = SyncConfig.get();
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastHealthMs < 30_000L) return;
        lastHealthMs = now;
        fetchJson(orderedMirrors(cfg), 0, "/health").whenComplete((obj, err) -> {
            if (err != null) {
                NetDebug.http("/health сервера → недоступно (" + err + ")");
                return;
            }
            NetDebug.http("/health сервера: conns=" + jsonStr(obj, "conns")
                    + " live=" + jsonStr(obj, "live")
                    + " players=" + jsonStr(obj, "players")
                    + " mods=" + jsonStr(obj, "mods")
                    + " версия=" + jsonStr(obj, "version"));
        });
    }

    /** JSON response field value as a string ("?" - the field is missing/the response is malformed). */
    private static String jsonStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "?";
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return String.valueOf(obj.get(key));
        }
    }

    private static void fetchAndApply(Minecraft mc, List<UUID> batch, UUID self) {
        List<String> mirrors = orderedMirrors(SyncConfig.get());
        if (mirrors.isEmpty()) return;
        StringBuilder q = new StringBuilder("/v1/morphs?uuids=");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) q.append(',');
            q.append(batch.get(i));
        }
        fetchJson(mirrors, 0, q.toString()).whenComplete((obj, err) -> {
            if (err != null) {
                if (lastCycleFailed.compareAndSet(false, true)) {
                    LucarioMod.LOGGER.warn("[Lucario Sync] Ни одно зеркало не ответило, кэш оставлен как был: {}", err.toString());
                }
                return;
            }
            if (lastCycleFailed.compareAndSet(true, false)) {
                LucarioMod.LOGGER.info("[Lucario Sync] Связь с сервисом восстановлена");
            }
            mc.execute(() -> applyBatch(batch, obj, self));
        });
    }

    /** Tries the mirrors in sequence starting with the preferred one; returns the JSON of the first to answer. */
    private static CompletableFuture<JsonObject> fetchJson(
            List<String> mirrors, int idx, String pathAndQuery) {
        if (idx >= mirrors.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("все зеркала недоступны"));
        }
        String url = mirrors.get(idx) + pathAndQuery;
        NetDebug.http("GET " + NetDebug.clip(url) + " отправляю"
                + (idx > 0 ? " [зеркало #" + (idx + 1) + "]" : ""));
        long t0 = System.currentTimeMillis();
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
        } catch (Exception e) {
            return fetchJson(mirrors, idx + 1, pathAndQuery);
        }
        return HTTP.sendAsync(req, boundedHandler()).whenComplete((resp, err) ->
                NetDebug.http("GET " + NetDebug.clip(url) + " → "
                        + (err != null ? "ошибка: " + err : "HTTP " + resp.statusCode())
                        + " (" + (System.currentTimeMillis() - t0) + " мс)")
        ).thenCompose(resp -> {
            if (resp.statusCode() == 200) {
                try {
                    JsonObject obj = GSON.fromJson(resp.body(), JsonObject.class);
                    preferredMirror = mirrors.get(idx);
                    return CompletableFuture.completedFuture(obj);
                } catch (Exception parseError) {
                    return fetchJson(mirrors, idx + 1, pathAndQuery);
                }
            }
            return fetchJson(mirrors, idx + 1, pathAndQuery);
        });
    }

    private static List<String> orderedMirrors(SyncConfig cfg) {
        List<String> out = new ArrayList<>(cfg.serviceUrls);
        String pref = preferredMirror;
        if (pref != null && out.remove(pref)) out.add(0, pref);
        return out;
    }

    /** 1.7.0: the mirror that answered successfully most recently - EmoteSync connects its WS channel to it. */
    static String preferredMirror() {
        return preferredMirror;
    }

    /** 1.7.0: GET request to the first mirror that answered (the emote cache is the fallback read channel). */
    static CompletableFuture<JsonObject> fetch(String pathAndQuery) {
        return fetchJson(orderedMirrors(SyncConfig.get()), 0, pathAndQuery);
    }

    /** Applies service responses to the render cache. Called on the client's main thread. */
    private static void applyBatch(List<UUID> batch, JsonObject obj, UUID self) {
        for (UUID id : batch) {
            if (id.equals(self)) continue; // our own morph is managed locally (MorphApplier)
            String key = id.toString();
            String v = "";
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                v = obj.get(key).getAsString();
            }
            // 1.7.3: to an observer the "mod present, no morph" marker looks like "no morph"
            if (v.isEmpty() || PRESENT_MARKER.equals(v)) {
                if (ClientMorphs.get(id) != null) ClientMorphs.set(id, ""); // morph cleared on the service
            } else if (Variants.isValid(v)) {
                if (!v.equals(ClientMorphs.get(id))) ClientMorphs.set(id, v);
            }
        }
    }

    /**
     * 1.7.2: applies SOMEONE ELSE'S morph that arrived over WebSocket (the "morph" event
     * or the "morphs" field in the "state" snapshot). The logic is exactly the same as in
     * {@link #applyBatch}: an empty string or the {@link #PRESENT_MARKER} marker = no morph
     * record (clear it), an unknown variant is dropped by the whitelist, dedup against
     * the current cache value. Our own UUID is ignored - our own morph is managed locally
     * and through publishing. Called on the main thread.
     */
    static void applyRemoteMorph(UUID id, String variant) {
        UUID self = selfUuid();
        if (self == null || id.equals(self)) return;
        if (variant.isEmpty() || PRESENT_MARKER.equals(variant)) {
            if (ClientMorphs.get(id) != null) ClientMorphs.set(id, ""); // morph cleared on the service
        } else if (Variants.isValid(variant)) {
            if (!variant.equals(ClientMorphs.get(id))) ClientMorphs.set(id, variant);
        }
    }

    // ============================================================ HTTP helpers

    /**
     * A BodySubscriber with a size ceiling: as soon as the server sends more than {@code maxBytes},
     * the connection is aborted with an error (instead of silently buffering gigabytes).
     */
    private static HttpResponse.BodySubscriber<String> boundedBody(long maxBytes) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<String> done = new CompletableFuture<>();
            private final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            private long size = 0;
            private Flow.Subscription sub;

            @Override
            public CompletionStage<String> getBody() {
                return done;
            }

            @Override
            public void onSubscribe(Flow.Subscription s) {
                sub = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> items) {
                if (done.isDone()) return;
                for (ByteBuffer b : items) {
                    int n = b.remaining();
                    if (size + n > maxBytes) {
                        sub.cancel();
                        done.completeExceptionally(new IllegalStateException(
                                "ответ зеркала больше " + (maxBytes >> 10) + " КиБ — соединение оборвано"));
                        return;
                    }
                    size += n;
                    byte[] a = new byte[n];
                    b.get(a);
                    out.write(a, 0, n);
                }
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                done.complete(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        };
    }

    static HttpResponse.BodyHandler<String> boundedHandler() {
        // BodyHandler is a functional interface: hand out a fresh subscriber for every response
        // (AuthClient uses it too - the limit applies to all responses).
        return info -> boundedBody(MAX_FETCH_BYTES);
    }

    private static UUID selfUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }
}
