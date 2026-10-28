package dev.lucario.client;

import dev.lucario.MorphCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Клиентские команды {@code /lucario} и {@code /riolu} — с 1.8.0 единственная версия
 * (серверной у мода больше нет). Образ применяется локально, запоминается в конфиге
 * и публикуется в сервис синхронизации, так что его видят другие игроки с модом.
 * Если на сервере есть чужая команда с тем же именем (датапак/другой мод) — Fabric
 * отправляет её серверу, и клиентская версия игнорируется: так работает приоритет
 * серверных команд в brigadier.
 *
 * <p>Дерево команд общее ({@link MorphCommands}), здесь остаётся только адаптер
 * над клиентским окружением.</p>
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
