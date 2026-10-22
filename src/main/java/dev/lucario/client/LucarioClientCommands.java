package dev.lucario.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.lucario.Variants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.function.Predicate;

/**
 * Клиентский запасной вариант команд {@code /lucario} и {@code /riolu}.
 *
 * <p>Работают на серверах <b>без</b> мода: образ применяется локально, запоминается
 * в конфиге и публикуется в сервис синхронизации, так что образ видят другие
 * игроки с модом. Если на сервере есть своя команда с тем же именем — Fabric
 * автоматически отправляет её серверу, и клиентская версия игнорируется
 * (сервер остаётся главным источником, как раньше).</p>
 */
@Environment(EnvType.CLIENT)
public final class LucarioClientCommands {
    private LucarioClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("lucario")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.DEFAULT_LUCARIO, Variants::isLucario))
                    .then(ClientCommands.literal("off")
                            .executes(ctx -> apply(ctx.getSource(), null)))
                    .then(ClientCommands.argument("variant", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> {
                                Variants.suggest(builder, Variants.DEFAULT_LUCARIO);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> applyArg(ctx, Variants.DEFAULT_LUCARIO))));

            dispatcher.register(ClientCommands.literal("riolu")
                    .executes(ctx -> toggle(ctx.getSource(), Variants.DEFAULT_RIOLU, Variants::isRiolu))
                    .then(ClientCommands.literal("off")
                            .executes(ctx -> apply(ctx.getSource(), null)))
                    .then(ClientCommands.argument("variant", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> {
                                Variants.suggest(builder, Variants.DEFAULT_RIOLU);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> applyArg(ctx, Variants.DEFAULT_RIOLU))));
        });
    }

    /** Переключатель: если игрок уже в образе из этого семейства — вернуть обычный вид. */
    private static int toggle(FabricClientCommandSource source, String defaultVariant, Predicate<String> sameFamily) {
        LocalPlayer player = source.getPlayer();
        if (player == null) return 0;
        String current = ClientMorphs.get(player.getUUID());
        return apply(source, current != null && sameFamily.test(current) ? null : defaultVariant);
    }

    private static int applyArg(CommandContext<FabricClientCommandSource> ctx, String defaultBase) {
        return apply(ctx.getSource(), Variants.resolve(StringArgumentType.getString(ctx, "variant"), defaultBase));
    }

    private static int apply(FabricClientCommandSource source, String variant) {
        LocalPlayer player = source.getPlayer();
        if (player == null) return 0;
        String v = Variants.normalize(variant);
        if (v != null && !Variants.isValid(v)) {
            source.sendError(Component.literal(
                    "Неизвестный вариант. Нажмите Tab, чтобы увидеть список (например: /lucario captain, /lucario captain shiny, /lucario mega)."));
            return 0;
        }
        ClientMorphs.set(player.getUUID(), v);
        SyncConfig cfg = SyncConfig.get();
        cfg.lastVariant = v == null ? "" : v;
        cfg.save();
        SyncClient.publish(v);
        source.sendFeedback(Component.literal(v == null
                ? "Вы снова обычный игрок."
                : "Вы теперь " + Variants.title(v) + " (" + v + ")!"));
        return 1;
    }
}
