package com.bluedragon.chat.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bluedragon.chat.bluetooth.MessageType

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val messageId: String,          // ID único del mensaje (de MeshMessage)
    val sourceId: String,           // ID del dispositivo que originó el mensaje
    val destinationId: String?,     // ID del dispositivo final (null si es broadcast)
    val payload: String,            // Contenido del mensaje (texto)
    val timestamp: Long,            // Marca de tiempo de creación
    val isSentByMe: Boolean,        // Indica si este dispositivo envió el mensaje
    val status: String,             // Estado del mensaje (ej: "SENT", "DELIVERED", "PENDING")
    val type: String                // Tipo de mensaje (CHAT, GOSSIP) - almacenamos como String
)
