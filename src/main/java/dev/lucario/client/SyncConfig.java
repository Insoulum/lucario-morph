package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.lucario.LucarioMod;
import dev.lucario.Variants;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public final class SyncConfig {
   // TODO: replace with an HTTPS domain before public release (see review patch 1)
   public static final String DEFAULT_SERVICE = "http://193.164.155.75:8080";
   public boolean syncEnabled = true;
   public boolean rememberVariant = true;
   public Boolean autoAuth = Boolean.TRUE;
   public int syncIntervalSeconds = 5;
   public Integer heartbeatMinutes = 15;
   public List<String> serviceUrls = new ArrayList<>(List.of(DEFAULT_SERVICE));
   public String lastVariant = "";
   public Map<String, String> serviceTokens = new LinkedHashMap<>();
   /**
    * Patch 17: user consent for publishing/fetching morph data via the external service.
    * null = the user has not been asked yet (fresh install) → the consent screen is shown once.
    * Legacy configs that predate the consent flow are migrated to TRUE on load, so existing
    * users keep their previous behavior and are not nagged.
    */
   public Boolean syncOptIn = null;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static volatile SyncConfig instance;
   private final transient Path file;

   private SyncConfig(Path file) {
      this.file = file;
   }

   public static SyncConfig get() {
      SyncConfig cfg = instance;
      if (cfg != null) {
         return cfg;
      } else {
         synchronized (SyncConfig.class) {
            if (instance != null) {
               return instance;
            } else {
               instance = load(FabricLoader.getInstance().getConfigDir().resolve("lucariomorph.json"));
               return instance;
            }
         }
      }
   }

   /** Patch 17: sync is only active when enabled AND explicitly opted in. */
   public boolean syncActive() {
      return syncEnabled && Boolean.TRUE.equals(syncOptIn);
   }

   private static SyncConfig load(Path file) {
      SyncConfig cfg = new SyncConfig(file);
      if (Files.exists(file)) {
         try (Reader r = Files.newBufferedReader(file)) {
            SyncConfig loaded = (SyncConfig)new Gson().fromJson(r, SyncConfig.class);
            if (loaded != null) {
               cfg.syncEnabled = loaded.syncEnabled;
               cfg.rememberVariant = loaded.rememberVariant;
               if (loaded.autoAuth != null) {
                  cfg.autoAuth = loaded.autoAuth;
               }

               cfg.syncIntervalSeconds = loaded.syncIntervalSeconds;
               if (loaded.heartbeatMinutes != null) {
                  cfg.heartbeatMinutes = loaded.heartbeatMinutes;
               }

               cfg.lastVariant = loaded.lastVariant == null ? "" : loaded.lastVariant;
               if (loaded.serviceUrls != null && !loaded.serviceUrls.isEmpty()) {
                  cfg.serviceUrls = new ArrayList<>(loaded.serviceUrls);
               }

               if (loaded.serviceTokens != null) {
                  cfg.serviceTokens.putAll(loaded.serviceTokens);
               }

               // Patch 17: legacy migration — the config already existed before the consent
               // flow, so the user has been using sync already; keep their behavior.
               if (loaded.syncOptIn != null) {
                  cfg.syncOptIn = loaded.syncOptIn;
               } else {
                  cfg.syncOptIn = Boolean.TRUE;
               }
            }
         } catch (Exception var7) {
            LucarioMod.LOGGER.warn("[Lucario Sync] Failed to read {}, using defaults", file, var7);
         }
      }

      cfg.serviceUrls.removeIf(u -> u == null || u.isBlank());

      for (int i = 0; i < cfg.serviceUrls.size(); i++) {
         cfg.serviceUrls.set(i, cfg.serviceUrls.get(i).trim().replaceAll("/+$", ""));
      }

      cfg.serviceUrls.removeIf(String::isEmpty);
      if (cfg.serviceUrls.isEmpty()) {
         cfg.serviceUrls = new ArrayList<>(List.of(DEFAULT_SERVICE));
      }

      if (cfg.syncIntervalSeconds < 2) {
         cfg.syncIntervalSeconds = 2;
      }

      if (cfg.heartbeatMinutes == null) {
         cfg.heartbeatMinutes = 15;
      }

      if (cfg.heartbeatMinutes < 0) {
         cfg.heartbeatMinutes = 0;
      }

      if (cfg.autoAuth == null) {
         cfg.autoAuth = Boolean.TRUE;
      }

      if (!cfg.lastVariant.isEmpty() && !Variants.isValid(cfg.lastVariant)) {
         cfg.lastVariant = "";
      }

      Map<String, String> tokens = new LinkedHashMap<>();
      cfg.serviceTokens.forEach((k, v) -> {
         if (k != null && v != null) {
            String kk = k.trim().replaceAll("/+$", "");
            String vv = v.trim();
            if (!kk.isEmpty() && !vv.isEmpty()) {
               tokens.put(kk, vv);
            }
         }
      });
      cfg.serviceTokens = tokens;
      return cfg;
   }

   public void save() {
      Path target = this.file;
      if (target != null) {
         try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

            try (Writer w = Files.newBufferedWriter(tmp)) {
               GSON.toJson(this, w);
            }

            try {
               Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException var7) {
               Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (Exception var9) {
            LucarioMod.LOGGER.warn("[Lucario Sync] Failed to save {}", target, var9);
         }
      }
   }
}
