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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1.7.0: синхронизация эмоутов между игроками через сервис синхронизации
 * (сервер LucarioSync 1.5.0+). Работает на любых серверах — как и синхронизация образов.
 * <p>
 * Три канала и их роли:
 * <ol>
 *   <li><b>Публикация себя (HTTP PUT во все зеркала)</b> — момент старта/стопа эмоута.
 *       Ничего не ждём: событие улетает немедленно; если зеркала не ответили, intent
 *       повторяется каждые 3 с, пока не дойдёт (более свежее событие вытесняет старое).</li>
 *   <li><b>Refresh (PUT раз в 10 с, пока эмоут играет)</b> — держит запись живой:
 *       у записи на сервисе TTL 30 с. Вылет/обрыв связи гасит эмоут у всех максимум
 *       через ~30 с, причём «гашение» ПРИХОДИТ как обычное событие стопа.</li>
 *   <li><b>Приём (WebSocket /ws приоритетного зеркала)</b> — мгновенный пуш событий
 *       «только рядом» ({@code mc.level.players()} = отслеживаемые игроки; это и есть
 *       зона видимости). Сокет подписан списком UUID ({@code watch}); при смене списка
 *       (кто-то вошёл/вышел из зоны) список отправляется заново, а сервер сразу
 *       отвечает снимком текущих эмоутов — никто не пропадает мимо кадра.</li>
 * </ol>
 * <p>
 * Гарантии показа/прекращения: пуш → запасной HTTP-опрос раз в 2 с, пока WS закрыт →
 * снимок при каждом обновлении watch-листа → TTL сервиса (30 с) + локальная страховка
 * {@link RemoteEmotes#STALE_MS}. Порядок событий защищён отметкой отправителя (ts),
 * поэтому отставшее зеркало не перебивает свежий стоп.
 * <p>
 * Всё работает только при активной синхронизации ({@code syncActive()}): отдельный
 * выключатель не заведён — эмоуты едут вместе с образами (решение владельца мода).
 */
@Environment(EnvType.CLIENT)
public final class EmoteSync {
    private static final Gson GSON = new Gson();
    /** Refresh активного эмоута: втрое чаще TTL сервиса (30 с) — запас в 3 пропуска. */
    private static final long REFRESH_MS = 10_000L;
    /** Повтор недоставленной публикации (старт/стоп), пока зеркала не ответят 2xx. */
    private static final long RETRY_MS = 3_000L;
    /** Запасной HTTP-опрос эмоутов, пока WebSocket закрыт. */
    private static final long POLL_MS = 2_000L;
    /** Зеркало старой версии (404 на /v1/anims или /ws) — опрос реже, чтобы не молотить. */
    private static final long POLL_TOO_OLD_MS = 30_000L;
    /** Backoff переподключения WebSocket: 1, 2, 4, 8, 16, 30 (cap) секунд. */
    private static final long WS_BACKOFF_BASE_MS = 1_000L;
    private static final long WS_BACKOFF_MAX_MS = 30_000L;
    /** Старое зеркало: WS-рукопожатие отвечало 404/400 — пробуем реже. */
    private static final long WS_OLD_SERVER_MS = 60_000L;
    /** Максимум «ближних» игроков в watch-листе (лимит сервера — 100). */
    private static final int WATCH_MAX = 100;

    // ============================================================ публикация себя
    /** Наш текущий эмоут (для refresh); null — эмоута нет. */
    private static volatile String liveAnim = null;
    private static volatile long nextRefreshMs = 0L;
    /** Старт/стоп, который ещё не подтверждён всеми зеркалами. */
    private static volatile Intent pendingIntent = null;
    private static volatile long nextPublishMs = 0L;

    private static final class Intent {
        final String anim;   // "" = стоп
        final long ts;       // наши часы: сервер эхом вернёт в событиях — общий порядок
        final boolean start;

        Intent(String anim, boolean start, long ts) {
            this.anim = anim;
            this.start = start;
            this.ts = ts;
        }
    }

    // ============================================================ WebSocket-приём
    private static volatile WebSocket ws = null;
    private static volatile boolean wsOpen = false;
    private static long wsAttempt = 0;
    private static volatile long nextWsTryMs = 0L;
    private static volatile long lastWsFailLogMs = 0L;

    // ============================================================ запасной опрос
    private static volatile long nextPollMs = 0L;
    private static volatile boolean pollTooOld = false;

    /** Текущий watch-лист (ближние игроки, отсортированы — для стабильного сравнения). */
    private static volatile List<UUID> lastWatch = List.of();
    private static long lastPruneMs = 0L;

    private EmoteSync() {}

    // ============================================================ публичные хуки

    /** Эмоут начат (клавишей J / меню / командой). Вызывается с главного потока. */
    public static void publishStart(String anim) {
        if (anim == null || anim.isEmpty()) anim = "dance";
        liveAnim = anim;
        nextRefreshMs = System.currentTimeMillis() + REFRESH_MS;
        enqueueIntent(anim, true);
    }

    /** Эмоут прекращён (движение/урон/вода/переключение). Вызывается с главного потока. */
    public static void publishStop() {
        if (liveAnim == null) return;   // не играли — слать нечего (и не грузим зеркала)
        liveAnim = null;
        nextRefreshMs = 0L;
        enqueueIntent("", false);
    }

    /** При входе в мир: WS-канал и опрос перезапускаются сразу после спавна. */
    public static void onJoin() {
        nextWsTryMs = 0L;
        nextPollMs = 0L;
    }

    /**
     * При выходе из мира. Стоп НЕ публикуем: эмоут визуально продолжается и после смены
     * мира (так устроен мод), а если игрок не вернётся — запись погасит TTL сервиса
     * (те самые ~30 с, выбранное поведение). Сетевое состояние сбрасываем.
     */
    public static void onDisconnect() {
        pendingIntent = null;
        nextPublishMs = 0L;
        lastWatch = List.of();
        closeWs();
        RemoteEmotes.clear();
    }

    /** Вызывается каждый клиентский тик (после {@link SyncClient#tick}). */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) return;
        SyncConfig cfg = SyncConfig.get();
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) return;
        UUID self = mc.player == null ? null : mc.player.getUUID();
        if (self == null) return;   // ещё не заспавнились
        long now = System.currentTimeMillis();

        // 1) refresh активного эмоута («я ещё играю») — держит запись против TTL
        if (liveAnim != null && now >= nextRefreshMs) {
            nextRefreshMs = now + REFRESH_MS;
            enqueueIntent(liveAnim, true);
        }

        // 2) повтор недоставленной публикации
        Intent intent = pendingIntent;
        if (intent != null && now >= nextPublishMs) fireIntent(intent, self);

        // 3) watch-лист ближних игроков: меняется (кто-то вошёл/вышел из зоны) — отсылаем
        List<UUID> watch = nearbyIds(mc, self);
        if (!watch.equals(lastWatch)) {
            lastWatch = watch;
            sendWatch(watch);
        }

        // 4) WebSocket: переподключение по расписанию
        if (!wsOpen && now >= nextWsTryMs) connectWs(cfg, self);

        // 5) запасной HTTP-опрос, пока WS закрыт (и для зеркал старых версий — так же,
        //    но реже: они отвечают 404 на /v1/anims)
        if (!wsOpen && now >= nextPollMs) {
            nextPollMs = now + (pollTooOld ? POLL_TOO_OLD_MS : POLL_MS);
            if (!watch.isEmpty()) pollSnapshot(self, watch);
        }

        // 6) гигиена: подчищаем давно необновляемые записи
        if (now - lastPruneMs > 10_000L) {
            lastPruneMs = now;
            RemoteEmotes.prune(now);
        }
    }

    // ============================================================ публикация (HTTP PUT)

    private static void enqueueIntent(String anim, boolean start) {
        Intent intent = new Intent(anim, start, System.currentTimeMillis());
        pendingIntent = intent;
        nextPublishMs = 0L;
        UUID self = selfUuid();
        if (self != null) fireIntent(intent, self);   // не спавнились — повторит tick()
    }

    private static void fireIntent(Intent intent, UUID self) {
        SyncConfig cfg = SyncConfig.get();
        if (!cfg.syncActive() || cfg.serviceUrls.isEmpty()) {
            pendingIntent = null;
            return;
        }
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
                SyncClient.HTTP.sendAsync(req, SyncClient.boundedHandler()).whenComplete((resp, err) -> {
                    if (err != null) {
                        failed.incrementAndGet();
                    } else if (resp.statusCode() == 401) {
                        failed.incrementAndGet();
                        AuthClient.onUnauthorized();   // запись защищена — авторизуемся, как с образами
                    } else if (resp.statusCode() / 100 != 2) {
                        failed.incrementAndGet();
                    } else {
                        ok.incrementAndGet();
                    }
                    // Сравнение с pendingIntent не даёт снять с ретрая более свежее событие.
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

    // ============================================================ приём: WebSocket

    private static void connectWs(SyncConfig cfg, UUID self) {
        List<String> mirrors = new ArrayList<>(cfg.serviceUrls);
        String pref = SyncClient.preferredMirror();
        if (pref != null && mirrors.remove(pref)) mirrors.add(0, pref);
        String mirror = mirrors.isEmpty() ? null : mirrors.get(0);
        String url = wsUrl(mirror);
        if (url == null) {
            nextWsTryMs = System.currentTimeMillis() + WS_BACKOFF_MAX_MS;
            return;
        }
        try {
            SyncClient.HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(url), newListener())
                    .whenComplete((socket, err) -> {
                        if (err != null) {
                            onWsFailure(err);
                            return;
                        }
                        ws = socket;
                        wsOpen = true;
                        wsAttempt = 0;
                        // приветствие + текущий список интересов; дальше сервер пушит сам
                        sendText(socket, GSON.toJson(Map.of("type", "hello", "uuid", self.toString())));
                        sendWatch(lastWatch);
                    });
        } catch (Exception e) {
            onWsFailure(e);
        }
    }

    /**
     * Свежий слушатель на КАЖДОЕ соединение: буфер сборки фрагментированных сообщений —
     * локальное состояние, и при перекрытии старого/нового сокета они не должны делить его.
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
                onWsFailure(new IllegalStateException("ws closed: " + statusCode));
                return null;
            }

            @Override
            public void onError(WebSocket socket, Throwable error) {
                onWsFailure(error);
            }
        };
    }

    private static String wsUrl(String mirror) {
        if (mirror == null || mirror.isEmpty()) return null;
        if (mirror.startsWith("https://")) return "wss://" + mirror.substring("https://".length()) + "/ws";
        if (mirror.startsWith("http://")) return "ws://" + mirror.substring("http://".length()) + "/ws";
        return null;   // странный адрес в конфиге — WS-канал к нему не строим
    }

    private static void onWsFailure(Throwable t) {
        wsOpen = false;
        long now = System.currentTimeMillis();
        // Старое зеркало (без /ws): 404/400 на рукопожатии — пробуем реже, polling прикрывает.
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
        } else {
            long backoff = Math.min(WS_BACKOFF_BASE_MS * (1L << Math.min(wsAttempt, 5L)),
                    WS_BACKOFF_MAX_MS);
            wsAttempt++;
            nextWsTryMs = now + backoff;
        }
        if (now - lastWsFailLogMs > 60_000L) {
            lastWsFailLogMs = now;
            LucarioMod.LOGGER.info("[Lucario Emotes] WS-канал недоступен ({}), работает запасной опрос: {}",
                    oldServer ? "зеркало старой версии" : "ошибка", t.toString());
        }
    }

    private static void sendText(WebSocket socket, String text) {
        socket.sendText(text, true).whenComplete((r, err) -> {
            if (err != null) onWsFailure(err);
        });
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

    /** Сообщения сервера: пуш события «anim» и снимок «state» (после watch). */
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
            } else if (type.equals("state") && obj.has("anims") && obj.get("anims").isJsonObject()) {
                JsonObject anims = obj.getAsJsonObject("anims");
                Minecraft.getInstance().execute(() -> applySnapshot(anims));
            }
        } catch (Exception ignored) {
            // битое сообщение не должно ронять канал
        }
    }

    // ============================================================ приём: запасной HTTP-опрос

    private static void pollSnapshot(UUID self, List<UUID> watch) {
        StringBuilder q = new StringBuilder("/v1/anims?uuids=");
        for (int i = 0; i < watch.size(); i++) {
            if (i > 0) q.append(',');
            q.append(watch.get(i));
        }
        SyncClient.fetch(q.toString()).whenComplete((obj, err) -> {
            if (err != null || obj == null) return;   // ни одно зеркало не ответило — повторит tick()
            JsonObject anims = obj.has("anims") && obj.get("anims").isJsonObject()
                    ? obj.getAsJsonObject("anims") : new JsonObject();
            pollTooOld = false;   // зеркала понимают /v1/anims
            Minecraft.getInstance().execute(() -> applySnapshot(anims));
        });
    }

    /** Снимок (WS «state» или HTTP-опрос): применяем записи, отсутствующие мёртвые чистим. */
    private static void applySnapshot(JsonObject anims) {
        UUID self = selfUuid();
        List<UUID> present = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            // ключ записи — сам uuid
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

    // ============================================================ watch-лист

    /**
     * «Рядом» = отслеживаемые клиентом игроки ({@code mc.level.players()}): это ровно те,
     * кого игра уже держит в зоне видимости. Список сортируется по UUID — сравнение
     * списков (и потому частота отправки watch) не зависит от порядка отрисовки.
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

    // ============================================================ служебное

    private static void closeWs() {
        WebSocket socket = ws;
        ws = null;
        wsOpen = false;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
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
