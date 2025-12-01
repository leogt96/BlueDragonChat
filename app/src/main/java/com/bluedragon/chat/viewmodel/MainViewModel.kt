package com.bluedragon.chat.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bluedragon.chat.bluetooth.BluetoothController
import com.bluedragon.chat.bluetooth.ChatListItem
import com.bluedragon.chat.bluetooth.ConnectionState
import com.bluedragon.chat.bluetooth.NodeInfo
import com.bluedragon.chat.data.local.AppDatabase
import com.bluedragon.chat.workers.PendingMessageWorker
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // Inicializar la base de datos y los DAOs
    private val database = AppDatabase.getDatabase(app)
    private val chatMessageDao = database.chatMessageDao()
    private val nodeInfoDao = database.nodeInfoDao()
    
    // Inicializar BluetoothController. Su instancia se registrará automáticamente en el companion object.
    private val bluetoothController = BluetoothController(app, chatMessageDao, nodeInfoDao)

    val scannedDevices: StateFlow<List<BluetoothDevice>> = bluetoothController.scannedDevices
    val connectionState: StateFlow<List<ConnectionState>> = bluetoothController.connectionState
    val receivedMessages: StateFlow<List<ChatListItem>> = bluetoothController.receivedMessages
    val knownNodes: StateFlow<Map<String, NodeInfo>> = bluetoothController.knownNodes

    private val workManager = WorkManager.getInstance(app)

    init {
        bluetoothController.startServer()
        bluetoothController.startScan()
        schedulePeriodicWork()
    }

    override fun onCleared() {
        super.onCleared()
        // Limpiar la instancia de BluetoothController cuando el ViewModel se limpia
        BluetoothController.clearInstance()
        // Cancelar el trabajo periódico cuando el ViewModel se destruye (opcional, dependiendo del comportamiento deseado)
        cancelPeriodicWork()
    }

    fun startScan() {
        bluetoothController.startScan()
        schedulePeriodicWork()
    }

    fun stopScan() {
        bluetoothController.stopScan()
        cancelPeriodicWork()
    }

    fun startServer() {
        bluetoothController.startServer()
        schedulePeriodicWork()
    }
    
    fun stopServer() {
        bluetoothController.stopServer()
        cancelPeriodicWork()
    }

    fun connectToDevice(device: BluetoothDevice) {
        bluetoothController.connect(device)
    }

    fun disconnect() {
        bluetoothController.disconnectAll()
        cancelPeriodicWork()
    }
    
    fun sendMessage(message: String) {
        bluetoothController.sendMessage(message)
    }

    private fun schedulePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Requerir alguna conexión de red (incluido Bluetooth)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<PendingMessageWorker>(
            repeatInterval = 15, // Repetir cada 15 minutos (mínimo recomendado por WorkManager)
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PendingMessageWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // Si ya existe, actualiza el trabajo
            periodicWorkRequest
        )
        println("WorkManager: Tarea periódica '${PendingMessageWorker.WORK_NAME}' encolada.")
    }

    private fun cancelPeriodicWork() {
        workManager.cancelUniqueWork(PendingMessageWorker.WORK_NAME)
        println("WorkManager: Tarea periódica '${PendingMessageWorker.WORK_NAME}' cancelada.")
    }
}
