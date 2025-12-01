package com.bluedragon.chat.bluetooth

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Define los tipos de mensajes que pueden circular por la red de malla.
 */
@Serializable
enum class MessageType {
    CHAT,        // Mensajes de chat de usuario
    GOSSIP,      // Mensajes de chismorreo (información de nodos)
    ACK_DELIVERED // Confirmación de que un mensaje fue entregado al destinatario final
}

/**
 * Representa un mensaje dentro de la red de malla Bluetooth.
 * Contiene metadatos para el enrutamiento y el contenido del mensaje.
 */
@Serializable
data class MeshMessage(
    val type: MessageType,         // Tipo de mensaje (CHAT, GOSSIP, ACK_DELIVERED)
    val sourceId: String,          // ID del dispositivo que originó el mensaje
    val destinationId: String?,    // ID del dispositivo final al que va dirigido (null si es broadcast)
    val messageId: String,         // ID único para esta instancia del mensaje (para evitar loops)
    val ttl: Int,                  // Time-To-Live: número de saltos restantes
    val payload: String,           // Contenido real del mensaje (JSON de NodeInfo para GOSSIP, texto para CHAT, messageId de ACK para ACK_DELIVERED)
    val timestamp: Long            // Marca de tiempo de creación del mensaje (en milisegundos)
)

// Extension function para crear un ID único de mensaje
fun generateMeshMessageId(): String = UUID.randomUUID().toString()
