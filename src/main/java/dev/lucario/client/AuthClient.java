package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lucario.LucarioMod;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public final class AuthClient {
   private static final Gson GSON = new Gson();
   private static final String MOJANG_JOIN = "https://sessionserver.mojang.com/session/minecraft/join";
   private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
   private static final AtomicBoolean NEEDED = new AtomicBoolean(false);
   private static final AtomicInteger ATTEMPTS = new AtomicInteger(0);
   private static volatile long nextAttemptMs = 0L;
   // Patch 2: warn about offline-mode servers only once per game session
   private static boolean offlineWarned = false;

   private AuthClient() {
   }

   public static void onUnauthorized() {
      if (Boolean.TRUE.equals(SyncConfig.get().autoAuth)) {
         if (NEEDED.compareAndSet(false, true)) {
            ATTEMPTS.set(0);
            nextAttemptMs = 0L;
         }

         tryRun();
      }
   }

   /** Patch 8: drop pending auth retries when leaving a server. */
   public static void reset() {
      NEEDED.set(false);
      ATTEMPTS.set(0);
      nextAttemptMs = 0L;
   }

   public static void tick() {
      if (NEEDED.get() && !IN_FLIGHT.get() && System.currentTimeMillis() >= nextAttemptMs) {
         tryRun();
      }
   }

   private static void tryRun() {
      if (IN_FLIGHT.compareAndSet(false, true)) {
         run();
      }
   }

   private static void run() {
      Minecraft mc = Minecraft.getInstance();
      SyncConfig cfg = SyncConfig.get();
      if (!cfg.syncEnabled || cfg.serviceUrls.isEmpty()) {
         NEEDED.set(false);
         IN_FLIGHT.set(false);
         return;
      }

      boolean launched = false;

      try {
         User user = mc.getUser();
         if (mc.player != null && user != null && user.getAccessToken() != null && !user.getAccessToken().isBlank()) {
            // Patch 2: offline-mode guard. On offline servers mc.player.getUUID() is a
            // name-derived UUID that does not match the Mojang profile, so the join call
            // would be rejected forever and we would keep hammering the session server.
            UUID sessionProfile = mc.getGameProfile() != null ? mc.getGameProfile().id() : null;
            if (sessionProfile != null && !sessionProfile.equals(mc.player.getUUID())) {
               NEEDED.set(false);
               if (!offlineWarned) {
                  offlineWarned = true;
                  message(mc, Component.translatable("lucariomod.auth.offline_server"));
               }
               return;
            }

            int n = ATTEMPTS.incrementAndGet();
            if (n == 1) {
               LucarioMod.LOGGER.info("[Lucario Sync] Mirror requires a write token — authenticating via Mojang ({} mirror(s))", cfg.serviceUrls.size());
            }

            String name = user.getName();
            String uuid = mc.player.getUUID().toString();
            String accessToken = user.getAccessToken();
            Map<String, String> got = new ConcurrentHashMap<>();
            Map<String, String> errors = new ConcurrentHashMap<>();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

            for (String mirror : cfg.serviceUrls) {
               chain = chain.thenCompose(unused -> authMirror(mirror, name, uuid, accessToken, got, errors));
            }

            chain.whenComplete((unused, err) -> {
               try {
                  if (got.isEmpty()) {
                     fail(mc, combine(errors, err));
                  } else {
                     succeed(mc, got);
                  }
               } finally {
                  IN_FLIGHT.set(false);
               }
            });
            launched = true;
         } else {
            fail(mc, "no licensed Minecraft session");
         }
      } catch (Exception var16) {
         fail(mc, rootMessage(var16));
      } finally {
         if (!launched) {
            IN_FLIGHT.set(false);
         }
      }
   }

   private static long delayAfterFailureMs(int n) {
      if (n <= 2) {
         return 30000L;
      } else {
         return n <= 4 ? 60000L : 300000L;
      }
   }

   private static void fail(Minecraft mc, String reason) {
      int n = ATTEMPTS.get();
      long delay = delayAfterFailureMs(n);
      nextAttemptMs = System.currentTimeMillis() + delay;
      if (n == 1) {
         message(mc, Component.translatable("lucariomod.auth.failed", reason));
      }

      if (n <= 4 || n % 12 == 0) {
         LucarioMod.LOGGER.warn("[Lucario Sync] Record auth failed (attempt {}, retrying in {} s): {}", n, delay / 1000L, reason);
      }
   }

   private static void succeed(Minecraft mc, Map<String, String> got) {
      NEEDED.set(false);
      ATTEMPTS.set(0);
      nextAttemptMs = 0L;
      mc.execute(
         () -> {
            SyncConfig cfg = SyncConfig.get();
            cfg.serviceTokens.putAll(got);
            cfg.save();
            message(mc, Component.translatable("lucariomod.auth.protected", got.size()));
            SyncClient.onAuthSuccess();
         }
      );
   }

   private static CompletableFuture<Void> authMirror(
      String mirror, String name, String uuid, String accessToken, Map<String, String> got, Map<String, String> errors
   ) {
      String chBody = GSON.toJson(Map.of("uuid", uuid, "name", name));
      return post(mirror + "/v1/auth/challenge", chBody).thenCompose(challenge -> {
         String serverId = challenge.get("serverId").getAsString();
         String joinBody = GSON.toJson(Map.of("accessToken", accessToken, "selectedProfile", uuid.replace("-", ""), "serverId", serverId));
         return mojangJoin(joinBody).thenCompose(joined -> {
            if (!joined) {
               return CompletableFuture.failedFuture(new IllegalStateException("Mojang rejected join (licensed session required)"));
            } else {
               String vBody = GSON.toJson(Map.of("uuid", uuid, "name", name, "serverId", serverId));
               return post(mirror + "/v1/auth/verify", vBody);
            }
         });
      }).thenCompose(verified -> {
         if (verified != null && verified.has("token") && !verified.get("token").isJsonNull()) {
            String token = verified.get("token").getAsString();
            if (token.isBlank()) {
               return CompletableFuture.failedFuture(new IllegalStateException("empty token"));
            } else {
               got.put(mirror, token);
               return CompletableFuture.completedFuture((Void)null);
            }
         } else {
            return CompletableFuture.failedFuture(new IllegalStateException("no token in response"));
         }
      }).handle((res, err) -> {
         if (err != null) {
            errors.put(mirror, rootMessage(err));
         }

         return null;
      });
   }

   private static String combine(Map<String, String> errors, Throwable err) {
      if (!errors.isEmpty()) {
         StringBuilder sb = new StringBuilder();

         for (Entry<String, String> e : errors.entrySet()) {
            if (sb.length() > 0) {
               sb.append("; ");
            }

            sb.append(e.getKey()).append(": ").append(e.getValue());
         }

         return sb.toString();
      } else {
         return err == null ? "no mirror responded" : rootMessage(err);
      }
   }

   private static CompletableFuture<JsonObject> post(String url, String body) {
      HttpRequest req;
      try {
         req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10L))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
      } catch (Exception var4) {
         return CompletableFuture.failedFuture(var4);
      }

      return SyncClient.HTTP.sendAsync(req, SyncClient.boundedHandler()).thenCompose(resp -> {
         if (resp.statusCode() / 100 != 2) {
            return CompletableFuture.failedFuture(new IllegalStateException("HTTP " + resp.statusCode()));
         } else {
            try {
               JsonObject obj = (JsonObject)GSON.fromJson(resp.body(), JsonObject.class);
               return obj == null ? CompletableFuture.failedFuture(new IllegalStateException("empty response")) : CompletableFuture.completedFuture(obj);
            } catch (Exception var2x) {
               return CompletableFuture.failedFuture(var2x);
            }
         }
      });
   }

   private static CompletableFuture<Boolean> mojangJoin(String body) {
      HttpRequest req;
      try {
         req = HttpRequest.newBuilder(URI.create(MOJANG_JOIN))
            .timeout(Duration.ofSeconds(10L))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
      } catch (Exception var3) {
         return CompletableFuture.failedFuture(var3);
      }

      return SyncClient.HTTP.sendAsync(req, SyncClient.boundedHandler()).thenApply(resp -> resp.statusCode() == 204);
   }

   private static void message(Minecraft mc, Component text) {
      mc.execute(() -> {
         if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Lucario] ").append(text));
         }
      });
   }

   private static String rootMessage(Throwable t) {
      Throwable cur = t;

      while (cur.getCause() != null && cur.getCause() != cur) {
         cur = cur.getCause();
      }

      String m = cur.getMessage();
      return m == null ? cur.toString() : m;
   }
}
