package com.bluedragon.chat.bluetooth

import java.util.UUID

object GattConstants {
    /**
     * UUID único para nuestro servicio de Chat.
     * Es como la "frecuencia de radio" que nuestra app buscará.
     */
    val CHAT_SERVICE_UUID: UUID = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")

    /**
     * UUID único para la Característica que transportará nuestros mensajes.
     * Es el "canal" específico dentro de nuestra frecuencia donde se envían los datos.
     */
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
}
