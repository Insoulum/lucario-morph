package dev.lucario.client;

import dev.lucario.Variants;
import dev.lucario.net.MorphPayload;
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

import java.util.UUID;

@Environment(EnvType.CLIENT)
public class LucarioClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Читаем/создаём config/lucariomorph.json
        SyncConfig.get();
        // Клиентские /lucario и /riolu — работают на серверах без мода
        LucarioClientCommands.register();
        // Клавиши: L — выбор образа, J — танец
        KeyBindings.register();

        // Перепекаем модели при перезагрузке ресурсов (F3+T)
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

        // Классический канал: сервер с модом сообщает, кто во что превратился
        ClientPlayNetworking.registerGlobalReceiver(MorphPayload.ID, (payload, context) -> {
            try {
                UUID id = UUID.fromString(payload.uuid());
                // Не доверяем вариантам из сети: враждебный сервер может заспамить
                // клиент мусорными ключами и ошибками загрузки моделей.
                String v = payload.variant() == null ? "" : payload.variant();
                if (!v.isEmpty() && !Variants.isValid(v)) {
                    return;
                }

                ClientMorphs.set(id, v);
                // Если это про нас самих — запоминаем образ (он «следует» за игроком
                // на другие серверы) и обновляем сервис синхронизации.
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.player.getUUID().equals(id)) {
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
            // 1.7.0: WS-канал эмоутов закрываем, отложенные публикации сбрасываем.
            // Сам стоп НЕ шлём: эмоут визуально продолжается после смены мира (так
            // устроен мод), а если игрок не вернётся — запись погасит TTL сервиса.
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
