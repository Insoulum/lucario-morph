package dev.lucario.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public final class ClientMorphs {
    private static final Map<UUID, String> MAP = new ConcurrentHashMap<>();

    private ClientMorphs() {}

    public static String get(UUID id) {
        return MAP.get(id);
    }

    public static void set(UUID id, String variant) {
        if (variant == null || variant.isEmpty()) MAP.remove(id);
        else MAP.put(id, variant);
    }

    public static void clear() {
        MAP.clear();
    }
}
