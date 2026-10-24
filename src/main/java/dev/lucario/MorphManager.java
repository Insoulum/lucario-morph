package dev.lucario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.lucario.net.MorphPayload;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class MorphManager {
   private static final Map<UUID, String> MORPHS = new ConcurrentHashMap<>();
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   // Patch 7: morph changes no longer trigger synchronous file I/O on the server thread.
   // They are debounced: flushed at most once per SAVE_DEBOUNCE_MS and on server stop.
   private static final long SAVE_DEBOUNCE_MS = 5000L;
   private static volatile boolean dirty = false;
   private static long nextSaveMs = 0L;
   private static Path file;

   private MorphManager() {
   }

   public static void load(MinecraftServer server) {
      MORPHS.clear();
      file = server.getWorldPath(LevelResource.ROOT).resolve("lucariomod_morphs.json");
      if (Files.exists(file)) {
         try {
            try (Reader r = Files.newBufferedReader(file)) {
               Map<String, String> m = (Map<String, String>)GSON.fromJson(r, (new TypeToken<Map<String, String>>() {}).getType());
               if (m != null) {
                  m.forEach((k, v) -> {
                     try {
                        if (Variants.isValid(v)) {
                           MORPHS.put(UUID.fromString(k), Variants.normalize(v));
                        }
                     } catch (IllegalArgumentException var3) {
                     }
                  });
                  return;
               }
            }
         } catch (JsonParseException | IOException var6) {
            LucarioMod.LOGGER.warn("Failed to read {}", file, var6);
         }
      }
   }

   public static void unload(MinecraftServer server) {
      // Patch 7: flush pending changes before dropping the world data
      if (dirty && file != null) {
         dirty = false;
         nextSaveMs = 0L;
         save();
      }

      MORPHS.clear();
      file = null;
   }

   /** Patch 7: called every server tick; performs the debounced flush. */
   public static void tick(MinecraftServer server) {
      if (dirty && nextSaveMs > 0L && System.currentTimeMillis() >= nextSaveMs) {
         dirty = false;
         nextSaveMs = 0L;
         save();
      }
   }

   private static void save() {
      if (file != null) {
         Map<String, String> out = new HashMap<>();
         MORPHS.forEach((k, v) -> out.put(k.toString(), v));

         try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(out, w);
         } catch (IOException var6) {
            LucarioMod.LOGGER.warn("Failed to save {}", file, var6);
         }
      }
   }

   public static String get(UUID id) {
      return MORPHS.get(id);
   }

   public static void set(MinecraftServer server, UUID id, String variant) {
      if (variant == null) {
         MORPHS.remove(id);
      } else {
         MORPHS.put(id, variant);
      }

      dirty = true;
      nextSaveMs = System.currentTimeMillis() + SAVE_DEBOUNCE_MS;
      broadcast(server, id, variant);
   }

   public static void broadcast(MinecraftServer server, UUID id, String variant) {
      MorphPayload payload = new MorphPayload(id.toString(), variant == null ? "" : variant);

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (ServerPlayNetworking.canSend(p, MorphPayload.ID)) {
            ServerPlayNetworking.send(p, payload);
         }
      }
   }

   public static void sendAllTo(ServerPlayer player) {
      if (ServerPlayNetworking.canSend(player, MorphPayload.ID)) {
         MORPHS.forEach((id, variant) -> ServerPlayNetworking.send(player, new MorphPayload(id.toString(), variant)));
      }
   }
}
