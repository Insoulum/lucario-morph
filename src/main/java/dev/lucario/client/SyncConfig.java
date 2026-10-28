package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.lucario.LucarioMod;
import dev.lucario.Variants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиентские настройки мода: {@code config/lucariomorph.json}.
 *
 * <p>Здесь живёт всё, что нужно для синхронизации (с 1.8.0 — единственного канала
 * видимости образов между игроками): включение синхронизации, список зеркал сервиса
 * и запомненный образ игрока. Файл создаётся при первом запуске, правится и руками,
 * и командами.</p>
 */
@Environment(EnvType.CLIENT)
public final class SyncConfig {
    /** Сервис синхронизации по умолчанию (зеркало автора мода). */
    public static final String DEFAULT_SERVICE = "http://193.164.155.75:8080";

    /** Синхронизировать образы и эмоуты через внешний сервис (без него мод виден только вам). */
    public boolean syncEnabled = true;
    /** Запоминать выбранный образ между сессиями и применять при входе в мир. */
    public boolean rememberVariant = true;
    /**
     * Авто-авторизация записи через Mojang (запускается 401-м от зеркала, ретраи по расписанию).
     * {@code false} — session-токен Mojang не используется вовсе, записи остаются открытыми.
     * Обёртка Boolean (а не примитив): у конфигов от 1.3.x этого поля нет, а Gson через
     * Unsafe заполняет отсутствующие примитивы {@code false} — потеряли бы значение по умолчанию.
     */
    public Boolean autoAuth = Boolean.TRUE;
    /** Как часто (в секундах) проверять образы игроков на сервере. Минимум 2. */
    public int syncIntervalSeconds = 5;
    /**
     * «Пульс» записи: раз в столько минут клиент тихо перепубликует текущий образ на
     * зеркала, чтобы запись не протухла на сервисах с коротким TTL (записи без
     * обновлений считаются брошенными и удаляются — например, мод сняли с клиента).
     * 0 — выключить. По умолчанию 15 минут — уверенно покрывает зеркала с TTL от часа.
     * Обёртка Integer (а не примитив): у конфигов от 1.4.1 этого поля нет, а Gson через
     * Unsafe заполняет отсутствующие примитивы нулём — потеряли бы значение по умолчанию.
     */
    public Integer heartbeatMinutes = Integer.valueOf(15);
    /**
     * 1.7.8: дублировать сетевую трассировку в чат (теги [LM] / [LM-HTTP]) — то, что
     * в 1.7.7-debug было включено всегда. По умолчанию false: чат полностью тихий,
     * весь след и строка статуса при входе в мир пишутся только в лог (latest.log);
     * true — вернуть вид debug-сборок целиком.
     * Обёртка Boolean (а не примитив): у конфигов от 1.7.6 этого поля нет, а Gson через
     * Unsafe заполняет отсутствующие примитивы значением по умолчанию молча — обёртка
     * держит явный контракт «нет поля — умолчание», как autoAuth/heartbeatMinutes.
     */
    public Boolean debugChat = Boolean.FALSE;
    /** Зеркала сервиса синхронизации. Публикация идёт во все, чтение — с первого ответившего. */
    public List<String> serviceUrls = new ArrayList<>(List.of(DEFAULT_SERVICE));
    /** Образ игрока (без него пришлось бы вводить /lucario при каждом входе). */
    public String lastVariant = "";
    /**
     * Токены записи по адресам зеркал: адрес → токен (сервис выдаёт автоматически
     * после проверки через Mojang). Защищённое зеркало принимает PUT/DELETE только
     * с этим заголовком. Если файл конфига удалить — токены вернутся автоматически:
     * повторная авторизация через Mojang отдаст те же.
     */
    public Map<String, String> serviceTokens = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile SyncConfig instance;

    private final transient Path file;

    private SyncConfig(Path file) {
        this.file = file;
    }

    public static SyncConfig get() {
        SyncConfig cfg = instance;
        if (cfg != null) return cfg;
        synchronized (SyncConfig.class) {
            if (instance != null) return instance;
            instance = load(FabricLoader.getInstance().getConfigDir().resolve("lucariomorph.json"));
            return instance;
        }
    }

