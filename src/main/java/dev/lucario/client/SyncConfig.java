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
 * Client-side mod settings: {@code config/lucariomorph.json}.
 *
 * <p>Everything needed for synchronization lives here (since 1.8.0 the only channel
 * for making morphs visible between players): the sync toggle, the service mirror list,
 * and the remembered player morph. The file is created on first launch and is edited
 * both by hand and by commands.</p>
 */
@Environment(EnvType.CLIENT)
public final class SyncConfig {
    /** Default sync service (the mod author's mirror). */
    public static final String DEFAULT_SERVICE = "http://193.164.155.75:8080";

    /** Sync morphs and emotes through an external service (without it the mod is visible only to you). */
    public boolean syncEnabled = true;
    /** Remember the chosen morph between sessions and apply it on world join. */
    public boolean rememberVariant = true;
    /**
     * Automatic record authorization via Mojang (triggered by a 401 from a mirror, retries on a schedule).
     * {@code false} - the Mojang session token is not used at all, records stay open.
     * Boolean wrapper (not a primitive): configs from 1.3.x lack this field, and Gson via
     * Unsafe fills missing primitives with {@code false} - the default value would be lost.
     */
    public Boolean autoAuth = Boolean.TRUE;
    /** How often (in seconds) to check player morphs on the server. Minimum 2. */
    public int syncIntervalSeconds = 5;
    /**
     * 1.8.1-pre9: the client talks to the mirrors only while at least this many players are
     * on the server (the TAB list, all dimensions, self included) - alone nobody can see the
     * morph anyway. 2 - default: alone in the world (or in singleplayer) the client is fully
     * silent (no PUT, no polls, no /health, no WS); a viewer joining wakes it up immediately.
     * 1 - the old "always sync" behavior. Integer wrapper (not a primitive): configs from
     * before 1.8.1-pre9 lack this field - the same "no field - default" contract as
     * autoAuth/heartbeatMinutes (Gson via Unsafe fills missing primitives with zero).
     */
    public Integer minPlayers = Integer.valueOf(2);
    /**
     * Record "heartbeat": every this many minutes the client quietly re-publishes the current
     * morph to the mirrors, so the record does not go stale on services with a short TTL
     * (records without updates are considered abandoned and deleted - e.g. the mod was removed
     * from the client). 0 - off. The default of 15 minutes covers mirrors with an hourly TTL or longer.
     * Integer wrapper (not a primitive): configs from 1.4.1 lack this field, and Gson via
     * Unsafe fills missing primitives with zero - the default value would be lost.
     */
    public Integer heartbeatMinutes = Integer.valueOf(15);
    /**
     * 1.7.8: duplicate the network trace into chat (tags [LM] / [LM-HTTP]) - what was
     * always on in 1.7.7-debug. Default false: the chat is fully quiet, the whole trail
     * and the status line on world join are written only to the log (latest.log);
     * true - restore the full look of the debug builds.
     * Boolean wrapper (not a primitive): configs from 1.7.6 lack this field, and Gson via
     * Unsafe silently fills missing primitives with the default - the wrapper keeps an
     * explicit "no field - default" contract, like autoAuth/heartbeatMinutes.
     */
    public Boolean debugChat = Boolean.FALSE;
    /** Sync service mirrors. Publishing goes to all of them, reading - from the first to respond. */
    public List<String> serviceUrls = new ArrayList<>(List.of(DEFAULT_SERVICE));
    /** The player's morph (without it /lucario would have to be typed on every join). */
    public String lastVariant = "";
    /**
     * Write tokens keyed by mirror URL: URL → token (the service issues them automatically
     * after verification via Mojang). A protected mirror accepts PUT/DELETE only
     * with this header. If the config file is deleted, the tokens come back automatically:
     * re-authorization via Mojang returns the same ones.
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

    /** Sync is active when enabled in the settings (turned off there too - syncEnabled).
     *  1.7.4: the separate opt-in (syncOptIn) was removed - it silently suppressed syncing
     *  on fresh configs, and the consent screen on world join almost always lost the race
     *  with the loading screen and was never shown. */
    public boolean syncActive() {
        return syncEnabled;
    }

    /** 1.7.8: duplicate the network trace into chat (see {@link #debugChat}). */
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
                    if (loaded.minPlayers != null) cfg.minPlayers = loaded.minPlayers;
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
        // Value normalization
        cfg.serviceUrls.removeIf(u -> u == null || u.isBlank());
        for (int i = 0; i < cfg.serviceUrls.size(); i++) {
            cfg.serviceUrls.set(i, cfg.serviceUrls.get(i).trim().replaceAll("/+$", ""));
        }
        cfg.serviceUrls.removeIf(String::isEmpty);
        if (cfg.serviceUrls.isEmpty()) cfg.serviceUrls = new ArrayList<>(List.of(DEFAULT_SERVICE));
        if (cfg.syncIntervalSeconds < 2) cfg.syncIntervalSeconds = 2;
        if (cfg.heartbeatMinutes == null) cfg.heartbeatMinutes = 15;
        if (cfg.heartbeatMinutes < 0) cfg.heartbeatMinutes = 0;   // negative = disabled
        if (cfg.minPlayers == null) cfg.minPlayers = 2;
        if (cfg.minPlayers < 1) cfg.minPlayers = 1;               // 1 = the old always-sync behavior
        if (cfg.minPlayers > 100) cfg.minPlayers = 100;
        if (cfg.autoAuth == null) cfg.autoAuth = Boolean.TRUE;
        if (cfg.debugChat == null) cfg.debugChat = Boolean.FALSE;
        if (!cfg.lastVariant.isEmpty() && !Variants.isValid(cfg.lastVariant)) cfg.lastVariant = "";
        // Tokens: keys are normalized the same way as serviceUrls (no trailing "/")
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

    /** Saves the config atomically (write to a temp file first, then rename). */
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
