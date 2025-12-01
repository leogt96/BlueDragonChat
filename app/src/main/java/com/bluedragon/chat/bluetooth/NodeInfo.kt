package com.bluedragon.chat.bluetooth

import kotlinx.serialization.Serializable

/**
 * Representa la información básica de un nodo en la red de malla.
 * Esta información será chismorreada entre los nodos.
 */
@Serializable
data class NodeInfo(
    val nodeId: String,           // ID único del nodo
    val lastSeen: Long,           // Última vez que se vio este nodo (timestamp)
    val name: String? = null      // Nombre amigable del dispositivo (opcional)
)
