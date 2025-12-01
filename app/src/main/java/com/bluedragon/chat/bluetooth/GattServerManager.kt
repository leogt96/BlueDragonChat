package com.bluedragon.chat.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.bluedragon.chat.bluetooth.GattConstants.CHAT_SERVICE_UUID
import com.bluedragon.chat.bluetooth.GattConstants.MESSAGE_CHARACTERISTIC_UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

// Importaciones de Room
import com.bluedragon.chat.data.local.ChatMessageDao
import com.bluedragon.chat.data.local.ChatMessageEntity

@SuppressLint("MissingPermission")
class GattServerManager(
    private val context: Context,
    private val deviceId: String,
    private val chatMessageDao: ChatMessageDao // Inyectar ChatMessageDao
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val advertiser: BluetoothLeAdvertiser? = bluetoothManager.adapter.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null

    // Scope para corrutinas internas del servidor GATT
    private val serverScope = CoroutineScope(Dispatchers.IO)

    // Ahora emitimos un par de MeshMessage y el BluetoothDevice que lo envió
    private val _messages = MutableSharedFlow<Pair<MeshMessage, BluetoothDevice?>>()
    val messages: SharedFlow<Pair<MeshMessage, BluetoothDevice?>> = _messages

    // Caché de IDs de mensajes vistos para evitar retransmisiones en bucle
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            println("Advertising iniciado con éxito.")
        }

        override fun onStartFailure(errorCode: Int) {
            println("Error al iniciar advertising, código: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status == 0) {
                println("Servicio de Chat añadido con éxito.")
                startAdvertising()
            } else {
                println("Error al añadir el servicio de chat, status: $status")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic?.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                val jsonMessage = value?.toString(StandardCharsets.UTF_8) ?: ""
                try {
                    val receivedMeshMessage = Json.decodeFromString(MeshMessage.serializer(), jsonMessage)

                    // --- LÓGICA DE RETRANSMISIÓN Y CACHÉ ---
                    if (seenMessageIds.contains(receivedMeshMessage.messageId)) {
                        // Mensaje ya visto, ignorar para evitar bucles
                        println("Mensaje (ID: ${receivedMeshMessage.messageId.take(8)}) ya visto. Ignorando.")
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                        return
                    }
                    seenMessageIds.add(receivedMeshMessage.messageId) // Añadir a la caché de vistos

                    println("MeshMessage recibido de ${receivedMeshMessage.sourceId.take(4)} con TTL: ${receivedMeshMessage.ttl}, Tipo: ${receivedMeshMessage.type}, Payload: ${receivedMeshMessage.payload.take(20)}")

                    serverScope.launch {
                        // Guardar el mensaje recibido en la base de datos
                        val chatMessageEntity = ChatMessageEntity(
                            messageId = receivedMeshMessage.messageId,
                            sourceId = receivedMeshMessage.sourceId,
                            destinationId = receivedMeshMessage.destinationId,
                            payload = receivedMeshMessage.payload,
                            timestamp = receivedMeshMessage.timestamp,
                            isSentByMe = false, // Este mensaje fue recibido, no enviado por este dispositivo
                            status = MessageStatus.RECEIVED,
                            type = receivedMeshMessage.type.name
                        )
                        chatMessageDao.insertMessage(chatMessageEntity)
                    }

                    // Emitir para que BluetoothController lo procese (retransmita o muestre en UI)
                    _messages.tryEmit(receivedMeshMessage to device)

                    // --- FIN LÓGICA DE RETRANSMISIÓN Y CACHÉ ---

                } catch (e: Exception) {
                    println("Error al deserializar MeshMessage: ${e.message}")
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    fun startServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupGattService()
    }

    fun stopServer() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        serverScope.cancel() // Cancelar el scope cuando el servidor se detiene
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(CHAT_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }

    private fun setupGattService() {
        val service = BluetoothGattService(CHAT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(messageCharacteristic)
        gattServer?.addService(service)
    }
}
