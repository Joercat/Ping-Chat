// data/database/dao/UserDao.kt
package com.ping.app.data.database.dao

import androidx.room.*
import com.ping.app.data.database.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    
    /**
     * Gets all users ordered by display name
     */
    @Query("SELECT * FROM users ORDER BY displayName ASC")
    fun getAllUsers(): Flow<List<UserEntity>>
    
    /**
     * Gets a user by ID
     */
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): UserEntity?
    
    /**
     * Observes a user by ID
     */
    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeUser(userId: String): Flow<UserEntity?>
    
    /**
     * Gets a user by username
     */
    @Query("SELECT * FROM users WHERE username = :username COLLATE NOCASE")
    suspend fun getUserByUsername(username: String): UserEntity?
    
    /**
     * Gets all favorite users
     */
    @Query("SELECT * FROM users WHERE isFavorite = 1 ORDER BY displayName ASC")
    fun getFavoriteUsers(): Flow<List<UserEntity>>
    
    /**
     * Gets all online users (ONLINE or IN_APP status)
     */
    @Query("SELECT * FROM users WHERE presence = 'ONLINE' OR presence = 'IN_APP' ORDER BY displayName ASC")
    fun getOnlineUsers(): Flow<List<UserEntity>>
    
    /**
     * Gets all blocked users
     */
    @Query("SELECT * FROM users WHERE isBlocked = 1 ORDER BY displayName ASC")
    fun getBlockedUsers(): Flow<List<UserEntity>>
    
    /**
     * Searches users by display name or username
     */
    @Query("SELECT * FROM users WHERE displayName LIKE :query OR username LIKE :query ORDER BY displayName ASC")
    suspend fun searchUsers(query: String): List<UserEntity>
    
    /**
     * Inserts a user (replaces on conflict)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)
    
    /**
     * Inserts multiple users (replaces on conflict)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)
    
    /**
     * Updates a user
     */
    @Update
    suspend fun updateUser(user: UserEntity)
    
    /**
     * Updates user presence and last seen
     */
    @Query("UPDATE users SET presence = :presence, lastSeen = :lastSeen, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updatePresence(
        userId: String, 
        presence: String, 
        lastSeen: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Updates user connection info (IP and port)
     */
    @Query("UPDATE users SET ipAddress = :ipAddress, port = :port, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateConnectionInfo(
        userId: String, 
        ipAddress: String?, 
        port: Int?,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Updates user blocked status
     */
    @Query("UPDATE users SET isBlocked = :isBlocked, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateBlockedStatus(
        userId: String, 
        isBlocked: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Updates user favorite status
     */
    @Query("UPDATE users SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateFavoriteStatus(
        userId: String, 
        isFavorite: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Updates user avatar
     */
    @Query("UPDATE users SET avatarUrl = :avatarUrl, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateAvatar(
        userId: String, 
        avatarUrl: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Updates user status message
     */
    @Query("UPDATE users SET status = :status, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateStatus(
        userId: String, 
        status: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Deletes a user
     */
    @Delete
    suspend fun deleteUser(user: UserEntity)
    
    /**
     * Deletes a user by ID
     */
    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteUserById(userId: String)
    
    /**
     * Gets the count of all users
     */
    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int
    
    /**
     * Gets the count of online users
     */
    @Query("SELECT COUNT(*) FROM users WHERE presence = 'ONLINE' OR presence = 'IN_APP'")
    suspend fun getOnlineUserCount(): Int
    
    /**
     * Gets the count of blocked users
     */
    @Query("SELECT COUNT(*) FROM users WHERE isBlocked = 1")
    suspend fun getBlockedUserCount(): Int
    
    /**
     * Clears all users
     */
    @Query("DELETE FROM users")
    suspend fun clearAllUsers()
}
