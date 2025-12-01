package com.bluedragon.chat.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bluedragon.chat.bluetooth.BluetoothController
import com.bluedragon.chat.data.local.AppDatabase

/**
 * Worker que se encarga de intentar enviar mensajes pendientes
 * y de enviar periódicamente mensajes de chismorreo (gossip).
 * 
 * Este worker intenta obtener una instancia activa de BluetoothController.
 * Si el BluetoothController está activo (la app está en primer plano o en segundo plano reciente
 * y el Controller no ha sido limpiado), utilizará sus métodos para manejar la lógica BLE.
 * Si el BluetoothController no está activo, el worker podría no hacer nada con respecto a BLE,
 * pero aún así puede interactuar con la base de datos (por ejemplo, para logs o futuras mejoras
 * que no requieran una conexión activa en ese instante).
 */
class PendingMessageWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val bluetoothController = BluetoothController.getCurrentInstance()

        if (bluetoothController == null) {
            println("PendingMessageWorker: BluetoothController no está activo. No se pueden enviar mensajes ni chismorreo en este momento.")
            // Podríamos intentar recrear el controlador aquí si quisiéramos una operación BLE completamente autónoma
            // desde el worker, pero eso es más complejo y lo haremos de esta forma para un MVP.
            return Result.retry() // Reintentar más tarde cuando la app esté más activa
        }

        println("PendingMessageWorker: Ejecutando tareas en segundo plano...")

        // 1. Intentar enviar mensajes pendientes
        bluetoothController.attemptSendPendingMessages()

        // 2. Enviar mensaje de chismorreo
        bluetoothController.sendGossipMessage()

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "PendingMessageWorker"
    }
}
