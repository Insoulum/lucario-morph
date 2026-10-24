package dev.lucario.client;

import dev.lucario.MorphCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Клиентский запасной вариант команд {@code /lucario} и {@code /riolu}.
 *
 * <p>Работают на серверах <b>без</b> мода: образ применяется локально, запоминается
 * в конфиге и публикуется в сервис синхронизации, так что образ видят другие
 * игроки с модом. Если на сервере есть своя команда с тем же именем — Fabric
 * автоматически отправляет её серверу, и клиентская версия игнорируется
 * (сервер остаётся главным источником, как раньше).</p>
 *
 * <p>Само дерево команд теперь общее с серверной версией ({@link MorphCommands}),
 * здесь остаётся только адаптер над клиентским окружением.</p>
 */
@Environment(EnvType.CLIENT)
public final class LucarioClientCommands {
    private LucarioClientCommands() {}

    /** Адаптер: дерево команд одно и то же, среда выполнения — клиентская. */
    private static final MorphCommands.Bridge<FabricClientCommandSource> CLIENT_BRIDGE = new MorphCommands.Bridge<>() {
        @Override
        public UUID player(FabricClientCommandSource source) {
            return source.getPlayer() != null ? source.getPlayer().getUUID() : null;
        }

        @Override
        public String current(UUID id) {
            return ClientMorphs.get(id);
        }

        @Override
        public void apply(FabricClientCommandSource source, UUID id, String variant) {
            MorphApplier.apply(variant);
        }

        @Override
        public void feedback(FabricClientCommandSource source, Component message) {
            source.sendFeedback(message);
        }

        @Override
        public void failure(FabricClientCommandSource source, Component message) {
            source.sendError(message);
        }
    };

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(MorphCommands.tree("lucario", "lucario", CLIENT_BRIDGE));
            dispatcher.register(MorphCommands.tree("riolu", "riolu", CLIENT_BRIDGE));
        });
    }
}
