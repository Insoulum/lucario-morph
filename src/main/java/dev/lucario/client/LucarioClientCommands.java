package dev.lucario.client;

import dev.lucario.MorphCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Client-side {@code /lucario} and {@code /riolu} commands - since 1.8.0 the only version
 * (the mod no longer has a server-side one). The morph is applied locally, stored in the
 * config and published to the sync service, so other players with the mod can see it.
 * If the server has a foreign command with the same name (a datapack/another mod), Fabric
 * forwards it to the server and the client-side version is ignored: that is how server
 * command priority works in brigadier.
 *
 * <p>The command tree is shared ({@link MorphCommands}); only the adapter over the
 * client environment lives here.</p>
 */
@Environment(EnvType.CLIENT)
public final class LucarioClientCommands {
    private LucarioClientCommands() {}

    /** Adapter: the command tree is the same, the execution environment is client-side. */
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
