package dev.lucario.client;

import dev.lucario.net.MorphPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.UUID;

@Environment(EnvType.CLIENT)
public class LucarioClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MorphPayload.ID, (payload, context) -> {
            try {
                ClientMorphs.set(UUID.fromString(payload.uuid()), payload.variant());
            } catch (IllegalArgumentException ignored) {
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientMorphs.clear());
    }
}
