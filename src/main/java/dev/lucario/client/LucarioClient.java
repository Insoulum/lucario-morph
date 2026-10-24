package dev.lucario.client;

import dev.lucario.Variants;
import dev.lucario.net.MorphPayload;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

@Environment(EnvType.CLIENT)
public class LucarioClient implements ClientModInitializer {
   // Patch 17: the consent screen is offered at most once per game session
   private static boolean consentShownThisSession = false;

   @Override
   public void onInitializeClient() {
      SyncConfig.get();
      LucarioClientCommands.register();
      KeyBindings.register();
      // Patch 6: re-bake all models on resource reload (F3+T). clear() existed but was dead code.
      // Fabric API 0.16x: ResourceManagerHelper.get(PackType) and the IdentifiableResourceReloadListener
      // registration (the plain-lambda overload is gone).
      ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
         new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
               return Identifier.fromNamespaceAndPath("lucariomod", "models");
            }

            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
               LucarioModels.clear();
            }
         }
      );
      ClientPlayNetworking.registerGlobalReceiver(MorphPayload.ID, (payload, context) -> {
         try {
            UUID id = UUID.fromString(payload.uuid());
            // Patch 3: never trust variant strings received over the network — the sync path
            // validates, this channel previously did not, letting a hostile server spam
            // "failed to load model" errors with garbage keys.
            String v = payload.variant() == null ? "" : payload.variant();
            if (!v.isEmpty() && !Variants.isValid(v)) {
               return;
            }

            ClientMorphs.set(id, v);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getUUID().equals(id)) {
               SyncConfig cfg = SyncConfig.get();
               if (!v.equals(cfg.lastVariant)) {
                  cfg.lastVariant = v;
                  cfg.save();
                  SyncClient.publish(v);
               }
            }
         } catch (IllegalArgumentException var6) {
         }
      });
      ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
         SyncClient.onJoin();
         maybeShowConsent(client);
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         ClientMorphs.clear();
         CombatState.clear();
         // 1.6.0: drop per-player animation blend weights too
         MotionBlend.clear();
         // 1.6.2: drop per-player last-attack marks too
         AttackSwingState.clear();
         // Patch 8: don't carry pending auth retries across servers/sessions
         AuthClient.reset();
      });
      ClientTickEvents.END_CLIENT_TICK.register(mc -> {
         KeyBindings.tick(mc);
         DanceState.tick(mc);
         SyncClient.tick(mc);
      });
   }

   /**
    * Patch 17: one-time sync opt-in. Shown only for online (remote) servers, only when the
    * user has not made a choice yet. Singleplayer/LAN never talks to the service anyway.
    */
   private static void maybeShowConsent(Minecraft mc) {
      SyncConfig cfg = SyncConfig.get();
      if (cfg.syncOptIn != null || consentShownThisSession || mc.hasSingleplayerServer()) {
         return;
      }

      consentShownThisSession = true;
      mc.execute(() -> {
         if (mc.gui.screen() == null) {
            mc.setScreenAndShow(new LucarioConsentScreen());
         }
      });
   }
}
