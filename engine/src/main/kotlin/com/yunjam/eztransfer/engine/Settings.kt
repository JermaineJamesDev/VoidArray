package com.yunjam.eztransfer.engine

import com.yunjam.eztransfer.core.ThemeMode
import java.io.File
import java.util.Properties
import java.util.UUID

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Small properties-file store used on desktop. Writes go through a temp file so a crash cannot truncate it. */
class PropertiesFileStore(private val file: File) : KeyValueStore {
    private val properties = Properties().apply {
        if (file.isFile) file.inputStream().use { load(it) }
    }

    @Synchronized
    override fun get(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value == null) properties.remove(key) else properties.setProperty(key, value)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { properties.store(it, null) }
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}

class AppSettings(val store: KeyValueStore, private val defaultAlias: String) {
    val deviceId: String = store.get(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also {
        store.put(KEY_DEVICE_ID, it)
    }

    var alias: String
        get() = store.get(KEY_ALIAS)?.takeIf { it.isNotBlank() } ?: defaultAlias
        set(value) = store.put(KEY_ALIAS, value.trim().ifEmpty { null })

    /** Platform-specific destination: a filesystem path on desktop, a SAF tree URI on Android. */
    var destination: String?
        get() = store.get(KEY_DESTINATION)
        set(value) = store.put(KEY_DESTINATION, value)

    var autoAcceptTrusted: Boolean
        get() = store.get(KEY_AUTO_ACCEPT) == "true"
        set(value) = store.put(KEY_AUTO_ACCEPT, value.toString())

    var theme: ThemeMode
        get() = store.get(KEY_THEME)?.let { name -> ThemeMode.entries.find { it.name == name } } ?: ThemeMode.SYSTEM
        set(value) = store.put(KEY_THEME, value.name)

    var minimizeToTray: Boolean
        get() = store.get(KEY_TRAY) != "false"
        set(value) = store.put(KEY_TRAY, value.toString())

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_ALIAS = "alias"
        const val KEY_DESTINATION = "destination"
        const val KEY_AUTO_ACCEPT = "autoAcceptTrusted"
        const val KEY_THEME = "theme"
        const val KEY_TRAY = "minimizeToTray"
    }
}

/**
 * Gate checked before any socket is opened. On Android 17+ with targetSdk 37+ a denied
 * ACCESS_LOCAL_NETWORK makes TCP connects time out and UDP fail with EPERM, which is
 * indistinguishable from a firewall problem unless it is checked up front.
 */
fun interface LocalNetworkAccess {
    suspend fun ensure(): Boolean
}
