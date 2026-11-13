package com.drdisagree.teledrive.presentation.channels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.teledrive.core.telegram.TelegramChatSummary
import com.drdisagree.teledrive.domain.model.DriveChannel
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import com.drdisagree.teledrive.domain.model.TelegramChatType
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.add
import com.drdisagree.teledrive.presentation.common.resolve
import com.drdisagree.teledrive.presentation.components.BlockingProgressDialog
import com.drdisagree.teledrive.presentation.components.ChannelAvatar
import com.drdisagree.teledrive.presentation.components.CollectionShortcuts
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.keyboardCollection
import com.drdisagree.teledrive.presentation.components.keyboardCollectionItem
import com.drdisagree.teledrive.presentation.components.liftedTopAppBarColors
import com.drdisagree.teledrive.presentation.components.rememberKeyboardCollection
import com.drdisagree.teledrive.presentation.components.rememberToolbarLift
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.channels_channel_container
import com.drdisagree.teledrive.resources.channels_create_one_teledrive_store
import com.drdisagree.teledrive.resources.channels_creates_private_channel_account
import com.drdisagree.teledrive.resources.channels_delete_confirm_hint
import com.drdisagree.teledrive.resources.channels_delete_message
import com.drdisagree.teledrive.resources.channels_delete_telegram
import com.drdisagree.teledrive.resources.channels_delete_title
import com.drdisagree.teledrive.resources.channels_drive_actions
import com.drdisagree.teledrive.resources.channels_drive_name
import com.drdisagree.teledrive.resources.channels_look_drives
import com.drdisagree.teledrive.resources.channels_new_drive
import com.drdisagree.teledrive.resources.channels_no_drives_yet
import com.drdisagree.teledrive.resources.channels_open_drive
import com.drdisagree.teledrive.resources.channels_rename_drive
import com.drdisagree.teledrive.resources.channels_renames_channel_telegram_well
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.common_cancel
import com.drdisagree.teledrive.resources.common_create
import com.drdisagree.teledrive.resources.common_delete_forever
import com.drdisagree.teledrive.resources.common_rename
import com.drdisagree.teledrive.resources.common_storage_channels
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = koinViewModel()
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val availableChats by viewModel.availableChats.collectAsStateWithLifecycle()
    val loadingChats by viewModel.loadingChats.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreate by remember { mutableStateOf(false) }
    var showLinkExisting by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DriveChannel?>(null) }
    var deleteTarget by remember { mutableStateOf<DriveChannel?>(null) }
    var syncModeTarget by remember { mutableStateOf<DriveChannel?>(null) }

    CollectSnackbarMessages(viewModel.messages, snackbarHostState)

    working?.let { BlockingProgressDialog(message = it.resolve()) }

    if (showCreate) {
        ChannelNameDialog(
            title = stringResource(Res.string.channels_new_drive),
            description = stringResource(Res.string.channels_creates_private_channel_account),
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = { label ->
                showCreate = false
                viewModel.create(label)
            },
            onDismiss = { showCreate = false }
        )
    }

    if (showLinkExisting) {
        LaunchedEffect(Unit) {
            viewModel.loadAvailableChats()
        }
        ExistingChatPickerDialog(
            chats = availableChats,
            linkedChatIds = channels.map { it.chatId }.toSet(),
            loading = loadingChats,
            onRefresh = viewModel::loadAvailableChats,
            onSelect = { chat, mode ->
                showLinkExisting = false
                viewModel.linkExistingChat(chat, mode)
            },
            onDismiss = { showLinkExisting = false }
        )
    }

    syncModeTarget?.let { channel ->
        SyncModeDialog(
            channel = channel,
            onConfirm = { mode ->
                syncModeTarget = null
                viewModel.setSyncMode(channel.chatId, mode)
            },
            onDismiss = { syncModeTarget = null }
        )
    }

    renameTarget?.let { channel ->
        ChannelNameDialog(
            title = stringResource(Res.string.channels_rename_drive),
            description = stringResource(Res.string.channels_renames_channel_telegram_well),
            confirmLabel = stringResource(Res.string.common_rename),
            initialValue = channel.label,
            onConfirm = { label ->
                renameTarget = null
                viewModel.rename(channel.chatId, label)
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { channel ->
        DeleteChannelDialog(
            channel = channel,
            onConfirm = {
                deleteTarget = null
                viewModel.deleteRemotely(channel.chatId)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    val listState = rememberLazyListState()
    val lifted by rememberToolbarLift(listState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = liftedTopAppBarColors(lifted),
                title = { Text(stringResource(Res.string.common_storage_channels)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(Res.string.channels_look_drives)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { showLinkExisting = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Existing")
                }
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(Res.string.channels_new_drive)
                        )
                    },
                    text = { Text(stringResource(Res.string.channels_new_drive)) }
                )
            }
        }
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.CloudQueue,
                title = stringResource(Res.string.channels_no_drives_yet),
                description = stringResource(Res.string.channels_create_one_teledrive_store),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        val keyboard = rememberKeyboardCollection(listState)
        val shortcuts = CollectionShortcuts(
            onDelete = { index ->
                if (channels.size > 1) {
                    channels.getOrNull(index)?.let { ch ->
                        if (ch.isLinkedChat) viewModel.unlinkLocal(ch.chatId) else deleteTarget = ch
                    }
                }
            },
            onRename = { index ->
                channels.getOrNull(index)?.takeIf { !it.isLinkedChat }?.let { renameTarget = it }
            },
            onBack = onBack
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .keyboardCollection(keyboard, channels.size, shortcuts),
            contentPadding = padding.add(horizontal = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(channels, key = { _, it -> it.chatId }) { index, channel ->
                ChannelRow(
                    channel = channel,
                    onOpen = { viewModel.switchTo(channel.chatId, onSwitched = onBack) },
                    onConfigureMode = { syncModeTarget = channel },
                    onRename = { renameTarget = channel },
                    onUnlink = { viewModel.unlinkLocal(channel.chatId) },
                    onDelete = { deleteTarget = channel },
                    canDelete = channels.size > 1,
                    actionsFocusable = keyboard.current == index,
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, index)
                        .animateItem(
                            fadeInSpec = tween(ROW_FADE_MS),
                            fadeOutSpec = tween(ROW_FADE_MS),
                            placementSpec = spring(
                                stiffness = Spring.StiffnessMediumLow,
                                visibilityThreshold = IntOffset.VisibilityThreshold
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: DriveChannel,
    onOpen: () -> Unit,
    onConfigureMode: () -> Unit,
    onRename: () -> Unit,
    onUnlink: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    actionsFocusable: Boolean,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        targetValue = if (channel.isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(CONTAINER_FADE_MS),
        label = stringResource(Res.string.channels_channel_container)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(container)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { selected = channel.isActive }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ChannelAvatar(channel = channel)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ChatTypeBadge(channel.chatType)
            }
            Text(
                text = channelSubtitle(channel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(
            visible = channel.isActive,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(Res.string.channels_open_drive),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.focusProperties { canFocus = actionsFocusable }
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(Res.string.channels_drive_actions)
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Drive Mode (${syncModeShortLabel(channel.syncMode)})") },
                    leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onConfigureMode()
                    }
                )
                if (!channel.isLinkedChat) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_rename)) },
                        leadingIcon = {
                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                }
                if (channel.isLinkedChat) {
                    DropdownMenuItem(
                        text = { Text("Unlink from App") },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                        enabled = canDelete,
                        onClick = {
                            showMenu = false
                            onUnlink()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.channels_delete_telegram)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        enabled = canDelete,
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTypeBadge(chatType: TelegramChatType) {
    val label = when (chatType) {
        TelegramChatType.DRIVE_CHANNEL -> "Drive"
        TelegramChatType.CHANNEL -> "Channel"
        TelegramChatType.FORUM_GROUP -> "Topics"
        TelegramChatType.GROUP -> "Group"
        TelegramChatType.SAVED_MESSAGES -> "Saved"
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun syncModeShortLabel(mode: DriveSyncMode): String = when (mode) {
    DriveSyncMode.SAFE -> "Safe"
    DriveSyncMode.READ_ONLY -> "Read-Only"
    DriveSyncMode.FULL -> "Full"
}

private fun channelSubtitle(channel: DriveChannel): String = buildString {
    val count = if (channel.isIndexed) channel.fileCount else channel.remoteFileCount
    append(if (count == 1) "1 file" else "$count files")
    if (channel.isIndexed) {
        append(" · ")
        append(Formatters.bytes(channel.storedBytes))
    } else {
        append(" · opens on first use")
    }
    append(" · ")
    append(syncModeShortLabel(channel.syncMode))
    if (channel.backupFolders.isNotEmpty()) {
        append(" · ")
        append(
            if (channel.backupFolders.size == 1) {
                "1 backup folder"
            } else {
                "${channel.backupFolders.size} backup folders"
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExistingChatPickerDialog(
    chats: List<TelegramChatSummary>,
    linkedChatIds: Set<Long>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSelect: (TelegramChatSummary, DriveSyncMode) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf<TelegramChatType?>(null) }
    var selectedSyncMode by remember { mutableStateOf(DriveSyncMode.SAFE) }

    val filteredChats = remember(chats, searchQuery, selectedTypeFilter) {
        chats.filter { chat ->
            val matchesSearch = searchQuery.isBlank() ||
                    chat.title.contains(searchQuery.trim(), ignoreCase = true)
            val matchesType = selectedTypeFilter == null || chat.chatType == selectedTypeFilter
            matchesSearch && matchesType
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Link Existing Chat or Channel",
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh chats")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Browse media, forum topics as folders, and text messages from any existing channel or group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search channels or groups…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = selectedTypeFilter == null,
                        onClick = { selectedTypeFilter = null },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = selectedTypeFilter == TelegramChatType.FORUM_GROUP,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == TelegramChatType.FORUM_GROUP) null else TelegramChatType.FORUM_GROUP
                        },
                        label = { Text("Topic Groups") }
                    )
                    FilterChip(
                        selected = selectedTypeFilter == TelegramChatType.CHANNEL,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == TelegramChatType.CHANNEL) null else TelegramChatType.CHANNEL
                        },
                        label = { Text("Channels") }
                    )
                    FilterChip(
                        selected = selectedTypeFilter == TelegramChatType.GROUP,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == TelegramChatType.GROUP) null else TelegramChatType.GROUP
                        },
                        label = { Text("Groups") }
                    )
                    FilterChip(
                        selected = selectedTypeFilter == TelegramChatType.SAVED_MESSAGES,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == TelegramChatType.SAVED_MESSAGES) null else TelegramChatType.SAVED_MESSAGES
                        },
                        label = { Text("Saved") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Mode:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DriveSyncMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedSyncMode == mode,
                            onClick = { selectedSyncMode = mode },
                            label = { Text(syncModeShortLabel(mode)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (loading) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (filteredChats.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    ) {
                        Text(
                            text = "No matching Telegram chats found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(filteredChats, key = { it.chatId }) { chat ->
                            val alreadyLinked = chat.chatId in linkedChatIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable(enabled = !alreadyLinked) {
                                        onSelect(chat, selectedSyncMode)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                ChannelAvatar(
                                    channel = DriveChannel(
                                        chatId = chat.chatId,
                                        title = chat.title,
                                        fileCount = 0,
                                        remoteFileCount = 0,
                                        storedBytes = 0L,
                                        backupFolders = emptySet(),
                                        photoPath = chat.photoPath,
                                        isActive = false,
                                        isIndexed = false,
                                        lastOpenedAt = 0L,
                                        isLinkedChat = true,
                                        chatType = chat.chatType
                                    ),
                                    size = 40.dp
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = chat.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = buildString {
                                        append(
                                            when (chat.chatType) {
                                                TelegramChatType.FORUM_GROUP -> "Forum Group (Topics → Folders)"
                                                TelegramChatType.CHANNEL -> "Channel"
                                                TelegramChatType.GROUP -> "Group"
                                                TelegramChatType.SAVED_MESSAGES -> "Personal Cloud"
                                                TelegramChatType.DRIVE_CHANNEL -> "TeleWarden Channel"
                                            }
                                        )
                                        if (chat.subtitle.isNotBlank()) {
                                            append(" · ${chat.subtitle}")
                                        }
                                        if (alreadyLinked) {
                                            append(" · Linked")
                                        }
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (alreadyLinked) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Already linked",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes()
            ) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncModeDialog(
    channel: DriveChannel,
    onConfirm: (DriveSyncMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(channel.syncMode) { mutableStateOf(channel.syncMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drive Mode · ${channel.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SyncModeOptionRow(
                    title = "Safe Mode (Recommended for shared chats)",
                    description = "Browse, download, and upload files without posting TeleWarden JSON index files or editing existing captions.",
                    selected = selected == DriveSyncMode.SAFE,
                    onClick = { selected = DriveSyncMode.SAFE }
                )
                SyncModeOptionRow(
                    title = "Read-Only Mode",
                    description = "Strictly view, stream, and download content. Uploads, folder creation, and deletions are disabled.",
                    selected = selected == DriveSyncMode.READ_ONLY,
                    onClick = { selected = DriveSyncMode.READ_ONLY }
                )
                SyncModeOptionRow(
                    title = "Full TeleWarden Mode",
                    description = "Full metadata sync — stores folder trees and file manifests inside the chat for cross-device TeleWarden sync.",
                    selected = selected == DriveSyncMode.FULL,
                    onClick = { selected = DriveSyncMode.FULL }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                shapes = ButtonDefaults.shapes()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes()
            ) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@Composable
private fun SyncModeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Deleting destroys every file in the drive, so the name has to be typed; a tap is too easy by
 * accident.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeleteChannelDialog(
    channel: DriveChannel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val matches = typed.trim().equals(channel.displayName, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.channels_delete_title, channel.displayName)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        Res.string.channels_delete_message,
                        channel.fileCount,
                        Formatters.bytes(channel.storedBytes)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = {
                        Text(
                            stringResource(
                                Res.string.channels_delete_confirm_hint,
                                channel.displayName
                            )
                        )
                    },
                    singleLine = true,
                    isError = typed.isNotEmpty() && !matches,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                enabled = matches,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(stringResource(Res.string.common_delete_forever)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes()
            ) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelNameDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = ""
) {
    var label by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(Res.string.channels_drive_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(label.trim()) },
                shapes = ButtonDefaults.shapes(),
                enabled = label.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes()
            ) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

private const val ROW_FADE_MS = 180
private const val CONTAINER_FADE_MS = 220

