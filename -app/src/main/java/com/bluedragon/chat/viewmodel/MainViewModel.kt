
package com.bluedragon.chat.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluedragon.chat.bluetooth.BluetoothController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    
    private val bluetoothController = BluetoothController(app)

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    init {
        // Observamos el flow del controller para actualizar nuestra propia lista
        viewModelScope.launch {
            bluetoothController.scannedDevices.collect { devices ->
                _scannedDevices.value = devices
            }
        }
    }

    fun startScan() {
        bluetoothController.startScan()
    }

    fun stopScan() {
        bluetoothController.stopScan()
    }

    fun connectToDevice(device: BluetoothDevice) {
        // TODO: Llamar a la función de conexión real en bluetoothController
    }
}
