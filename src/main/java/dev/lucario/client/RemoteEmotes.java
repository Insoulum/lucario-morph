package dev.lucario.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 1.7.0: эмоуты ДРУГИХ игроков, полученные через сервис синхронизации
 * ({@link EmoteSync} — WebSocket-пуш, а при его недоступности запасной HTTP-опрос).
 * <p>
 * Раньше эмоут был локальным (см. заметку в {@link DanceState}): {@code dancing} в
 * {@code AvatarRendererMixin} вычислялся только для своего игрока. Теперь состояние
 * эмоута — пер-игроковое: у каждого игрока своё имя анимации и свои часы.
 * <p>
 * Как устроена запись:
 * <ul>
 *   <li>{@code anim} — короткое имя анимации или {@link PreviewState#TPOSE};</li>
 *   <li>{@code startMs} — момент старта ПО ЛОКАЛЬНЫМ часам наблюдателя: событие
 *       приходит через ~пол-RTT, часы ставятся в момент получения, поэтому анимация
 *       начинается с первого ключевого кадра без синхронизации часов между игроками
 *       (для зацикленных эмоутов фаза и вовсе не важна);</li>
 *   <li>{@code frozenSeconds} — время, замороженное в момент стопа (кадр держится
 *       во время кроссфейда — как у локального {@link DanceState#stop(String)});</li>
 *   <li>{@code applyTs} — отметка отправителя (epoch ms) для ПОРЯДКА событий: все
 *       события одного игрока метятся его часами, поэтому устаревший снимок с другого
 *       зеркала не может перебить более свежее событие;</li>
 *   <li>{@code touchMs} — локальное время последнего обновления: страховка от
 *       «зависших» эмоутов (если обновления прекратились — игрок вылетел, а TTL
 *       сервиса не дошёл), эмоут гаснет сам.</li>
 * </ul>
 * Потокобезопасность: карта — {@link ConcurrentHashMap}; запись пишется только с
 * главного потока ({@code mc.execute} из слушателей сети), читается — на потоке рендера.
 */
@Environment(EnvType.CLIENT)
public final class RemoteEmotes {
    /** Страховка: эмоут без обновлений дольше этого гаснет сам (TTL сервиса — 30 с, refresh — 10 с). */
    static final long STALE_MS = 35_000L;
    /** Сколько секунд держать кадр после стопа (кроссфейд ~0.2 с — с запасом). */
    private static final long DEAD_KEEP_MS = 5_000L;

    /** Одна эмоут-запись игрока (неизменяемая; карта хранит последний вариант). */
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

        /** Секунды эмоута: идут, пока активен; заморожены после стопа (для фейда). */
        public double seconds(long nowMs) {
            return active ? (nowMs - startMs) / 1000.0 : frozenSeconds;
        }
    }

    private static final Map<UUID, Emote> MAP = new ConcurrentHashMap<>();

    private RemoteEmotes() {}

    public static Emote get(UUID id) {
        return MAP.get(id);
    }

    /** Активен ли эмоут игрока прямо сейчас (с учётом страховки от зависания). */
    public static boolean active(UUID id) {
        Emote e = MAP.get(id);
        return e != null && e.active && System.currentTimeMillis() - e.touchMs <= STALE_MS;
    }

    /**
     * Применяет событие/снимок от сервиса. Порядок событий защищён отметкой отправителя:
     * старт принимается только СТРОГО новее, стоп — не старее (TTL-стоп сервиса приходит
     * на 1 мс позже последнего refresh по тем же часам отправителя — см. service.py).
     */
    public static void apply(UUID id, String anim, boolean active, double ts) {
        if (id == null || anim == null || anim.isEmpty()) {
            MAP.remove(id);
            return;
        }
        long now = System.currentTimeMillis();
        Emote cur = MAP.get(id);
        if (cur != null && ts > 0 && ts < cur.applyTs) {
            return; // устаревшее событие (например, отстающее зеркало) — игнорируем
        }
        if (active) {
            if (cur != null && cur.active && cur.anim.equals(anim)) {
                // refresh «я ещё играю»: часы НЕ перезапускаем
                MAP.put(id, new Emote(anim, cur.startMs, true, 0.0F,
                        Math.max(cur.applyTs, ts), now));
            } else {
                MAP.put(id, new Emote(anim, now, true, 0.0F, ts, now));
            }
        } else {
            if (cur == null) return;               // стоп без известного старта — нечего гасить
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

    /** Снимок с зеркала: id, которых в нём нет, чистим, только если они и так погашены
     *  (активные оставляем — зеркало могло отстать; стоп придёт отдельным событием/TTL). */
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

    /** Гигиена: подчищаем записи, которые давно не обновлялись (вылет игрока, смена зеркал). */
    static void prune(long nowMs) {
        MAP.values().removeIf(e -> nowMs - e.touchMs > Math.max(STALE_MS, DEAD_KEEP_MS));
    }

    public static void clear() {
        MAP.clear();
    }
}
