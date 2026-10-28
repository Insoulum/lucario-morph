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
        // Читаем/создаём config/lucariomorph.json
        SyncConfig.get();
        // Клиентские /lucario и /riolu — образ применяется локально и уходит
        // в сервис синхронизации, так что его видят другие игроки с модом
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

        // С 1.8.0 серверного канала нет: мод не работает на выделенных серверах,
        // чужие образы приходят только через сервис синхронизации (SyncClient —
        // WebSocket-push + запасной HTTP-опрос).
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
