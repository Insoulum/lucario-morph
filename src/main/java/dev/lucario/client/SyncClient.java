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
 * Синхронизация образов через внешний HTTP-сервис — для серверов, где мод <b>не</b> установлен.
 *
 * <p>Как это работает (схема «как у Figura»):</p>
 * <ul>
 *   <li>клиент публикует свой образ (UUID → вариант) на все зеркала из конфига — при смене
 *       образа и при входе в мир; если зеркала в тот момент были недоступны, публикация
 *       повторяется раз в 30 секунд, пока не дойдёт;</li>
 *   <li>раз в {@code heartbeatMinutes} минут клиент тихо перепубликует текущий образ
 *       («пульс»), чтобы запись не протухла на зеркалах с коротким TTL (записи без
 *       обновлений считаются брошенными и удаляются); 0 — выключить;</li>
 *   <li>1.7.7: полный HTTP-опрос всех игроков на сервере — запасной путь. Пока WS открыт
 *       ({@link EmoteSync}), изменения приходят пушем мгновенно, и опрос ходит лишь раз
 *       в 60–120 с — страховка от потерянных при переподключении фреймов и от старых
 *       зеркал, где WS есть, а адресного пуша морфов ещё нет (сервисы до 1.5.1).
 *       WS закрыт — опрос возвращается к {@code syncIntervalSeconds} и снова несёт
 *       локальный кэш {@link ClientMorphs};</li>
 *   <li>1.7.3: если морф не выбран, клиент публикует маркер {@link #PRESENT_MARKER}
 *       («мод есть, дефолтный скин») — запись не удаляется, и база отличает «мод без
 *       морфа» от «мода нет». Входящие маркеры (события, снапшоты, опрос) трактуются
 *       как «образа нет»; отрисовке маркер не подлежит — белый список его отбрасывает.</li>
 * </ul>
 *
 * <p>Публикация идёт во <b>все</b> зеркала сразу, чтение — с первого ответившего
 * (приоритет у зеркала, которое успешно ответило в прошлый раз). Если зеркало защищило
 * UUID токеном записи, публикация шлёт заголовок {@code X-Lucario-Token}; при 401
 * автоматически запускается авторизация через Mojang ({@link AuthClient}: попытка сразу,
 * затем ретраи 2×30 с, 2×1 мин и каждые 5 минут). Все данные проверяются белым списком {@link Variants}, поэтому мусор
 * с чужого зеркала не отрисуется. Тело любого ответа обрезается лимитом
 * {@link #MAX_FETCH_BYTES}: злое зеркало не сможет уронить клиент по памяти.</p>
 */
@Environment(EnvType.CLIENT)
public final class SyncClient {
    /** Общий пул соединений (виден AuthClient — авторизация ходит в те же зеркала и в Mojang). */
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final Gson GSON = new Gson();
    /** Максимум UUID в одном запросе на чтение. */
    private static final int BATCH = 100;
    /** Максимум тела ответа зеркала (1 КиБ хватит на 100 UUID с запасом; лимит — защита от OOM). */
    private static final int MAX_FETCH_BYTES = 1024 * 1024;
    /** Пауза между попытками допубликовать образ, если зеркала не ответили. */
    private static final long RETRY_BACKOFF_MS = 30_000L;

    /**
     * 1.7.3: маркер «мод есть, морф не выбран». Публикуется вместо удаления записи,
     * сервер 1.5.3+ хранит его как обычную запись. Не настоящий вариант: белый список
     * {@link Variants} отбрасывает его на приёме, поэтому он никогда не отрисуется.
     */
    public static final String PRESENT_MARKER = "__present__";

    /** Зеркало, которое последним успешно ответило — пробуем его первым. */
    private static volatile String preferredMirror = null;
    private static long nextCycleMs = 0L;
    /** 1.7.7: страховочный полный опрос при живом WS — база 90 с ±33% (итого 60–120 с).
     *  Лечит потерянные при переподключениях пуш-фреймы и зеркала, где WS есть, а пуша
     *  морфов ещё нет (сервисы до 1.5.1). */
    private static final long RESYNC_MS = 90_000L;
    private static long nextResyncMs = 0L;
    /** Следующий «пульс» — тихая перепубликация текущего образа (мс epoch; 0 = расписание не начато). */
    private static volatile long nextHeartbeatMs = 0L;
    private static final AtomicBoolean lastCycleFailed = new AtomicBoolean(false);
    private static volatile long lastPublishFailLogMs = 0L;
    /** Флаг «при входе в мир надо применить запомненный образ» (когда игрок заспавнится). */
    private static volatile boolean pendingApply = false;
    /** Образ, который ещё не удалось опубликовать на все зеркала (кandidat для ретрая). */
    private static volatile String pendingPublish = null;
    private static volatile long nextPublishAttemptMs = 0L;

    private SyncClient() {}

    // ============================================================ публикация себя

    /**
     * Публикует состояние игрока на все настроенные зеркала. Вариант — ключ из
     * {@link Variants}; null/пустая строка («дефолтный скин») публикуется как
     * {@link #PRESENT_MARKER} — постоянная регистрация «мод есть» (1.7.3).
     */
    public static void publish(String variant) {
        String key = variant == null ? "" : variant;
        if (key.isEmpty()) key = PRESENT_MARKER;
        if (!PRESENT_MARKER.equals(key) && !Variants.isValid(key)) return;
        pendingPublish = key;
        nextPublishAttemptMs = System.currentTimeMillis() + 5_000L;
        doPublish(key);
    }

    /** Одна попытка опубликовать {@code key} на все зеркала. */
    private static void doPublish(String key) {
        SyncConfig cfg = SyncConfig.get();
        // Публикация — тоже внешний обмен: требуется явное согласие (opt-in).
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) {
            pendingPublish = null;
            return;
        }
        UUID self = selfUuid();
        if (self == null) return; // игрок ещё не заспавнился — ретрай сделает tick()

        String body = GSON.toJson(Map.of("variant", key));
        int total = cfg.serviceUrls.size();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (String raw : cfg.serviceUrls) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(raw + "/v1/morphs/" + self))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json");
                String rec = cfg.serviceTokens.get(raw); // токен записи этого зеркала (выдаётся автоматически)
                if (rec != null && !rec.isEmpty()) rb.header("X-Lucario-Token", rec);
                HttpRequest req = rb.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                HTTP.sendAsync(req, boundedHandler()).whenComplete((resp, err) -> {
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
                    // Ответили все зеркала: успех — снимаем с ретрая; иначе назначаем ретрай.
                    // Сравнение с pendingPublish нужно, чтобы не снять с ретрая более свежую
                    // публикацию (игрок мог сменить образ, пока этот запрос летел).
                    if (ok.get() + failed.get() == total) {
                        if (failed.get() > 0) {
                            nextPublishAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
                        } else if (key.equals(pendingPublish)) {
                            pendingPublish = null; // доставлено во все зеркала
                        }
                    }
                });
            } catch (Exception e) {
                failed.incrementAndGet();
                logPublishFail(raw, e);
            }
        }
    }

    /** Вызывается AuthClient после успешной авторизации: сразу доталкиваем отложенную публикацию. */
    public static void onAuthSuccess() {
        nextPublishAttemptMs = 0L;
    }

    private static void logPublishFail(String mirror, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastPublishFailLogMs < 60_000L) return; // не спамим в лог
        synchronized (SyncClient.class) {
            if (now - lastPublishFailLogMs < 60_000L) return;
            lastPublishFailLogMs = now;
        }
        LucarioMod.LOGGER.warn("[Lucario Sync] Зеркало {} не приняло образ: {}", mirror, t.toString());
    }

    // ============================================================ игровой цикл

    /** Вызывается при подключении к миру (игрок ещё мог не заспавниться). */
    public static void onJoin() {
        pendingApply = true;
        nextCycleMs = 0L; // первый опрос — как можно раньше
        nextResyncMs = 0L; // 1.7.7: первый полный забор — даже если WS уже открыт
        nextHeartbeatMs = 0L; // расписание «пульса» начнётся заново (после публикации при входе)
    }

    /** Вызывается каждый клиентский тик. */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) return;
        SyncConfig cfg = SyncConfig.get();

        // Применяем запомненный образ, как только игрок заспавнился (UUID выдаёт сервер).
        if (pendingApply && mc.player != null) {
            pendingApply = false;
            String v = cfg.rememberVariant ? cfg.lastVariant : "";
            if (!v.isEmpty() && Variants.isValid(v)) {
                ClientMorphs.set(mc.player.getUUID(), v);
                publish(v); // без согласия (syncActive()) публикация просто сбросится
            } else {
                // 1.7.3: морфа нет — публикуем маркер «мод есть» (постоянная регистрация)
                publish(PRESENT_MARKER);
            }
        }

        // Допубликация: зеркала были недоступны в момент смены образа.
        String pending = pendingPublish;
        if (pending != null && System.currentTimeMillis() >= nextPublishAttemptMs) {
            nextPublishAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            doPublish(pending);
        }

        // Ретраи авторизации записи по расписанию (запускаются 401-м от зеркала).
        AuthClient.tick();

        // Всё, что ходит во внешний сервис (опрос и «пульс»), — только с явного согласия (opt-in).
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) return;
        long now = System.currentTimeMillis();

        // «Пульс»: раз в heartbeatMinutes минут тихо перепубликуем текущий образ,
        // чтобы запись не протухла на зеркалах с коротким TTL (0 — выключено).
        // Упало — не страшно, повторим на следующем такте; ретраи тут не нужны.
        // Такт пропускаем, если уже идёт допубликация (pendingPublish) — не дублируем PUT.
        int hb = cfg.heartbeatMinutes == null ? 0 : cfg.heartbeatMinutes;
        if (hb > 0) {
            if (nextHeartbeatMs == 0L) {
                nextHeartbeatMs = now + hb * 60_000L; // старт расписания после публикации при входе
            } else if (now >= nextHeartbeatMs) {
                nextHeartbeatMs = now + hb * 60_000L;
                UUID self = selfUuid();
                String current = self == null ? null : ClientMorphs.get(self);
                if (pendingPublish == null) {
                    // 1.7.3: пульс продлевает и маркер «мод есть» (дефолтный скин), не только морф
                    doPublish(current == null || current.isEmpty() ? PRESENT_MARKER : current);
                }
            }
        }

        // 1.7.7: полный опрос — запасной путь. Пока WS открыт (EmoteSync), изменения
        // морфов приходят пушем мгновенно; частый опрос здесь лишь дублировал бы их
        // нагрузкой на сервис. Живой WS -> редкий страховочный ресинк, WS закрыт ->
        // обычный опрос по syncIntervalSeconds.
        boolean wsUp = EmoteSync.wsOpen();
        if (wsUp) {
            if (now < nextResyncMs) return;
        } else if (now < nextCycleMs) return;
        // Случайный разброс к интервалу, чтобы множество клиентов не били в зеркала
        // синхронно: ±15% у обычного опроса, ±33% у страховки (итого 60–120 с).
        long base = wsUp ? RESYNC_MS : cfg.syncIntervalSeconds * 1000L;
        long jitter = (long) ((ThreadLocalRandom.current().nextDouble() - 0.5) * base
                * (wsUp ? 0.66 : 0.3));
        if (wsUp) nextResyncMs = now + base + jitter;
        else nextCycleMs = now + base + jitter;

        UUID self = selfUuid();
        List<UUID> ids = new ArrayList<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            if (self == null || !self.equals(id)) ids.add(id);
        }
        if (ids.isEmpty()) return;
        for (int i = 0; i < ids.size(); i += BATCH) {
            fetchAndApply(mc, List.copyOf(ids.subList(i, Math.min(ids.size(), i + BATCH))), self);
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

    /** Последовательно пробует зеркала, начиная с приоритетного; возвращает JSON первого ответившего. */
    private static CompletableFuture<JsonObject> fetchJson(
            List<String> mirrors, int idx, String pathAndQuery) {
        if (idx >= mirrors.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("все зеркала недоступны"));
        }
        String url = mirrors.get(idx) + pathAndQuery;
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
        } catch (Exception e) {
            return fetchJson(mirrors, idx + 1, pathAndQuery);
        }
        return HTTP.sendAsync(req, boundedHandler()).thenCompose(resp -> {
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

    /** 1.7.0: зеркало, которое последним успешно ответило — EmoteSync подключает к нему WS-канал. */
    static String preferredMirror() {
        return preferredMirror;
    }

    /** 1.7.0: GET-запрос к первому ответившему зеркалу (кэш эмоутов — запасной канал чтения). */
    static CompletableFuture<JsonObject> fetch(String pathAndQuery) {
        return fetchJson(orderedMirrors(SyncConfig.get()), 0, pathAndQuery);
    }

    /** Применяет ответы сервиса к кэшу рендера. Вызывается на главном потоке клиента. */
    private static void applyBatch(List<UUID> batch, JsonObject obj, UUID self) {
        for (UUID id : batch) {
            if (id.equals(self)) continue; // свой образ управляется локально/сервером
            String key = id.toString();
            String v = "";
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                v = obj.get(key).getAsString();
            }
            // 1.7.3: маркер «мод есть, морфа нет» для наблюдателя выглядит как «образа нет»
            if (v.isEmpty() || PRESENT_MARKER.equals(v)) {
                if (ClientMorphs.get(id) != null) ClientMorphs.set(id, ""); // образ снят на сервисе
            } else if (Variants.isValid(v)) {
                if (!v.equals(ClientMorphs.get(id))) ClientMorphs.set(id, v);
            }
        }
    }

    /**
     * 1.7.2: применяет ЧУЖОЙ морф, пришедший по WebSocket (событие "morph" или поле
     * "morphs" в снапшоте "state"). Логика ровно та же, что в {@link #applyBatch}:
     * пустая строка или маркер {@link #PRESENT_MARKER} = записи-морфа нет (снять),
     * неизвестный вариант отбрасывается белым списком, дедуп по текущему значению
     * кэша. Свой UUID игнорируется — свой образ управляется локально и через
     * публикацию. Вызывается на главном потоке.
     */
    static void applyRemoteMorph(UUID id, String variant) {
        UUID self = selfUuid();
        if (self == null || id.equals(self)) return;
        if (variant.isEmpty() || PRESENT_MARKER.equals(variant)) {
            if (ClientMorphs.get(id) != null) ClientMorphs.set(id, ""); // образ снят на сервисе
        } else if (Variants.isValid(variant)) {
            if (!variant.equals(ClientMorphs.get(id))) ClientMorphs.set(id, variant);
        }
    }

    // ============================================================ HTTP-помощники

    /**
     * BodySubscriber с потолком размера: как только сервер отдаёт больше {@code maxBytes},
     * соединение обрывается с ошибкой (вместо молчаливого буферизации гигабайтов).
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
        // BodyHandler — функциональный интерфейс: на каждый ответ выдаём свежий subscriber
        // (используется и AuthClient — лимит действует на все ответы).
        return info -> boundedBody(MAX_FETCH_BYTES);
    }

    private static UUID selfUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }
}
