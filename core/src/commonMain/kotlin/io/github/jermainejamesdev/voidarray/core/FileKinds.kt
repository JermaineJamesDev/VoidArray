package io.github.jermainejamesdev.voidarray.core

private val executableExtensions = setOf(
    "exe", "msi", "msix", "appx", "bat", "cmd", "com", "scr", "pif", "cpl", "ps1", "psm1", "vbs", "vbe", "js", "jse",
    "wsf", "wsh", "hta", "lnk", "reg", "jar", "dll", "apk", "aab", "xapk", "sh", "command", "app", "dmg", "pkg",
)

/**
 * File types that run code when opened. The user is told before accepting them, and offers containing
 * them are never auto-accepted, even from a trusted device.
 */
fun looksExecutable(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in executableExtensions
