package dev.lucario.client;

import dev.lucario.net.MorphPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.UUID;

@Environment(EnvType.CLIENT)
public class LucarioClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Читаем/создаём config/lucariomorph.json
        SyncConfig.get();
        // Клиентские /lucario и /riolu — работают на серверах без мода
        LucarioClientCommands.register();

        // Классический канал: сервер с модом сообщает, кто во что превратился
        ClientPlayNetworking.registerGlobalReceiver(MorphPayload.ID, (payload, context) -> {
            try {
                UUID id = UUID.fromString(payload.uuid());
                ClientMorphs.set(id, payload.variant());
                // Если это про нас самих — запоминаем образ (он «следует» за игроком
                // на другие серверы) и обновляем сервис синхронизации.
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.player.getUUID().equals(id)) {
                    String v = payload.variant() == null ? "" : payload.variant();
                    SyncConfig cfg = SyncConfig.get();
                    if (!v.equals(cfg.lastVariant)) {
                        cfg.lastVariant = v;
                        cfg.save();
                        SyncClient.publish(v);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SyncClient.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientMorphs.clear());
        ClientTickEvents.END_CLIENT_TICK.register(SyncClient::tick);
    }
}
