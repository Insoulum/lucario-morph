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
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

public class LucarioMod implements ModInitializer {
    public static final String MOD_ID = "lucariomod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(MorphPayload.ID, MorphPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(MorphManager::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(MorphManager::unload);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            MorphManager.sendAllTo(player);
            String mine = MorphManager.get(player.getUuid());
            if (mine != null) MorphManager.broadcast(server, player.getUuid(), mine);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /lucario [вариант | off]
            dispatcher.register(CommandManager.literal("lucario")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.DEFAULT_LUCARIO, Variants::isLucario))
                    .then(CommandManager.literal("off")
                            .executes(ctx -> apply(ctx.getSource(), null)))
                    .then(CommandManager.argument("variant", StringArgumentType.word())
                            .suggests((ctx, builder) -> CommandSource.suggestMatching(Variants.lucarioSuggestions(), builder))
                            .executes(ctx -> applyArg(ctx, Variants.DEFAULT_LUCARIO))));

            // /riolu [вариант | off]
            dispatcher.register(CommandManager.literal("riolu")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.DEFAULT_RIOLU, Variants::isRiolu))
                    .then(CommandManager.literal("off")
                            .executes(ctx -> apply(ctx.getSource(), null)))
                    .then(CommandManager.argument("variant", StringArgumentType.word())
                            .suggests((ctx, builder) -> CommandSource.suggestMatching(Variants.rioluSuggestions(), builder))
                            .executes(ctx -> applyArg(ctx, Variants.DEFAULT_RIOLU))));

            // /mega [shiny] — быстрый переключатель Мега-Лукарио
            dispatcher.register(CommandManager.literal("mega")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.MEGA, Variants::isMega))
                    .then(CommandManager.literal("shiny")
                            .executes(ctx -> apply(ctx.getSource(), Variants.MEGA + Variants.SHINY_SUFFIX))));
        });
    }

    /** Переключатель: если игрок уже в образе из этого семейства — вернуть обычный вид, иначе включить образ по умолчанию. */
    private static int toggle(ServerCommandSource source, String defaultVariant, Predicate<String> sameFamily)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String current = MorphManager.get(player.getUuid());
        return apply(source, current != null && sameFamily.test(current) ? null : defaultVariant);
    }

    private static int applyArg(CommandContext<ServerCommandSource> ctx, String defaultBase) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(ctx, "variant");
        return apply(ctx.getSource(), Variants.resolve(raw, defaultBase));
    }

    private static int apply(ServerCommandSource source, String variant) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String v = Variants.normalize(variant);
        if (v != null && !Variants.isValid(v)) {
            source.sendError(Text.literal("Неизвестный вариант. Нажмите Tab, чтобы увидеть список."));
            return 0;
        }
        MorphManager.set(source.getServer(), player.getUuid(), v);
        source.sendFeedback(() -> Text.literal(v == null
                ? "Вы снова обычный игрок."
                : "Вы теперь " + Variants.title(v) + " (" + v + ")!"), false);
        return 1;
    }
}
