// data/database/dao/UserDao.kt
package com.ping.app.data.database.dao

import androidx.room.*
import com.ping.app.data.database.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    
    @Query("SELECT * FROM users ORDER BY displayName ASC")
    fun getAllUsers(): Flow<List<UserEntity>>
    
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): UserEntity?
    
    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeUser(userId: String): Flow<UserEntity?>
    
    @Query("SELECT * FROM users WHERE username = :username")
    suspend fun getUserByUsername(username: String): UserEntity?
    
    @Query("SELECT * FROM users WHERE isFavorite = 1")
    fun getFavoriteUsers(): Flow<List<UserEntity>>
    
    @Query("SELECT * FROM users WHERE presence = 'ONLINE' OR presence = 'IN_APP'")
    fun getOnlineUsers(): Flow<List<UserEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)
    
    @Update
    suspend fun updateUser(user: UserEntity)
    
    @Query("UPDATE users SET presence = :presence, lastSeen = :lastSeen WHERE id = :userId")
    suspend fun updatePresence(userId: String, presence: String, lastSeen: Long?)
    
    @Query("UPDATE users SET ipAddress = :ipAddress, port = :port WHERE id = :userId")
    suspend fun updateConnectionInfo(userId: String, ipAddress: String?, port: Int?)
    
    @Delete
    suspend fun deleteUser(user: UserEntity)
}