    /** Синхронизация активна, когда включена в настройках (выключается там же — syncEnabled).
     *  1.7.4: отдельное согласие (syncOptIn) убрано — оно молча душило синк на свежих
     *  конфигах, а экран согласия при входе в мир почти всегда проигрывал гонку
     *  с загрузочным экраном и никогда не показывался. */
    public boolean syncActive() {
        return syncEnabled;
    }

    /** 1.7.8: дублировать сетевую трассировку в чат (см. {@link #debugChat}). */
    public boolean debugChatOn() {
        return Boolean.TRUE.equals(debugChat);
    }

    private static SyncConfig load(Path file) {
        SyncConfig cfg = new SyncConfig(file);
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                SyncConfig loaded = new Gson().fromJson(r, SyncConfig.class);
                if (loaded != null) {
                    cfg.syncEnabled = loaded.syncEnabled;
                    cfg.rememberVariant = loaded.rememberVariant;
                    if (loaded.autoAuth != null) cfg.autoAuth = loaded.autoAuth;
                    cfg.syncIntervalSeconds = loaded.syncIntervalSeconds;
                    if (loaded.heartbeatMinutes != null) cfg.heartbeatMinutes = loaded.heartbeatMinutes;
                    if (loaded.debugChat != null) cfg.debugChat = loaded.debugChat;
                    cfg.lastVariant = loaded.lastVariant == null ? "" : loaded.lastVariant;
                    if (loaded.serviceUrls != null && !loaded.serviceUrls.isEmpty()) {
                        cfg.serviceUrls = new ArrayList<>(loaded.serviceUrls);
                    }
                    if (loaded.serviceTokens != null) {
                        cfg.serviceTokens.putAll(loaded.serviceTokens);
                    }
                }
            } catch (Exception e) {
                LucarioMod.LOGGER.warn("[Lucario Sync] Не удалось прочитать {}, использую значения по умолчанию", file, e);
            }
        }
        // Нормализация значений
        cfg.serviceUrls.removeIf(u -> u == null || u.isBlank());
        for (int i = 0; i < cfg.serviceUrls.size(); i++) {
            cfg.serviceUrls.set(i, cfg.serviceUrls.get(i).trim().replaceAll("/+$", ""));
        }
        cfg.serviceUrls.removeIf(String::isEmpty);
        if (cfg.serviceUrls.isEmpty()) cfg.serviceUrls = new ArrayList<>(List.of(DEFAULT_SERVICE));
        if (cfg.syncIntervalSeconds < 2) cfg.syncIntervalSeconds = 2;
        if (cfg.heartbeatMinutes == null) cfg.heartbeatMinutes = 15;
        if (cfg.heartbeatMinutes < 0) cfg.heartbeatMinutes = 0;   // отрицательное = выключено
        if (cfg.autoAuth == null) cfg.autoAuth = Boolean.TRUE;
        if (cfg.debugChat == null) cfg.debugChat = Boolean.FALSE;
        if (!cfg.lastVariant.isEmpty() && !Variants.isValid(cfg.lastVariant)) cfg.lastVariant = "";
        // Токены: ключи приводим к тому же виду, что и serviceUrls (без хвостового «/»)
        Map<String, String> tokens = new LinkedHashMap<>();
        cfg.serviceTokens.forEach((k, v) -> {
            if (k == null || v == null) return;
            String kk = k.trim().replaceAll("/+$", "");
            String vv = v.trim();
            if (!kk.isEmpty() && !vv.isEmpty()) tokens.put(kk, vv);
        });
        cfg.serviceTokens = tokens;
        return cfg;
    }

    /** Атомарно сохраняет конфиг (сначала во временный файл, потом переименование). */
    public void save() {
        Path target = file;
        if (target == null) return;
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(this, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LucarioMod.LOGGER.warn("[Lucario Sync] Не удалось сохранить {}", target, e);
        }
    }
}
