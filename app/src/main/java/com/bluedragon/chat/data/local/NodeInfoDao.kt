package com.bluedragon.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NodeInfoEntity)

    @Update
    suspend fun updateNode(node: NodeInfoEntity)

    @Query("SELECT * FROM known_nodes ORDER BY lastSeen DESC")
    fun getKnownNodes(): Flow<List<NodeInfoEntity>>

    @Query("SELECT * FROM known_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: String): NodeInfoEntity?

    @Query("DELETE FROM known_nodes WHERE nodeId = :nodeId")
    suspend fun deleteNode(nodeId: String)
}
