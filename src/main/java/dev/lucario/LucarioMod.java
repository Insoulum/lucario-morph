package dev.lucario;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lucario.net.MorphPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class LucarioMod implements ModInitializer {
    public static final String MOD_ID = "lucariomod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Мост для общих команд /lucario и /riolu (дерево в MorphCommands). */
    private static final MorphCommands.Bridge<CommandSourceStack> SERVER_BRIDGE = new MorphCommands.Bridge<>() {
        @Override
        public UUID player(CommandSourceStack source) throws CommandSyntaxException {
            return source.getPlayerOrException().getUUID();
        }

        @Override
        public String current(UUID id) {
            return MorphManager.get(id);
        }

        @Override
        public void apply(CommandSourceStack source, UUID id, String variant) {
            MorphManager.set(source.getServer(), id, variant);
        }

        @Override
        public void feedback(CommandSourceStack source, Component message) {
            source.sendSuccess(() -> message, false);
        }

        @Override
        public void failure(CommandSourceStack source, Component message) {
            source.sendFailure(message);
        }
    };

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(MorphPayload.ID, MorphPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(MorphManager::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(MorphManager::unload);
        ServerTickEvents.END_SERVER_TICK.register(MorphManager::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            MorphManager.sendAllTo(player);
            String mine = MorphManager.get(player.getUUID());
            if (mine != null) MorphManager.broadcast(server, player.getUUID(), mine);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(MorphCommands.tree("lucario", Variants.DEFAULT_LUCARIO, SERVER_BRIDGE));
            dispatcher.register(MorphCommands.tree("riolu", Variants.DEFAULT_RIOLU, SERVER_BRIDGE));
        });
    }
}
