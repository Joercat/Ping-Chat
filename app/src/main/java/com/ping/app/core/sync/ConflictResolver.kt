// core/sync/ConflictResolver.kt
package com.ping.app.core.sync

import com.ping.app.data.models.Message
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConflictResolver @Inject constructor() {
    
    fun resolveMessageConflict(local: Message, remote: Message): Message {
        // Last write wins, but prefer non-deleted messages
        return when {
            local.isDeleted && !remote.isDeleted -> remote
            !local.isDeleted && remote.isDeleted -> local
            local.editedAt != null && remote.editedAt != null -> {
                if ((local.editedAt ?: 0) > (remote.editedAt ?: 0)) local else remote
            }
            local.createdAt > remote.createdAt -> local
            else -> remote
        }
    }
    
    fun mergeReactions(local: Message, remote: Message): Message {
        val mergedReactions = (local.reactions + remote.reactions)
            .distinctBy { "${it.userId}_${it.emoji}" }
        return local.copy(reactions = mergedReactions)
    }
}
