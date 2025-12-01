package com.bluedragon.chat.bluetooth

/**
 * Representa un elemento individual en el historial del chat para la interfaz de usuario.
 * Encapsula un MeshMessage y añade información contextual para la visualización.
 */
data class ChatListItem(
    val meshMessage: MeshMessage,
    val isSentByMe: Boolean,
    // Podemos añadir más campos aquí en el futuro, como estado de entrega, etc.
)
