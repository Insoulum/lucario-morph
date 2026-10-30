package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lucario.LucarioMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authorization of a morph record via the Mojang session servers - protection
 * against UUID spoofing on the sync mirrors. <b>Fully automatic: there is no
 * command</b> - the player just plays, and protection kicks in by itself.
 *
 * <p>Just like a vanilla server when a player joins, the service "impersonates" a game
 * server: the client proves account ownership through the challenge → join → verify chain,
 * after which the mirrors store a write token for this UUID and require it in PUT/DELETE
 * ({@code X-Lucario-Token}). The token is issued by the service and kept in the config;
 * deleting the config brings back <b>the same</b> token on re-authorization - orphaned records never happen.</p>
 *
 * <p>Schedule: as soon as a mirror replies 401 to a publish (the record is already
 * protected or being protected) - an attempt follows immediately; on failure, retries
 * escalate: two 30-second pauses, two one-minute ones, then every 5 minutes - forever,
 * until it succeeds. Success resets the schedule; the next 401 (for example, after
 * tokens.json is wiped on the service) starts it again. Repeated 401s from publish
 * retries do NOT speed up the schedule - otherwise the backoff would be pointless.
 * Everything is switched off in the config ({@code autoAuth: false}) - then the Mojang session token is unused.</p>
 *
 * <p>Security:</p>
 * <ul>
 *   <li>{@code accessToken} is sent <b>only directly to Mojang over HTTPS</b>
 *       ({@code sessionserver.mojang.com/session/minecraft/join}) - the mirrors never see it;</li>
 *   <li>only the UUID, the nickname, and a one-time serverId fly to the mirrors;</li>
 *   <li>works only with a licensed session (Mojang rejects join for offline clients);
 *       unprotected UUIDs keep being written openly - protection is opt-in.</li>
 * </ul>
 *
 * <p>Resilience (anti-deadlock): not a single lock - only {@link AtomicBoolean}/volatile;
 * the whole chain is asynchronous (HttpClient threads), the render thread is never blocked;
 * every request has its own timeout; concurrent authorization is impossible; the schedule
 * is advanced from the game tick and survives a chain crash (release via finally).</p>
 */
@Environment(EnvType.CLIENT)
public final class AuthClient {
    private static final Gson GSON = new Gson();
    /** Mojang session server: accessToken goes only here and only over HTTPS. */
    private static final String MOJANG_JOIN = "https://sessionserver.mojang.com/session/minecraft/join";

    /** Only one challenge → join → verify chain at a time. */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    /** Record needed: a mirror replied 401, and there has been no success since. */
    private static final AtomicBoolean NEEDED = new AtomicBoolean(false);
    /** Attempts made in the current cycle (success, or a new 401 after success, resets it). */
    private static final AtomicInteger ATTEMPTS = new AtomicInteger(0);
    /** No next attempt starts before this moment (ms). */
    private static volatile long nextAttemptMs = 0L;
    /** Offline server already detected - the warning is shown once per game session. */
    private static boolean offlineWarned = false;

    private AuthClient() {}

    /** A mirror replied 401 - record needed. First attempt immediately, then on schedule. */
    public static void onUnauthorized() {
        if (!Boolean.TRUE.equals(SyncConfig.get().autoAuth)) return;
        if (NEEDED.compareAndSet(false, true)) {
            ATTEMPTS.set(0);      // new cycle: schedule starts from scratch
            nextAttemptMs = 0L;
        }
        tryRun();
    }

    /** Disconnect from the server: resets an unfinished authorization cycle. */
    public static void reset() {
        NEEDED.set(false);
        ATTEMPTS.set(0);
        nextAttemptMs = 0L;
    }

    /** Called every client tick (from {@link SyncClient#tick}): advances the schedule. */
    public static void tick() {
        if (NEEDED.get()
                && !IN_FLIGHT.get()
                && System.currentTimeMillis() >= nextAttemptMs) {
            tryRun();
        }
    }

    private static void tryRun() {
        if (IN_FLIGHT.compareAndSet(false, true)) run();
    }

