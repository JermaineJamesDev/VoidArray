package io.github.jermainejamesdev.voidarray.engine

import io.github.jermainejamesdev.voidarray.core.HistoryEntry
import io.github.jermainejamesdev.voidarray.core.TrustedDevice
import io.github.jermainejamesdev.voidarray.protocol.ProtocolJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Devices the user chose to trust, keyed by device id and pinned to the fingerprint seen at that time. */
internal class TrustStore(private val store: KeyValueStore) {
    private val serializer = ListSerializer(TrustedDevice.serializer())

    @Volatile
    var devices: List<TrustedDevice> = load()
        private set

    fun find(deviceId: String): TrustedDevice? = devices.find { it.deviceId == deviceId }

    @Synchronized
    fun add(device: TrustedDevice) {
        save(devices.filterNot { it.deviceId == device.deviceId } + device)
    }

    @Synchronized
    fun remove(deviceId: String) {
        save(devices.filterNot { it.deviceId == deviceId })
    }

    private fun load(): List<TrustedDevice> =
        store.get(KEY)?.let { runCatching { ProtocolJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun save(list: List<TrustedDevice>) {
        devices = list.sortedBy { it.alias.lowercase() }
        store.put(KEY, ProtocolJson.encodeToString(serializer, devices))
    }

    private companion object {
        const val KEY = "trustedDevices"
    }
}

/** Finished transfers, newest first, persisted as JSON. A missing or corrupt file starts an empty history. */
internal class HistoryStore(private val file: File?) {
    private val serializer = ListSerializer(HistoryEntry.serializer())

    fun load(): List<HistoryEntry> {
        val source = file?.takeIf { it.isFile } ?: return emptyList()
        return runCatching { ProtocolJson.decodeFromString(serializer, source.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(entries: List<HistoryEntry>) {
        val target = file ?: return
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(ProtocolJson.encodeToString(serializer, entries))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_FILES_PER_ENTRY = 20
    }
}
