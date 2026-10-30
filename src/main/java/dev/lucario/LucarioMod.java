package dev.lucario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared mod constants. The mod itself is purely client-side ("environment": "client" in
 * fabric.mod.json): it never loads on dedicated servers, and morphs and
 * emotes are synced only through the external service (see dev.lucario.client.SyncClient).
 * The classic server-side channel (MorphPayload/MorphManager) is gone as of 1.8.0.
 */
public final class LucarioMod {
    public static final String MOD_ID = "lucariomod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private LucarioMod() {}
}
