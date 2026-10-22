package dev.lucario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.lucario.net.MorphPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Серверное состояние: кто сейчас Лукарио. Сохраняется в папке мира. */
public final class MorphManager {
    private static final Map<UUID, String> MORPHS = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    private MorphManager() {}

    public static void load(MinecraftServer server) {
        MORPHS.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("lucariomod_morphs.json");
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, String> m = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
            if (m == null) return;
            m.forEach((k, v) -> {
                try {
                    if (Variants.isValid(v)) MORPHS.put(UUID.fromString(k), Variants.normalize(v));
                } catch (IllegalArgumentException ignored) {
                }
            });
        } catch (IOException | JsonParseException e) {
            LucarioMod.LOGGER.warn("Не удалось прочитать {}", file, e);
        }
    }

    public static void unload(MinecraftServer server) {
        MORPHS.clear();
        file = null;
    }

    private static void save() {
        if (file == null) return;
        Map<String, String> out = new HashMap<>();
        MORPHS.forEach((k, v) -> out.put(k.toString(), v));
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(out, w);
        } catch (IOException e) {
            LucarioMod.LOGGER.warn("Не удалось сохранить {}", file, e);
        }
    }

    public static String get(UUID id) {
        return MORPHS.get(id);
    }

    /** variant == null -> вернуть обычный облик. */
    public static void set(MinecraftServer server, UUID id, String variant) {
        if (variant == null) MORPHS.remove(id);
        else MORPHS.put(id, variant);
        save();
        broadcast(server, id, variant);
    }

    public static void broadcast(MinecraftServer server, UUID id, String variant) {
        MorphPayload payload = new MorphPayload(id.toString(), variant == null ? "" : variant);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(p, MorphPayload.ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    /** Отправить новому игроку текущие облики всех. */
    public static void sendAllTo(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, MorphPayload.ID)) return;
        MORPHS.forEach((id, variant) -> ServerPlayNetworking.send(player, new MorphPayload(id.toString(), variant)));
    }
}
