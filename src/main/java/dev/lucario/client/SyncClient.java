package dev.lucario.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lucario.LucarioMod;
import dev.lucario.Variants;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

@Environment(EnvType.CLIENT)
public final class SyncClient {
   static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3L)).build();
   private static final Gson GSON = new Gson();
   private static final int BATCH = 100;
   private static final int MAX_FETCH_BYTES = 1048576;
   private static final long RETRY_BACKOFF_MS = 30000L;
   private static volatile String preferredMirror = null;
   private static long nextCycleMs = 0L;
   private static volatile long nextHeartbeatMs = 0L;
   private static final AtomicBoolean lastCycleFailed = new AtomicBoolean(false);
   private static volatile long lastPublishFailLogMs = 0L;
   private static volatile boolean pendingApply = false;
   private static volatile String pendingPublish = null;
   private static volatile long nextPublishAttemptMs = 0L;

   private SyncClient() {
   }

   public static void publish(String variant) {
      String key = variant == null ? "" : variant;
      if (key.isEmpty() || Variants.isValid(key)) {
         pendingPublish = key;
         nextPublishAttemptMs = System.currentTimeMillis() + 5000L;
         doPublish(key);
      }
   }

   private static void doPublish(String key) {
      SyncConfig cfg = SyncConfig.get();
      // Patch 17: publishing requires explicit opt-in (syncActive = syncEnabled && syncOptIn)
      if (cfg.syncActive() && !cfg.serviceUrls.isEmpty()) {
         UUID self = selfUuid();
         if (self != null) {
            String body = GSON.toJson(Map.of("variant", key));
            int total = cfg.serviceUrls.size();
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            for (String raw : cfg.serviceUrls) {
               try {
                  Builder rb = HttpRequest.newBuilder(URI.create(raw + "/v1/morphs/" + self))
                     .timeout(Duration.ofSeconds(5L))
                     .header("Content-Type", "application/json");
                  String rec = cfg.serviceTokens.get(raw);
                  if (rec != null && !rec.isEmpty()) {
                     rb.header("X-Lucario-Token", rec);
                  }

                  HttpRequest req = rb.PUT(BodyPublishers.ofString(body)).build();
                  HTTP.sendAsync(req, boundedHandler()).whenComplete((resp, err) -> {
                     if (err != null) {
                        failed.incrementAndGet();
                        logPublishFail(raw, err);
                     } else if (resp.statusCode() == 401) {
                        failed.incrementAndGet();
                        logPublishFail(raw, new IllegalStateException("HTTP 401 — record is token-protected, authenticating automatically"));
                        AuthClient.onUnauthorized();
                     } else if (resp.statusCode() / 100 != 2) {
                        failed.incrementAndGet();
                        logPublishFail(raw, new IllegalStateException("HTTP " + resp.statusCode()));
                     } else {
                        ok.incrementAndGet();
                     }

                     if (ok.get() + failed.get() == total) {
                        if (failed.get() > 0) {
                           nextPublishAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
                        } else if (key.equals(pendingPublish)) {
                           pendingPublish = null;
                        }
                     }
                  });
               } catch (Exception var12) {
                  failed.incrementAndGet();
                  logPublishFail(raw, var12);
               }
            }
         }
      } else {
         pendingPublish = null;
      }
   }

   public static void onAuthSuccess() {
      nextPublishAttemptMs = 0L;
   }

   private static void logPublishFail(String mirror, Throwable t) {
      long now = System.currentTimeMillis();
      if (now - lastPublishFailLogMs >= 60000L) {
         synchronized (SyncClient.class) {
            if (now - lastPublishFailLogMs < 60000L) {
               return;
            }

            lastPublishFailLogMs = now;
         }

         LucarioMod.LOGGER.warn("[Lucario Sync] Mirror {} rejected the morph: {}", mirror, t.toString());
      }
   }

   public static void onJoin() {
      pendingApply = true;
      nextCycleMs = 0L;
      nextHeartbeatMs = 0L;
   }

   public static void tick(Minecraft mc) {
      if (mc.level != null && mc.getConnection() != null) {
         SyncConfig cfg = SyncConfig.get();
         if (pendingApply && mc.player != null) {
            pendingApply = false;
            String v = cfg.rememberVariant ? cfg.lastVariant : "";
            if (!v.isEmpty() && Variants.isValid(v)) {
               ClientMorphs.set(mc.player.getUUID(), v);
               publish(v);
            }
         }

         String pending = pendingPublish;
         if (pending != null && System.currentTimeMillis() >= nextPublishAttemptMs) {
            nextPublishAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            doPublish(pending);
         }

         AuthClient.tick();
         // Patch 17: everything below talks to the external service — requires opt-in
         if (cfg.syncActive() && !cfg.serviceUrls.isEmpty()) {
            long now = System.currentTimeMillis();
            int hb = cfg.heartbeatMinutes == null ? 0 : cfg.heartbeatMinutes;
            if (hb > 0) {
               if (nextHeartbeatMs == 0L) {
                  nextHeartbeatMs = now + hb * 60000L;
               } else if (now >= nextHeartbeatMs) {
                  nextHeartbeatMs = now + hb * 60000L;
                  UUID self = selfUuid();
                  String current = self == null ? null : ClientMorphs.get(self);
                  if (current != null && !current.isEmpty() && pendingPublish == null) {
                     doPublish(current);
                  }
               }
            }

            if (now >= nextCycleMs) {
               // Patch 9: ±15% jitter so many clients don't hit the mirrors in sync
               long base = cfg.syncIntervalSeconds * 1000L;
               long jitter = (long)((ThreadLocalRandom.current().nextDouble() - 0.5) * base * 0.3);
               nextCycleMs = now + base + jitter;
               UUID self = selfUuid();
               List<UUID> ids = new ArrayList<>();

               for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                  UUID id = info.getProfile().id();
                  if (self == null || !self.equals(id)) {
                     ids.add(id);
                  }
               }

               if (!ids.isEmpty()) {
                  for (int i = 0; i < ids.size(); i += BATCH) {
                     fetchAndApply(mc, List.copyOf(ids.subList(i, Math.min(ids.size(), i + BATCH))), self);
                  }
               }
            }
         }
      }
   }

   private static void fetchAndApply(Minecraft mc, List<UUID> batch, UUID self) {
      List<String> mirrors = orderedMirrors(SyncConfig.get());
      if (!mirrors.isEmpty()) {
         StringBuilder q = new StringBuilder("/v1/morphs?uuids=");

         for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
               q.append(',');
            }

            q.append(batch.get(i));
         }

         fetchJson(mirrors, 0, q.toString()).whenComplete((obj, err) -> {
            if (err != null) {
               if (lastCycleFailed.compareAndSet(false, true)) {
                  LucarioMod.LOGGER.warn("[Lucario Sync] No mirror responded, keeping cache as-is: {}", err.toString());
               }
            } else {
               if (lastCycleFailed.compareAndSet(true, false)) {
                  LucarioMod.LOGGER.info("[Lucario Sync] Mirror connection restored");
               }

               mc.execute(() -> applyBatch(batch, obj, self));
            }
         });
      }
   }

   private static CompletableFuture<JsonObject> fetchJson(List<String> mirrors, int idx, String pathAndQuery) {
      if (idx >= mirrors.size()) {
         return CompletableFuture.failedFuture(new IllegalStateException("all mirrors unavailable"));
      } else {
         String url = mirrors.get(idx) + pathAndQuery;

         HttpRequest req;
         try {
            req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5L)).GET().build();
         } catch (Exception var6) {
            return fetchJson(mirrors, idx + 1, pathAndQuery);
         }

         return HTTP.sendAsync(req, boundedHandler()).thenCompose(resp -> {
            if (resp.statusCode() == 200) {
               try {
                  JsonObject obj = (JsonObject)GSON.fromJson(resp.body(), JsonObject.class);
                  preferredMirror = mirrors.get(idx);
                  return CompletableFuture.completedFuture(obj);
               } catch (Exception var5) {
                  return fetchJson(mirrors, idx + 1, pathAndQuery);
               }
            } else {
               return fetchJson(mirrors, idx + 1, pathAndQuery);
            }
         });
      }
   }

   private static List<String> orderedMirrors(SyncConfig cfg) {
      List<String> out = new ArrayList<>(cfg.serviceUrls);
      String pref = preferredMirror;
      if (pref != null && out.remove(pref)) {
         out.add(0, pref);
      }

      return out;
   }

   private static void applyBatch(List<UUID> batch, JsonObject obj, UUID self) {
      for (UUID id : batch) {
         if (!id.equals(self)) {
            String key = id.toString();
            String v = "";
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
               v = obj.get(key).getAsString();
            }

            if (v.isEmpty()) {
               if (ClientMorphs.get(id) != null) {
                  ClientMorphs.set(id, "");
               }
            } else if (Variants.isValid(v) && !v.equals(ClientMorphs.get(id))) {
               ClientMorphs.set(id, v);
            }
         }
      }
   }

   private static BodySubscriber<String> boundedBody(final long maxBytes) {
      return new BodySubscriber<String>() {
         private final CompletableFuture<String> done = new CompletableFuture<>();
         private final ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
         private long size = 0L;
         private Subscription sub;

         @Override
         public CompletionStage<String> getBody() {
            return this.done;
         }

         @Override
         public void onSubscribe(Subscription s) {
            this.sub = s;
            s.request(Long.MAX_VALUE);
         }

         public void onNext(List<ByteBuffer> items) {
            if (!this.done.isDone()) {
               for (ByteBuffer b : items) {
                  int n = b.remaining();
                  if (this.size + n > maxBytes) {
                     this.sub.cancel();
                     this.done.completeExceptionally(new IllegalStateException("mirror response larger than " + (maxBytes >> 10) + " KiB — connection dropped"));
                     return;
                  }

                  this.size += n;
                  byte[] a = new byte[n];
                  b.get(a);
                  this.out.write(a, 0, n);
               }
            }
         }

         @Override
         public void onError(Throwable t) {
            this.done.completeExceptionally(t);
         }

         @Override
         public void onComplete() {
            this.done.complete(new String(this.out.toByteArray(), StandardCharsets.UTF_8));
         }
      };
   }

   static BodyHandler<String> boundedHandler() {
      return info -> boundedBody(1048576L);
   }

   private static UUID selfUuid() {
      Minecraft mc = Minecraft.getInstance();
      return mc.player == null ? null : mc.player.getUUID();
   }
}
