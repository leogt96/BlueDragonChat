package com.bluedragon.chat.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_nodes")
data class NodeInfoEntity(
    @PrimaryKey
    val nodeId: String,           // ID único del nodo
    val lastSeen: Long,           // Última vez que se vio este nodo (timestamp)
    val name: String? = null      // Nombre amigable del dispositivo (opcional)
)
