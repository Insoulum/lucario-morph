package dev.lucario.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

@Environment(EnvType.CLIENT)
public class LucarioClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Read/create config/lucariomorph.json
        SyncConfig.get();
        // Client-side /lucario and /riolu - the morph is applied locally and sent
        // to the sync service, so other players with the mod can see it
        LucarioClientCommands.register();
        // Keys: L - morph picker, J - dance
        KeyBindings.register();

        // Re-bake models on resource reload (F3+T)
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

        // Since 1.8.0 there is no server channel: the mod does not run on dedicated servers,
        // other players' morphs arrive only via the sync service (SyncClient -
        // WebSocket push + fallback HTTP polling).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SyncClient.onJoin();
            EmoteSync.onJoin();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientMorphs.clear();
            CombatState.clear();
            MotionBlend.clear();
            AttackSwingState.clear();
            RemoteEmotes.clear();
            AuthClient.reset();
            // 1.7.0: close the emote WS channel and drop pending publications.
            // We do NOT send the stop itself: the emote keeps playing visually after a world
            // switch (by design), and if the player never returns the service TTL expires the entry.
            EmoteSync.onDisconnect();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            KeyBindings.tick(mc);
            DanceState.tick(mc);
            SyncClient.tick(mc);
            EmoteSync.tick(mc);
        });
    }

}
