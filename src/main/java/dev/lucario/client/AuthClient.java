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
 * Авторизация записи образа через session-серверы Mojang — защита от подмены
 * чужого UUID на зеркалах синхронизации. <b>Полностью автоматическая: никакой
 * команды нет</b> — игрок просто играет, защита включается сама.
 *
 * <p>Как и ванильный сервер при входе игрока, сервис «притворяется» игровым сервером:
 * клиент доказывает владение аккаунтом цепочкой challenge → join → verify, после чего
 * зеркала хранят для этого UUID токен записи и требуют его в PUT/DELETE
 * ({@code X-Lucario-Token}). Токен выдаётся сервисом и хранится в конфиге; если конфиг
 * удалить — повторная авторизация вернёт <b>тот же</b> токен, осиротевших записей не бывает.</p>
 *
 * <p>Расписание: как только зеркало ответило 401 на публикацию (значит, запись уже
 * защищена или защищается) — попытка сразу; при неудаче ретраи по нарастающей:
 * две паузы по 30 секунд, две по минуте, затем каждые 5 минут — бесконечно, пока
 * не получится. Успех сбрасывает расписание; следующий 401 (например, после сноса
 * tokens.json на сервисе) запускает его заново. Повторные 401 от ретраев публикации
 * расписание НЕ ускоряют — иначе бэкофф не работал бы. Всё выключается в конфиге
 * ({@code autoAuth: false}) — тогда session-токен Mojang вообще не используется.</p>
 *
 * <p>Безопасность:</p>
 * <ul>
 *   <li>{@code accessToken} уходит <b>только напрямую в Mojang по HTTPS</b>
 *       ({@code sessionserver.mojang.com/session/minecraft/join}) — зеркала его не видят;</li>
 *   <li>на зеркала летят лишь UUID, ник и одноразовый serverId;</li>
 *   <li>работает только с лицензионной сессией (у офлайн-клиентов join Mojang отклонит);
 *       незащищённые UUID продолжают писаться открыто — защита opt-in.</li>
 * </ul>
 *
 * <p>Устойчивость (anti-deadlock): ни одного лока — только {@link AtomicBoolean}/volatile;
 * вся цепочка асинхронная (потоки HttpClient), рендер-поток никогда не блокируется;
 * у каждого запроса свой таймаут; одновременная авторизация невозможна; расписание
 * продвигается из игрового тика и переживает вылет цепочки (release через finally).</p>
 */
@Environment(EnvType.CLIENT)
public final class AuthClient {
    private static final Gson GSON = new Gson();
    /** Session-сервер Mojang: accessToken ходит только сюда и только по HTTPS. */
    private static final String MOJANG_JOIN = "https://sessionserver.mojang.com/session/minecraft/join";

    /** Только одна цепочка challenge → join → verify за раз. */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    /** Запись нужна: зеркало ответило 401, и с тех пор успеха не было. */
    private static final AtomicBoolean NEEDED = new AtomicBoolean(false);
    /** Попыток сделано в текущем цикле (успех или новый 401 после успеха сбрасывают). */
    private static final AtomicInteger ATTEMPTS = new AtomicInteger(0);
    /** Раньше этого момента (мс) следующую попытку не начинаем. */
    private static volatile long nextAttemptMs = 0L;
    /** Offline-сервер уже распознан — предупреждение показываем один раз за сессию игры. */
    private static boolean offlineWarned = false;

    private AuthClient() {}

    /** Зеркало ответило 401 — запись нужна. Первая попытка сразу, дальше по расписанию. */
    public static void onUnauthorized() {
        if (!Boolean.TRUE.equals(SyncConfig.get().autoAuth)) return;
        if (NEEDED.compareAndSet(false, true)) {
            ATTEMPTS.set(0);      // новый цикл: расписание с нуля
            nextAttemptMs = 0L;
        }
        tryRun();
    }

    /** Отключение от сервера: сбрасываем незавершённый цикл авторизации. */
    public static void reset() {
        NEEDED.set(false);
        ATTEMPTS.set(0);
        nextAttemptMs = 0L;
    }

    /** Вызывается каждый клиентский тик (из {@link SyncClient#tick}): продвигает расписание. */
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
            NEEDED.set(false); // защищать нечего; при следующем 401 цикл начнётся заново
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
            // Offline-сервер: UUID игрока вычислен из ника и не совпадает с профилем Mojang —
            // join всё равно отклонят, так что не долбим session-сервер бесконечно.
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

            // Цепочка по зеркалам ПОСЛЕДОВАТЕЛЬНО: Mojang принимает 6 join/30 сек на аккаунт,
            // и при параллельном старте несколько зеркал могли бы упереться в лимит.
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
                    IN_FLIGHT.set(false); // release гарантирован даже при сбое в fail/succeed
                }
            });
            launched = true;
        } catch (Exception e) {
            fail(mc, rootMessage(e));
        } finally {
            if (!launched) IN_FLIGHT.set(false);
        }
    }

    /** Пауза после n-й неудачи: 30 с, 30 с, минута, минута, дальше каждые 5 минут — бесконечно. */
    private static long delayAfterFailureMs(int n) {
        if (n <= 2) return 30_000L;
        if (n <= 4) return 60_000L;
        return 5 * 60_000L;
    }

    /** Неудача: планирует следующую попытку; в чат — один раз за цикл, дальше только лог. */
    private static void fail(Minecraft mc, String reason) {
        int n = ATTEMPTS.get();
        long delay = delayAfterFailureMs(n);
        nextAttemptMs = System.currentTimeMillis() + delay;
        if (n == 1) {
            message(mc, Component.translatable("lucariomod.auth.failed", reason));
        }
        if (n <= 4 || n % 12 == 0) { // первые попытки и далее раз в час — без спама
            LucarioMod.LOGGER.warn("[Lucario Sync] Авторизация записи не удалась (попытка {}, следующая через {} с): {}",
                    n, delay / 1000, reason);
        }
    }

    /** Успех: сбрасывает расписание, сохраняет токены, доталкивает отложенную публикацию. */
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

    /** Цепочка challenge → join → verify для одного зеркала; токен кладётся в {@code got},
     *  ошибка — в {@code errors}. Ошибка одного зеркала не роняет остальные. */
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

    /** Собирает причину неудачи из ошибок по зеркалам (или из корневого исключения). */
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

    // ------------------------------------------------------------ HTTP-помощники

    /** POST на конкретное зеркало; ответ — JSON-объект (тело ограничено, как и у чтения). */
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

    /** Шаг 2: POST join на session-сервер Mojang. true — Mojang ответил 204 (успех). */
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

    // ------------------------------------------------------------ утилиты

    /** Сообщение в чат из любого потока (без блокировок: всегда через mc.execute). */
    private static void message(Minecraft mc, Component text) {
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[Lucario] ").append(text));
            }
        });
    }

    /** Разворачивает CompletionException-цепочку до корня и возвращает человекочитаемый текст. */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.toString() : m;
    }
}
