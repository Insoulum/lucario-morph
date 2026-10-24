package dev.lucario;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;
import net.minecraft.network.chat.Component;

/**
 * Patch 16: shared tree builder for the /lucario and /riolu commands.
 * The same tree is used for the server-side (CommandSourceStack) and
 * client-side (FabricClientCommandSource) commands — previously ~40 lines
 * were copy-pasted between LucarioMod and LucarioClientCommands.
 */
public final class MorphCommands {
   private MorphCommands() {
   }

   /** Adapter over the environment in which the command runs. */
   public interface Bridge<S> {
      /** UUID of the commanding player, or null if unavailable (client, not in world). */
      UUID player(S source) throws CommandSyntaxException;

      /** Persist + propagate the morph (variant == null means "no morph"). */
      void apply(S source, UUID id, String variant);

      void feedback(S source, Component message);

      void failure(S source, Component message);
   }

   /**
    * 1.6.0: {@code /lucario} (no args) now always switches to the BASE form of the
    * family instead of toggling the morph off — so {@code /lucario mega} followed by
    * {@code /lucario} keeps you morphed (as plain Lucario), it does not reset you to
    * the standard skin. Returning to the normal skin is an explicit action:
    * {@code /lucario off} (or the "normal player" button in the picker GUI).
    */
   public static <S> LiteralArgumentBuilder<S> tree(String root, String defaultBase, Bridge<S> bridge) {
      return LiteralArgumentBuilder.<S>literal(root)
         .executes(ctx -> toBase(ctx, defaultBase, bridge))
         .then(LiteralArgumentBuilder.<S>literal("off")
            .executes(ctx -> apply(ctx, null, bridge)))
         .then(RequiredArgumentBuilder.<S, String>argument("variant", StringArgumentType.greedyString())
            .suggests((ctx, builder) -> {
               Variants.suggest(builder, defaultBase);
               return builder.buildFuture();
            })
            .executes(ctx -> applyArg(ctx, defaultBase, bridge)));
   }

   private static <S> int toBase(CommandContext<S> ctx, String defaultBase, Bridge<S> bridge)
      throws CommandSyntaxException {
      UUID id = bridge.player(ctx.getSource());
      if (id == null) {
         return 0;
      }

      return apply(ctx, defaultBase, bridge);
   }

   private static <S> int applyArg(CommandContext<S> ctx, String defaultBase, Bridge<S> bridge) throws CommandSyntaxException {
      return apply(ctx, Variants.resolve(StringArgumentType.getString(ctx, "variant"), defaultBase), bridge);
   }

   private static <S> int apply(CommandContext<S> ctx, String variant, Bridge<S> bridge) throws CommandSyntaxException {
      S source = ctx.getSource();
      UUID id = bridge.player(source);
      if (id == null) {
         return 0;
      }

      String v = Variants.normalize(variant);
      if (v != null && !Variants.isValid(v)) {
         bridge.failure(source, Component.translatable("lucariomod.cmd.unknown"));
         return 0;
      }

      bridge.apply(source, id, v);
      if (v == null) {
         bridge.feedback(source, Component.translatable("lucariomod.cmd.normal"));
      } else {
         // 1.6.2: "You are now Lucario pmd" — proper name, no technical key
         bridge.feedback(source, Variants.morphedMessage(v));
      }

      return 1;
   }
}
