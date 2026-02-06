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
    
    /**
     * Gets all users as a Flow
     */
    fun getUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    /**
     * Gets a user by ID
     */
    suspend fun getUserById(userId: String): User? {
        return userDao.getUserById(userId)?.toModel()
    }
    
    /**
     * Observes a user by ID
     */
    fun observeUser(userId: String): Flow<User?> {
        return userDao.observeUser(userId).map { it?.toModel() }
    }
    
    /**
     * Gets a user by username
     */
    suspend fun getUserByUsername(username: String): User? {
        return userDao.getUserByUsername(username)?.toModel()
    }
    
    /**
     * Gets all online users
     */
    fun getOnlineUsers(): Flow<List<User>> {
        return userDao.getOnlineUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    /**
     * Gets all favorite users
     */
    fun getFavoriteUsers(): Flow<List<User>> {
        return userDao.getFavoriteUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    /**
     * Gets all blocked users
     */
    fun getBlockedUsers(): Flow<List<User>> {
        return userDao.getBlockedUsers().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    /**
     * Inserts a new user
     */
    suspend fun insertUser(user: User) {
        userDao.insertUser(user.toEntity())
    }
    
    /**
     * Inserts multiple users
     */
    suspend fun insertUsers(users: List<User>) {
        userDao.insertUsers(users.map { it.toEntity() })
    }
    
    /**
     * Updates a user
     */
    suspend fun updateUser(user: User) {
        userDao.updateUser(user.toEntity())
    }
    
    /**
     * Updates user presence
     */
    suspend fun updatePresence(userId: String, status: PresenceStatus, lastSeen: Long?) {
        userDao.updatePresence(userId, status.name, lastSeen)
    }
    
    /**
     * Updates user connection info
     */
    suspend fun updateConnectionInfo(userId: String, ipAddress: String?, port: Int?) {
        userDao.updateConnectionInfo(userId, ipAddress, port)
    }
    
    /**
     * Blocks a user
     */
    suspend fun blockUser(userId: String) {
        val user = userDao.getUserById(userId)
        user?.let {
            userDao.updateUser(it.copy(isBlocked = true))
        }
    }
    
    /**
     * Unblocks a user
     */
    suspend fun unblockUser(userId: String) {
        val user = userDao.getUserById(userId)
        user?.let {
            userDao.updateUser(it.copy(isBlocked = false))
        }
    }
    
    /**
     * Toggles favorite status
     */
    suspend fun toggleFavorite(userId: String) {
        val user = userDao.getUserById(userId)
        user?.let {
            userDao.updateUser(it.copy(isFavorite = !it.isFavorite))
        }
    }
    
    /**
     * Deletes a user
     */
    suspend fun deleteUser(user: User) {
        userDao.deleteUser(user.toEntity())
    }
    
    /**
     * Searches users by display name or username
     */
    suspend fun searchUsers(query: String): List<User> {
        return userDao.searchUsers("%$query%").map { it.toModel() }
    }
    
    /**
     * Converts UserEntity to User model
     */
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
            presence = try {
                PresenceStatus.valueOf(presence)
            } catch (e: Exception) {
                PresenceStatus.OFFLINE
            },
            lastSeen = lastSeen,
            isBlocked = isBlocked,
            isFavorite = isFavorite,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
    
    /**
     * Converts User model to UserEntity
     */
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
