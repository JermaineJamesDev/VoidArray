package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.protocol.DeviceType

/** A screen section: a tonal card with a title row. Depth comes from surface lightness, not borders. */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                action?.invoke()
            }
            content()
        }
    }
}

/** A row set one step lighter than its card, for files, devices and key-value details. */
@Composable
internal fun InsetRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

enum class Tone { JADE, GOLD, DANGER, WARNING, NEUTRAL }

private data class ToneColors(val content: Color, val tint: Color)

@Composable
private fun toneColors(tone: Tone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val accent = when (tone) {
        Tone.JADE -> scheme.primary
        Tone.GOLD -> scheme.secondary
        Tone.DANGER -> scheme.error
        Tone.WARNING -> VoidArrayTheme.extras.warning
        Tone.NEUTRAL -> scheme.onSurfaceVariant
    }
    val tint = if (tone == Tone.NEUTRAL) scheme.surfaceContainerHigh else accent.copy(alpha = 0.14f)
    return ToneColors(accent, tint)
}

/** An icon on a faceted tile washed with its tone, for devices, files and transfer states. */
@Composable
internal fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.GOLD,
    size: Dp = 40.dp,
) {
    val colors = toneColors(tone)
    Box(
        modifier = modifier.size(size).clip(MaterialTheme.shapes.small).background(colors.tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.5f))
    }
}

/** A short status label such as "Trusted" or "Key changed". */
@Composable
internal fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = toneColors(tone)
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.tint)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.content)
    }
}

/** An uppercase caption that heads a group inside a card or dialog. */
@Composable
internal fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text)
}

/** The main action in a context: jade, faceted like the cards. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small, contentPadding = ButtonPadding) {
        ButtonContent(text, icon)
    }
}

/** A supporting action on a neutral control surface. */
@Composable
internal fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        ButtonContent(text, icon)
    }
}

/** The safe choice when a warning is showing: high contrast, so it reads as the default. */
@Composable
internal fun EmphaticButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Text(text)
    }
}

/** A low-emphasis text action such as "Clear" or "Open folder". */
@Composable
internal fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        ButtonContent(text, icon)
    }
}

internal fun deviceIcon(type: DeviceType): ImageVector =
    if (type == DeviceType.MOBILE) AppIcons.Phone else AppIcons.Computer

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "avif")

internal fun fileIcon(name: String): ImageVector = when {
    looksExecutable(name) -> AppIcons.Program
    name.substringAfterLast('.', "").lowercase() in imageExtensions -> AppIcons.Image
    else -> AppIcons.File
}

@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    art: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (art != null) art() else IconTile(icon, tone = Tone.NEUTRAL, size = 52.dp)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

enum class BannerKind { ERROR, WARNING, INFO }

@Composable
internal fun Banner(
    message: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    onDismiss: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val extras = VoidArrayTheme.extras
    val (container, onContainer, accent) = when (kind) {
        BannerKind.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
        BannerKind.WARNING -> Triple(extras.warningContainer, extras.onWarningContainer, extras.warning)
        BannerKind.INFO -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
    }
    val icon = if (kind == BannerKind.INFO) AppIcons.CheckCircle else AppIcons.Warning
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = onContainer)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            action?.invoke()
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = "Dismiss", tint = onContainer) }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val tenths = (value * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}

internal fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

/** Remaining time like "2 min left", or null when the speed is too uncertain to estimate. */
internal fun formatRemaining(remainingBytes: Long, bytesPerSecond: Long): String? {
    if (bytesPerSecond <= 0 || remainingBytes <= 0) return null
    val seconds = remainingBytes / bytesPerSecond
    return when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)} s left"
        seconds < 3600 -> "${seconds / 60} min left"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
    }
}

internal fun looksLikeUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.none { it.isWhitespace() } && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
}

private val executableExtensions = setOf(
    "exe", "msi", "msix", "appx", "bat", "cmd", "com", "scr", "pif", "cpl", "ps1", "psm1", "vbs", "vbe", "js", "jse",
    "wsf", "wsh", "hta", "lnk", "reg", "jar", "dll", "apk", "aab", "xapk", "sh", "command", "app", "dmg", "pkg",
)

/** File types that run code when opened, so the user is told before accepting them from another device. */
internal fun looksExecutable(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in executableExtensions
