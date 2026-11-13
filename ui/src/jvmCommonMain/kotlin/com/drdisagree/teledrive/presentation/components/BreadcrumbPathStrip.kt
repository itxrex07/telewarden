package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.common.resolve
import com.drdisagree.teledrive.presentation.files.FolderCrumb

/**
 * Modern interactive breadcrumb path strip adapted from Cloudyte,
 * styled strictly using TeleDrive's native Material 3 theme.
 */
@Composable
fun BreadcrumbPathStrip(
    crumbs: List<FolderCrumb>,
    onOpenCrumb: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to end whenever crumbs change
    LaunchedEffect(crumbs.size) {
        if (crumbs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        crumbs.forEachIndexed { index, crumb ->
            val isLast = index == crumbs.lastIndex
            val isRoot = index == 0

            Surface(
                onClick = { onOpenCrumb(crumb.id) },
                enabled = !isLast,
                shape = RoundedCornerShape(8.dp),
                color = if (isLast) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                border = BorderStroke(
                    1.dp,
                    if (isLast) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isRoot) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isLast) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Text(
                        text = if (isRoot) "Root" else crumb.name.resolve(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        color = if (isLast) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!isLast) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
