// data/database/entities/UserEntity.kt
package com.ping.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val publicKey: String,
    val deviceId: String,
    val ipAddress: String?,
    val port: Int?,
    val status: String?,
    val presence: String,
    val lastSeen: Long?,
    val isBlocked: Boolean,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
