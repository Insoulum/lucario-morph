package dev.lucario;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lucario.net.MorphPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

public class LucarioMod implements ModInitializer {
    public static final String MOD_ID = "lucariomod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(MorphPayload.ID, MorphPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(MorphManager::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(MorphManager::unload);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            MorphManager.sendAllTo(player);
            String mine = MorphManager.get(player.getUUID());
            if (mine != null) MorphManager.broadcast(server, player.getUUID(), mine);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /lucario [вариант | off] — например /lucario captain, /lucario captain shiny, /lucario mega
            dispatcher.register(Commands.literal("lucario")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.DEFAULT_LUCARIO, Variants::isLucario))
                    .then(Commands.literal("off")
                            .executes(ctx -> apply(ctx.getSource(), null)))
                    .then(Commands.argument("variant", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> { Variants.suggest(builder, Variants.DEFAULT_LUCARIO); return builder.buildFuture(); })
                            .executes(ctx -> applyArg(ctx, Variants.DEFAULT_LUCARIO))));

            // /riolu [вариант | off] — например /riolu pmd, /riolu pmd shiny
            dispatcher.register(Commands.literal("riolu")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.DEFAULT_RIOLU, Variants::isRiolu))
                    .then(Commands.literal("off")
                            .executes(ctx -> apply(ctx.getSource(), null)))
                    .then(Commands.argument("variant", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> { Variants.suggest(builder, Variants.DEFAULT_RIOLU); return builder.buildFuture(); })
                            .executes(ctx -> applyArg(ctx, Variants.DEFAULT_RIOLU))));
        });
    }

    /** Переключатель: если игрок уже в образе из этого семейства — вернуть обычный вид, иначе включить образ по умолчанию. */
    private static int toggle(CommandSourceStack source, String defaultVariant, Predicate<String> sameFamily)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String current = MorphManager.get(player.getUUID());
        return apply(source, current != null && sameFamily.test(current) ? null : defaultVariant);
    }

    private static int applyArg(CommandContext<CommandSourceStack> ctx, String defaultBase) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(ctx, "variant");
        return apply(ctx.getSource(), Variants.resolve(raw, defaultBase));
    }

    private static int apply(CommandSourceStack source, String variant) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String v = Variants.normalize(variant);
        if (v != null && !Variants.isValid(v)) {
            source.sendFailure(Component.literal("Неизвестный вариант. Нажмите Tab, чтобы увидеть список (например: /lucario captain, /lucario captain shiny, /lucario mega)."));
            return 0;
        }
        MorphManager.set(source.getServer(), player.getUUID(), v);
        source.sendSuccess(() -> Component.literal(v == null
                ? "Вы снова обычный игрок."
                : "Вы теперь " + Variants.title(v) + " (" + v + ")!"), false);
        return 1;
    }
}
