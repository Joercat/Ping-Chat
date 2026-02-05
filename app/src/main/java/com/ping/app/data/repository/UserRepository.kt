// data/repository/UserRepository.kt
package com.ping.app.data.repository

import com.ping.app.data.database.dao.UserDao
import com.ping.app.data.database.entities.UserEntity
import com.ping.app.data.models.PresenceStatus
import com.ping.app.data.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao
) {
    
    fun getUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    suspend fun getUserById(userId: String): User? {
        return userDao.getUserById(userId)?.toModel()
    }
    
    fun observeUser(userId: String): Flow<User?> {
        return userDao.observeUser(userId).map { it?.toModel() }
    }
    
    suspend fun getUserByUsername(username: String): User? {
        return userDao.getUserByUsername(username)?.toModel()
    }
    
    fun getOnlineUsers(): Flow<List<User>> {
        return userDao.getOnlineUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    fun getFavoriteUsers(): Flow<List<User>> {
        return userDao.getFavoriteUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    suspend fun insertUser(user: User) {
        userDao.insertUser(user.toEntity())
    }
    
    suspend fun updateUser(user: User) {
        userDao.updateUser(user.toEntity())
    }
    
    suspend fun updatePresence(userId: String, status: PresenceStatus, lastSeen: Long?) {
        userDao.updatePresence(userId, status.name, lastSeen)
    }
    
    suspend fun updateConnectionInfo(userId: String, ipAddress: String?, port: Int?) {
        userDao.updateConnectionInfo(userId, ipAddress, port)
    }
    
    suspend fun deleteUser(user: User) {
        userDao.deleteUser(user.toEntity())
    }
    
    private fun UserEntity.toModel(): User {
        return User(
            id = id,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            publicKey = publicKey,
            deviceId = deviceId,
            ipAddress = ipAddress,
            port = port,
            status = status,
            presence = PresenceStatus.valueOf(presence),
            lastSeen = lastSeen,
            isBlocked = isBlocked,
            isFavorite = isFavorite,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
    
    private fun User.toEntity(): UserEntity {
        return UserEntity(
            id = id,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            publicKey = publicKey,
            deviceId = deviceId,
            ipAddress = ipAddress,
            port = port,
            status = status,
            presence = presence.name,
            lastSeen = lastSeen,
            isBlocked = isBlocked,
            isFavorite = isFavorite,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
