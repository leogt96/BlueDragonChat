package com.bluedragon.chat.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.bluedragon.chat.bluetooth.GattConstants.CHAT_SERVICE_UUID
import com.bluedragon.chat.bluetooth.GattConstants.MESSAGE_CHARACTERISTIC_UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Importaciones de Room
import com.bluedragon.chat.data.local.AppDatabase
import com.bluedragon.chat.data.local.ChatMessageDao
import com.bluedragon.chat.data.local.NodeInfoDao
import com.bluedragon.chat.data.local.ChatMessageEntity
import com.bluedragon.chat.data.local.NodeInfoEntity

private const val GOSSIP_INTERVAL_MS = 10000L // Chismorreo cada 10 segundos
private const val DEFAULT_MESSAGE_TTL = 3 // TTL por defecto para mensajes retransmitidos

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, READY }

// Estados del mensaje para la base de datos
object MessageStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED" // Para cuando tengamos lógica de confirmación
    const val RECEIVED = "RECEIVED"
}

@SuppressLint("MissingPermission")
class BluetoothController(
    private val context: Context,
    private val chatMessageDao: ChatMessageDao,
    private val nodeInfoDao: NodeInfoDao
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    // Un ID único para este dispositivo en la red de malla
    val deviceId: String = UUID.randomUUID().toString()
    val deviceName: String = bluetoothAdapter?.name ?: "Unknown Device"

    // El GattServerManager ahora se inicializa con nuestro deviceId y chatMessageDao
    private val gattServerManager = GattServerManager(context, deviceId, chatMessageDao)

    // Mapas para gestionar múltiples conexiones y sus características
    private val activeGattConnections = ConcurrentHashMap<BluetoothDevice, BluetoothGatt>()
    private val activeMessageCharacteristics = ConcurrentHashMap<BluetoothDevice, BluetoothGattCharacteristic>()
    
    // Mapa para rastrear los messageId que están actualmente en proceso de ser enviados a un dispositivo específico
    private val messagesInFlight = ConcurrentHashMap<BluetoothDevice, String>()

    // Scope para corrutinas internas del controlador
    private val controllerScope = CoroutineScope(Dispatchers.IO)

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<List<ConnectionState>> = combine(
        _connectionState,
        activeGattConnections.asFlow().map { it.isNotEmpty() }, // True si hay alguna conexión
        activeMessageCharacteristics.asFlow().map { it.isNotEmpty() } // True si hay alguna característica lista
    ) { mainState, hasActiveConnections, hasReadyCharacteristics ->
        when {
            hasReadyCharacteristics -> listOf(ConnectionState.READY)
            hasActiveConnections -> listOf(ConnectionState.CONNECTED)
            mainState == ConnectionState.CONNECTING -> listOf(ConnectionState.CONNECTING)
            else -> listOf(ConnectionState.DISCONNECTED)
        }
    }.stateIn(
        scope = controllerScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = listOf(ConnectionState.DISCONNECTED)
    )

    // Ahora _receivedMessages observa la base de datos
    val receivedMessages: StateFlow<List<ChatListItem>> = chatMessageDao.getChatMessages()
        .map { entities ->
            entities.map { entity ->
                ChatListItem(
                    meshMessage = MeshMessage(
                        type = MessageType.valueOf(entity.type),
                        sourceId = entity.sourceId,
                        destinationId = entity.destinationId,
                        messageId = entity.messageId,
                        ttl = DEFAULT_MESSAGE_TTL, // Usar TTL por defecto al recuperar de DB
                        payload = entity.payload,
                        timestamp = entity.timestamp
                    ),
                    isSentByMe = entity.isSentByMe
                )
            }
        }
        .stateIn(
            scope = controllerScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Ahora _knownNodes observa la base de datos
    val knownNodes: StateFlow<Map<String, NodeInfo>> = nodeInfoDao.getKnownNodes()
        .map { entities ->
            entities.associate { entity ->
                entity.nodeId to NodeInfo(
                    nodeId = entity.nodeId,
                    lastSeen = entity.lastSeen,
                    name = entity.name
                )
            }
        }
        .stateIn(
            scope = controllerScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    init {
        // Registrar esta instancia para que WorkManager pueda acceder a ella
        instance = this

        controllerScope.launch {
            // Asegurarse de que el propio dispositivo esté en la base de datos
            nodeInfoDao.insertNode(NodeInfoEntity(deviceId, System.currentTimeMillis(), deviceName))

            gattServerManager.messages.collect { (meshMessage, sendingDevice) ->
                when (meshMessage.type) {
                    MessageType.CHAT -> {
                        // Si el mensaje es para este dispositivo, además de guardarlo (GattServerManager ya lo hizo),
                        // enviamos un ACK_DELIVERED de vuelta al remitente original.
                        if (meshMessage.destinationId == null || meshMessage.destinationId == deviceId) {
                            sendAckDelivered(originalMessageId = meshMessage.messageId, recipientId = meshMessage.sourceId)
                        }
                        // Retransmitir si aplica. El guardado en DB lo hace GattServerManager.
                        if (meshMessage.ttl > 0 && meshMessage.destinationId != deviceId) {
                            sendMessage(meshMessage.copy(ttl = meshMessage.ttl - 1), excludeDevice = sendingDevice)
                        }
                    }
                    MessageType.GOSSIP -> {
                        try {
                            val nodeInfo = Json.decodeFromString<NodeInfo>(meshMessage.payload)
                            // Insertar/actualizar en la base de datos
                            nodeInfoDao.insertNode(NodeInfoEntity(
                                nodeId = nodeInfo.nodeId,
                                lastSeen = nodeInfo.lastSeen,
                                name = nodeInfo.name
                            ))
                            println("Received GOSSIP from ${nodeInfo.nodeId}. Known nodes: ${nodeInfoDao.getKnownNodes().first().size}")
                        } catch (e: Exception) {
                            println("Error decoding NodeInfo from GOSSIP message: ${e.message}")
                        }
                        // Retransmitir mensaje de chismorreo si TTL > 0
                        if (meshMessage.ttl > 0) {
                            sendMessage(meshMessage.copy(ttl = meshMessage.ttl - 1), excludeDevice = sendingDevice)
                        }
                    }
                    MessageType.ACK_DELIVERED -> {
                        val originalMessageId = meshMessage.payload // El payload del ACK es el messageId original
                        val senderOfAck = meshMessage.sourceId // Quien envió el ACK (el destinatario original)
                        println("Received ACK_DELIVERED for message ID: ${originalMessageId.take(8)} from ${senderOfAck.take(4)}")

                        // Buscar el mensaje original en la base de datos y actualizar su estado a DELIVERED
                        val chatMessage = chatMessageDao.getMessageById(originalMessageId)
                        if (chatMessage != null && chatMessage.isSentByMe && chatMessage.status != MessageStatus.DELIVERED) {
                            chatMessageDao.updateMessage(chatMessage.copy(status = MessageStatus.DELIVERED))
                            println("Message (ID: ${originalMessageId.take(8)}) status updated to DELIVERED.")
                        }
                        // Retransmitir ACK si su TTL > 0 (opcional, depende de la estrategia de ACK. Por ahora, no retransmitimos ACKs)
                        // if (meshMessage.ttl > 0) {
                        //     sendMessage(meshMessage.copy(ttl = meshMessage.ttl - 1), excludeDevice = sendingDevice)
                        // }
                    }
                }
            }
        }
    }

    // Client-side callbacks
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    activeGattConnections[device] = gatt // Almacenar la nueva conexión
                    println("Conectado a: ${device.address}")
                    gatt.discoverServices()
                    // Cuando se establece una nueva conexión, enviamos nuestra información de nodo
                    sendGossipMessage()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    activeGattConnections.remove(device)?.close() // Cerrar y remover la conexión
                    activeMessageCharacteristics.remove(device)
                    println("Desconectado de: ${device.address}")
                    messagesInFlight.remove(device) // Limpiar mensajes en vuelo para este dispositivo
                }
            } else {
                // Error en la conexión
                activeGattConnections.remove(device)?.close()
                activeMessageCharacteristics.remove(device)
                println("Error de conexión con: ${device.address}, status: $status")
                messagesInFlight.remove(device) // Limpiar mensajes en vuelo para este dispositivo

                // Opcional: Marcar mensajes PENDING para este dispositivo como FAILED si se pierde la conexión
                // Esto sería más complejo y podría requerir iterar sobre todos los PENDING y ver si eran para este deviceId.
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val device = gatt.device
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val chatService = gatt.getService(CHAT_SERVICE_UUID)
                if (chatService != null) {
                    val characteristic = chatService.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        activeMessageCharacteristics[device] = characteristic // Almacenar la característica
                        println("Característica de mensaje encontrada para: ${device.address}")
                        // Cuando la característica está lista, intentamos enviar mensajes pendientes
                        controllerScope.launch { attemptSendPendingMessages() }
                    } else {
                        println("Error: Característica de mensaje no encontrada para: ${device.address}")
                        disconnect(device) // Desconectar si no encontramos la característica clave
                    }
                } else {
                    println("Error: Servicio de Chat no encontrado para: ${device.address}")
                    disconnect(device)
                }
            } else {
                println("Error en el descubrimiento de servicios para: ${device.address}, status: $status")
                disconnect(device)
            }
        }

        override fun fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val device = gatt?.device ?: return
            val messageId = messagesInFlight.remove(device) // Remover el messageId después de intentar enviar

            controllerScope.launch {
                if (messageId != null) {
                    val currentMessage = chatMessageDao.getMessageById(messageId) // Usar el nuevo método

                    if (currentMessage != null && currentMessage.isSentByMe) {
                        val newStatus = if (status == BluetoothGatt.GATT_SUCCESS) MessageStatus.SENT else MessageStatus.PENDING // Mantener PENDING si falla
                        chatMessageDao.updateMessage(currentMessage.copy(status = newStatus))
                        println("Mensaje (ID: ${messageId.take(8)}) actualizado a $newStatus para ${device.address}.")
                    } else {
                        println("No se encontró el mensaje con ID: ${messageId.take(8)} o no fue enviado por mí para actualizar.")
                    }
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    println("Mensaje enviado con éxito a ${device.address}.")
                } else {
                    println("Error al enviar el mensaje a ${device.address}, status: $status")
                }
            }
        }
    }

    // Scanner callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != null && !_scannedDevices.value.contains(device)) {
                _scannedDevices.update { it + device }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            println("Scan failed with error code: $errorCode")
        }
    }

    // --- Public API ---

    fun startServer() {
        gattServerManager.startServer()
        sendGossipMessage() // Envía un mensaje de chismorreo inicial
        // WorkManager se encargará del chismorreo periódico
    }

    fun stopServer() {
        gattServerManager.stopServer()
        // WorkManager se encargará de parar las tareas
    }

    fun startScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            return
        }
        _scannedDevices.update { emptyList() }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CHAT_SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        // WorkManager se encargará del chismorreo periódico
    }

    fun stopScan() {
        bleScanner?.stopScan(scanCallback)
        // WorkManager se encargará de parar las tareas
    }

    fun connect(device: BluetoothDevice) {
        // Si ya estamos conectados a este dispositivo, no hacemos nada
        if (activeGattConnections.containsKey(device)) {
            println("Ya conectado a: ${device.address}")
            return
        }
        // No detenemos el escaneo, podemos seguir buscando a otros
        device.connectGatt(context, false, gattCallback)
    }

    fun disconnect(device: BluetoothDevice) {
        activeGattConnections[device]?.disconnect() // Solicitar desconexión
    }

    // Desconecta de todos los dispositivos
    fun disconnectAll() {
        activeGattConnections.keys.forEach { device ->
            disconnect(device) // Solicitar desconexión para cada uno
        }
    }

    // Envía un nuevo mensaje CHAT originado por este dispositivo
    fun sendMessage(message: String) {
        // Crear el MeshMessage, pero ahora también una ChatMessageEntity
        val chatMessage = MeshMessage(
            type = MessageType.CHAT,
            sourceId = deviceId,
            destinationId = null, // Por ahora, null para indicar broadcast simple
            messageId = generateMeshMessageId(),
            ttl = DEFAULT_MESSAGE_TTL, // Usar TTL por defecto
            payload = message,
            timestamp = System.currentTimeMillis()
        )
        
        controllerScope.launch {
            // Guardar el mensaje en la base de datos con estado PENDING
            val chatMessageEntity = ChatMessageEntity(
                messageId = chatMessage.messageId,
                sourceId = chatMessage.sourceId,
                destinationId = chatMessage.destinationId,
                payload = chatMessage.payload,
                timestamp = chatMessage.timestamp,
                isSentByMe = true,
                status = MessageStatus.PENDING,
                type = MessageType.CHAT.name
            )
            chatMessageDao.insertMessage(chatMessageEntity)

            // Intentar enviar el mensaje por Bluetooth. Si no hay conexiones, el mensaje permanece PENDING.
            // Aquí no limitamos a `if (activeMessageCharacteristics.isNotEmpty())` porque `attemptSendPendingMessages`
            // se encargará de reintentar cuando haya conexiones.
            attemptSendPendingMessages()
        }
    }

    // Envía un mensaje de chismorreo (GOSSIP) con la información de este nodo
    fun sendGossipMessage() {
        controllerScope.launch {
            // Actualiza nuestra propia entrada en la base de datos con la última marca de tiempo
            nodeInfoDao.insertNode(NodeInfoEntity(deviceId, System.currentTimeMillis(), deviceName))

            if (activeMessageCharacteristics.isEmpty()) {
                // Si no hay conexiones activas, no podemos chismorrear todavía
                return@launch
            }
            val selfNodeInfo = NodeInfo(deviceId, System.currentTimeMillis(), deviceName)
            val gossipPayload = Json.encodeToString(selfNodeInfo)

            val gossipMessage = MeshMessage(
                type = MessageType.GOSSIP,
                sourceId = deviceId,
                destinationId = null, // Gossip es un broadcast
                messageId = generateMeshMessageId(),
                ttl = DEFAULT_MESSAGE_TTL, // TTL para el chismorreo
                payload = gossipPayload,
                timestamp = System.currentTimeMillis()
            )
            sendMessage(gossipMessage, excludeDevice = null)
            println("Sent GOSSIP message about self. Known nodes: ${nodeInfoDao.getKnownNodes().first().size}")
        }
    }

    // Envía un ACK_DELIVERED para un mensaje original recibido
    private suspend fun sendAckDelivered(originalMessageId: String, recipientId: String) {
        val ackMessage = MeshMessage(
            type = MessageType.ACK_DELIVERED,
            sourceId = deviceId,
            destinationId = recipientId, // El remitente original es ahora el destinatario del ACK
            messageId = generateMeshMessageId(), // Un nuevo ID para el mensaje ACK
            ttl = DEFAULT_MESSAGE_TTL, // TTL para el ACK
            payload = originalMessageId, // El payload es el ID del mensaje original confirmado
            timestamp = System.currentTimeMillis()
        )
        sendMessage(ackMessage, excludeDevice = null)
        println("Sent ACK_DELIVERED for message ID: ${originalMessageId.take(8)} to ${recipientId.take(4)}.")
    }

    // Sobrecarga para enviar un MeshMessage existente (usado para retransmisión y chismorreo)
    private fun sendMessage(meshMessage: MeshMessage, excludeDevice: BluetoothDevice?) {
        val jsonMessage = Json.encodeToString(meshMessage)
        val messageBytes = jsonMessage.toByteArray(StandardCharsets.UTF_8)
        
        activeGattConnections.forEach { (device, gatt) ->
            // No retransmitir al dispositivo que nos envió el mensaje originalmente
            if (device != excludeDevice) {
                val characteristic = activeMessageCharacteristics[device]
                if (characteristic != null) {
                    characteristic.value = messageBytes
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    // Antes de escribir, rastreamos el messageId para este dispositivo
                    messagesInFlight[device] = meshMessage.messageId
                    gatt.writeCharacteristic(characteristic)
                }
            }
        }
    }

    // Intenta enviar todos los mensajes pendientes en la base de datos a los dispositivos conectados.
    // Si targetDeviceId no es nulo, solo intentará enviar mensajes destinados a ese ID (no implementado aún para destino específico).
    // Esta función ahora es pública para que el Worker pueda llamarla.
    public suspend fun attemptSendPendingMessages() {
        val pendingMessages = chatMessageDao.getPendingMessages(MessageStatus.PENDING)
        if (pendingMessages.isNotEmpty()) {
            println("Intentando enviar ${pendingMessages.size} mensajes pendientes...")
            pendingMessages.forEach { entity ->
                // Convertir ChatMessageEntity a MeshMessage para el envío
                val meshMessage = MeshMessage(
                    type = MessageType.valueOf(entity.type),
                    sourceId = entity.sourceId,
                    destinationId = entity.destinationId,
                    messageId = entity.messageId,
                    ttl = DEFAULT_MESSAGE_TTL, // Asignar un TTL al reintentar
                    payload = entity.payload,
                    timestamp = entity.timestamp
                )
                // Enviar el mensaje a todos los dispositivos conectados (flooding)
                sendMessage(meshMessage, excludeDevice = null)
            }
        }
    }

    // Las funciones de gossip periódico se eliminarán, WorkManager se encargará.
    // private fun startGossipPeriodically() { ... }
    // private fun stopGossipPeriodically() { ... }

    companion object {
        @Volatile
        private var instance: BluetoothController? = null

        fun getInstance(context: Context, chatMessageDao: ChatMessageDao, nodeInfoDao: NodeInfoDao): BluetoothController {
            return instance ?: synchronized(this) {
                instance ?: BluetoothController(context, chatMessageDao, nodeInfoDao).also { instance = it }
            }
        }

        fun getCurrentInstance(): BluetoothController? = instance

        fun clearInstance() {
            instance = null
        }
    }
}