    private static void run() {
        Minecraft mc = Minecraft.getInstance();
        SyncConfig cfg = SyncConfig.get();
        if (!cfg.syncEnabled || cfg.serviceUrls.isEmpty()) {
            NEEDED.set(false); // nothing to protect; the next 401 starts the cycle anew
            IN_FLIGHT.set(false);
            return;
        }
        boolean launched = false;
        try {
            int n = ATTEMPTS.incrementAndGet();
            User user = mc.getUser();
            if (mc.player == null || user == null || user.getAccessToken() == null
                    || user.getAccessToken().isBlank()) {
                fail(mc, "нет лицензионной сессии Minecraft");
                return;
            }
            // Offline server: the player UUID is derived from the nickname and does not match
            // the Mojang profile - join would be rejected anyway, so do not hammer the session server endlessly.
            UUID sessionProfile = mc.getGameProfile() != null ? mc.getGameProfile().id() : null;
            if (sessionProfile != null && !sessionProfile.equals(mc.player.getUUID())) {
                NEEDED.set(false);
                if (!offlineWarned) {
                    offlineWarned = true;
                    message(mc, Component.translatable("lucariomod.auth.offline_server"));
                }
                return;
            }
            if (n == 1) {
                LucarioMod.LOGGER.info(
                        "[Lucario Sync] Зеркало требует токен записи — авторизуюсь через Mojang (зеркал: {})",
                        cfg.serviceUrls.size());
            }
            String name = user.getName();
            String uuid = mc.player.getUUID().toString();
            String accessToken = user.getAccessToken();

            // The chain runs across mirrors SEQUENTIALLY: Mojang allows 6 joins/30 sec per account,
            // and with a parallel start several mirrors could hit that limit.
            Map<String, String> got = new ConcurrentHashMap<>();
            Map<String, String> errors = new ConcurrentHashMap<>();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String mirror : cfg.serviceUrls) {
                chain = chain.thenCompose(unused ->
                        authMirror(mirror, name, uuid, accessToken, got, errors));
            }
            chain.whenComplete((unused, err) -> {
                try {
                    if (got.isEmpty()) fail(mc, combine(errors, err));
                    else succeed(mc, got);
                } finally {
                    IN_FLIGHT.set(false); // release is guaranteed even if fail/succeed throws
                }
            });
            launched = true;
        } catch (Exception e) {
            fail(mc, rootMessage(e));
        } finally {
            if (!launched) IN_FLIGHT.set(false);
        }
    }

    /** Pause after the n-th failure: 30 s, 30 s, a minute, a minute, then every 5 minutes - forever. */
    private static long delayAfterFailureMs(int n) {
        if (n <= 2) return 30_000L;
        if (n <= 4) return 60_000L;
        return 5 * 60_000L;
    }

    /** Failure: schedules the next attempt; to chat once per cycle, after that log only. */
    private static void fail(Minecraft mc, String reason) {
        int n = ATTEMPTS.get();
        long delay = delayAfterFailureMs(n);
        nextAttemptMs = System.currentTimeMillis() + delay;
        if (n == 1) {
            message(mc, Component.translatable("lucariomod.auth.failed", reason));
        }
        if (n <= 4 || n % 12 == 0) { // the first attempts and then once an hour - no spam
            LucarioMod.LOGGER.warn("[Lucario Sync] Авторизация записи не удалась (попытка {}, следующая через {} с): {}",
                    n, delay / 1000, reason);
        }
    }

    /** Success: resets the schedule, saves the tokens, flushes the pending publish. */
    private static void succeed(Minecraft mc, Map<String, String> got) {
        NEEDED.set(false);
        ATTEMPTS.set(0);
        nextAttemptMs = 0L;
        mc.execute(() -> {
            SyncConfig cfg = SyncConfig.get();
            cfg.serviceTokens.putAll(got);
            cfg.save();
            message(mc, Component.translatable("lucariomod.auth.protected", got.size()));
            SyncClient.onAuthSuccess();
        });
    }

    /** The challenge → join → verify chain for one mirror; the token goes into {@code got},
     *  an error into {@code errors}. One mirror's failure does not sink the others. */
    private static CompletableFuture<Void> authMirror(String mirror, String name, String uuid,
                                                      String accessToken,
                                                      Map<String, String> got, Map<String, String> errors) {
        String chBody = GSON.toJson(Map.of("uuid", uuid, "name", name));
        return post(mirror + "/v1/auth/challenge", chBody).thenCompose(challenge -> {
            String serverId = challenge.get("serverId").getAsString();
            String joinBody = GSON.toJson(Map.of(
                    "accessToken", accessToken,
                    "selectedProfile", uuid.replace("-", ""),
                    "serverId", serverId));
            return mojangJoin(joinBody).thenCompose(joined -> {
                if (!joined) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Mojang отклонил join (нужна лицензионная сессия)"));
                }
                String vBody = GSON.toJson(Map.of("uuid", uuid, "name", name, "serverId", serverId));
                return post(mirror + "/v1/auth/verify", vBody);
            });
        }).thenCompose(verified -> {
            if (verified == null || !verified.has("token") || verified.get("token").isJsonNull()) {
                return CompletableFuture.failedFuture(new IllegalStateException("в ответе нет токена"));
            }
            String token = verified.get("token").getAsString();
            if (token.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalStateException("пустой токен"));
            }
            got.put(mirror, token);
            return CompletableFuture.completedFuture((Void) null);
        }).handle((res, err) -> {
            if (err != null) errors.put(mirror, rootMessage(err));
            return null;
        });
    }

    /** Builds the failure reason from the per-mirror errors (or from the root exception). */
    private static String combine(Map<String, String> errors, Throwable err) {
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : errors.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append(": ").append(e.getValue());
            }
            return sb.toString();
        }
        return err == null ? "зеркала не ответили" : rootMessage(err);
    }

    // ------------------------------------------------------------ HTTP helpers

    /** POST to a specific mirror; the response is a JSON object (body capped, same as for reads). */
    private static CompletableFuture<JsonObject> post(String url, String body) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        return SyncClient.HTTP.sendAsync(req, SyncClient.boundedHandler()).thenCompose(resp -> {
            if (resp.statusCode() / 100 != 2) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("HTTP " + resp.statusCode()));
            }
            try {
                JsonObject obj = GSON.fromJson(resp.body(), JsonObject.class);
                return obj == null
                        ? CompletableFuture.failedFuture(new IllegalStateException("пустой ответ"))
                        : CompletableFuture.completedFuture(obj);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    /** Step 2: POST join to the Mojang session server. true - Mojang replied 204 (success). */
    private static CompletableFuture<Boolean> mojangJoin(String body) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(MOJANG_JOIN))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        return SyncClient.HTTP.sendAsync(req, SyncClient.boundedHandler())
                .thenApply(resp -> resp.statusCode() == 204);
    }

    // ------------------------------------------------------------ utilities

    /** Chat message from any thread (lock-free: always via mc.execute). */
    private static void message(Minecraft mc, Component text) {
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[Lucario] ").append(text));
            }
        });
    }

    /** Unwraps the CompletionException chain to the root and returns human-readable text. */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.toString() : m;
    }
}
