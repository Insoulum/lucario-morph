package dev.lucario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Общие константы мода. Сам мод — чисто клиентский ("environment": "client" в
 * fabric.mod.json): на выделенных серверах он не загружается вовсе, а образы и
 * эмоуты синхронизируются только через внешний сервис (см. dev.lucario.client.SyncClient).
 * Классического серверного канала (MorphPayload/MorphManager) больше нет — 1.8.0.
 */
public final class LucarioMod {
    public static final String MOD_ID = "lucariomod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private LucarioMod() {}
}
