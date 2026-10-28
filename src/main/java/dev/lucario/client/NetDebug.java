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
 * 1.7.7-debug2: трассировка сетевого канала синхронизации. ВСЁ идёт и в лог, и в чат:
 * события WebSocket — тег [NET-WS] / префикс чата «[LM]», HTTP-обмены с зеркалами —
 * тег [NET-HTTP] / префикс чата «[LM-HTTP]».
 *
 * <p>1.7.8: в релизе чат полностью тихий — дублирование в чат ([LM] / [LM-HTTP]) работает
 * только при {@code debugChat: true} в конфиге (SyncConfig#debugChatOn); в лог (latest.log)
 * пишется всегда, включая строку статуса при входе в мир.</p>
 *
 * <p>Почему в 1.7.7-debug HTTP был «только в лог»: при частом поллинге (старый режим
 * 1.7.6, опрос каждые 5 с) чат замусоривался. После push-first (1.7.7) HTTP стало мало —
 * страховочный ресинк раз в 60–120 с плюс публикация образа/эмоута, поэтому в debug2
 * каждое HTTP-событие тоже дублируется в чат. Если WS упал и чат затопило строками
 * опроса каждые ~5 с — это и есть диагноз: клиент живёт на запасном HTTP-пути.</p>
 *
 * <p>Зачем: в /health сервиса поле conns считает ВСЕ открытые TCP-соединения (HTTP с
 * keep-alive + WebSocket + чужие проверки/мониторы), а не только WS-клиенты. Мод видит
 * только собственные сокеты — сопоставив «что открыл я» (этот лог) с «что видит сервер»
 * (строка /health в этом же чате и лог сервера с CONN_DEBUG=1), можно найти источник
 * лишних соединений.</p>
 *
 * <p>Безопасно вызывать из любого потока: chat() всегда планирует вывод через
 * Minecraft.execute (как AuthClient.message).</p>
 */
@Environment(EnvType.CLIENT)
final class NetDebug {

    /** 1.7.7-debug3: строки, пришедшие до спавна игрока (mc.player == null): раньше chat()
     *  их молча выбрасывал, и «WS: подключаюсь/ОТКРЫТ» после перезахода в мир НЕ появлялись
     *  в чате (подключение стартует до спавна), хотя WS был жив (в логе это выглядело как
     *  «чат молчит, а live=1»). Копим и выливаем при первом появлении игрока. */
    private static final Queue<String> PENDING_CHAT = new ConcurrentLinkedQueue<>();
    /** Потолок буфера до спавна: чат и так не резиновый. */
    private static final int PENDING_LIMIT = 50;

    private NetDebug() {}

    /** Событие канала WebSocket: в лог всегда, в чат — по debugChat (1.7.8). */
    static void ws(String msg) {
        LucarioMod.LOGGER.info("[NET-WS] {}", msg);
        if (SyncConfig.get().debugChatOn()) chat("[LM] " + msg);
    }

    /** Событие HTTP (запросы к зеркалам): в лог всегда, в чат — по debugChat (1.7.8). */
    static void http(String msg) {
        LucarioMod.LOGGER.info("[NET-HTTP] {}", msg);
        if (SyncConfig.get().debugChatOn()) chat("[LM-HTTP] " + msg);
    }

    /** Короткая форма UUID для строк лога: первые 8 символов + «…». */
    static String shortId(Object id) {
        String s = String.valueOf(id);
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
    }

    /** Обрезает длинный URL (запрос с сотней UUID целиком в лог не рисуем). */
    static String clip(String url) {
        return url.length() <= 110 ? url : url.substring(0, 110) + "…(" + url.length() + " симв)";
    }

    /** 1.7.7-debug4: версия мода из fabric.mod.json — строка самопрезентации в чате
     *  при каждом входе в мир, чтобы лог всегда говорил, с какого билда он снят
     *  (больше не гадаем «debug2 или debug3?» по косвенным признакам). */
    static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("lucariomod")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
        } catch (Exception e) {
            return "?";   // без загрузчика (тесты/инструменты) просто не роняем вызывавшего
        }
    }

    /** В чат из любого потока (без блокировок: всегда через mc.execute).
     *  До спавна строка кладётся в буфер — выльется после появления игрока. */
    private static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (PENDING_CHAT.size() < PENDING_LIMIT) PENDING_CHAT.add(text);
            return;
        }
        mc.execute(() -> drainAndSend(text));
    }

    /** 1.7.7-debug3: вызывать из клиентского тика — вылить буфер, накопленный до спавна. */
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

    /** Внутри mc.execute: сначала буфер (по порядку), потом сама строка. */
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
