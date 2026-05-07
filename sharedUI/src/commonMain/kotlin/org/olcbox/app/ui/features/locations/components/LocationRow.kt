package org.olcbox.app.ui.features.locations.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.util.parseEmojiAndName

@Composable
fun LocationRow(
    location: LocationItem,
    isSelected: Boolean,
    isLoading: Boolean,
    pingMs: Int?,
    isError: Boolean = false,
    settingsEnabled: Boolean = true,
    onSettingsClick: () -> Unit = {},
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "locationRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "locationRowBorder"
    )
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val textColor = MaterialTheme.colorScheme.onSurface

    val metadata = location.metadata
    val rawName = metadata?.name?.takeIf { it.isNotBlank() } ?: location.fullName
    val fallbackIcon = metadata?.icon?.takeIf { it.isNotBlank() }
        ?: metadata?.subscription?.icon?.takeIf { it.isNotBlank() }
        ?: ""
    val (emoji, parsedName) = parseEmojiAndName(rawName, fallbackIcon)
    val cleanName = parsedName.ifBlank { location.config?.displayName().orEmpty() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        if (emoji.isNotEmpty()) {
            Text(text = emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanName,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = locationSubtitle(location),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (settingsEnabled) {
            IconButton(
                onClick = onSettingsClick,
                content = {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            )
        }


        when {
            isLoading -> {
                Text(
                    text = "Checking",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            pingMs != null -> {
                Text(
                    text = "$pingMs ms",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            isError -> {
                Text(
                    text = "Offline",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        LocationSelectionIndicator(isSelected = isSelected)
    }
}

private fun locationSubtitle(location: LocationItem): String {
    val config = location.config
    val metadata = location.metadata
    val providerName = config?.providerName()
        ?: LocationConfig.providerDisplayName(LocationConfig.DEFAULT_BYPASS_PROVIDER)
    val transportName = config?.transportName()
        ?: LocationConfig.transportDisplayName(LocationConfig.DEFAULT_TRANSPORT)

    return listOfNotNull(
        providerName,
        transportName,
        metadata?.comment?.takeIf { it.isNotBlank() },
        metadata?.ip?.takeIf { it.isNotBlank() }?.let { "IP $it" },
        quotaText(metadata?.used, metadata?.available)
    ).joinToString(" · ")
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

@Composable
private fun LocationSelectionIndicator(isSelected: Boolean) {
    if (isSelected) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Selected location",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.size(24.dp))
        }
    }
}
