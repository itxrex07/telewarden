package com.drdisagree.teledrive.core.telegram

import com.drdisagree.teledrive.domain.model.TelegramChatType

data class TelegramChatSummary(
    val chatId: Long,
    val title: String,
    val chatType: TelegramChatType,
    val subtitle: String = "",
    val photoPath: String? = null
)

data class RemoteForumTopic(
    val topicId: Long,
    val name: String,
    val createdAtSeconds: Int,
    val isGeneral: Boolean = false
)
