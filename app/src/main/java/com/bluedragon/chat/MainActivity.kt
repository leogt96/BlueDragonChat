package com.bluedragon.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.* 
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluedragon.chat.bluetooth.ChatListItem
import com.bluedragon.chat.bluetooth.ConnectionState
import com.bluedragon.chat.ui.theme.BlueDragonChatTheme
import com.bluedragon.chat.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlueDragonChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }
}

@Composable
fun MainNavigation(mainViewModel: MainViewModel = viewModel()) {
    // connectionState ahora es una lista, usamos .firstOrNull() para obtener el estado principal para la navegación
    val connectionState by mainViewModel.connectionState.collectAsState()
    val currentConnectionState = connectionState.firstOrNull()

    if (currentConnectionState == ConnectionState.READY) {
        ChatScreen(mainViewModel)
    } else {
        DeviceListScreen(mainViewModel)
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceListScreen(mainViewModel: MainViewModel) {
    val scannedDevices by mainViewModel.scannedDevices.collectAsState()
    val knownNodes by mainViewModel.knownNodes.collectAsState()
    val connectionState by mainViewModel.connectionState.collectAsState()
    val currentConnectionState = connectionState.firstOrNull()

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        remember { listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) }
    } else {
        remember { listOf(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        if (permissionsMap.values.all { it }) {
            // Permisos concedidos. El ViewModel ya intentó iniciar el escaneo/servidor. Si falló por permisos,
            // se podría considerar un reintento aquí, pero por simplicidad, confiamos en que ya lo está haciendo.
        }
    }

    LaunchedEffect(Unit) {
        // Lanza la solicitud de permisos al inicio de la pantalla.
        permissionLauncher.launch(permissions.toTypedArray())
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Dispositivos BlueDragon", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // Mostrar el estado de conexión actual
        Text("Estado de conexión: ${currentConnectionState ?: ConnectionState.DISCONNECTED}",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))

        // Lista de dispositivos escaneados
        Text("Dispositivos Escaneados", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(scannedDevices) { device ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { mainViewModel.connectToDevice(device) }
                    .padding(8.dp)) {
                    Text(device.name ?: "Dispositivo sin nombre")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Lista de nodos conocidos
        Text("Nodos Conocidos (Gossip)", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (knownNodes.isEmpty()) {
                item { Text("No hay nodos conocidos aún.") }
            } else {
                items(knownNodes.values.toList().sortedBy { it.lastSeen }.reversed()) { nodeInfo ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("ID: ${nodeInfo.nodeId.take(8)}...")
                        Text("Nombre: ${nodeInfo.name ?: "N/A"}")
                        Text("Visto por última vez: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(nodeInfo.lastSeen))}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Divider()
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Buscando dispositivos y esperando conexiones...",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun ChatScreen(mainViewModel: MainViewModel) {
    var message by remember { mutableStateOf("") }
    val messages by mainViewModel.receivedMessages.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { chatItem -> 
                // Aquí mostramos el contenido del mensaje de forma más enriquecida
                val sender = if (chatItem.isSentByMe) {
                    "Tú" // Si lo enviamos nosotros
                } else if (chatItem.meshMessage.destinationId == mainViewModel.bluetoothController.deviceId) {
                    "${chatItem.meshMessage.sourceId.take(4)} (Directo)"
                } else {
                    "${chatItem.meshMessage.sourceId.take(4)} (Vía Malla)"
                }
                Text(
                    text = "$sender: ${chatItem.meshMessage.payload}",
                    modifier = Modifier.padding(vertical = 4.dp)
                ) 
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Mensaje") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (message.isNotBlank()) {
                    mainViewModel.sendMessage(message)
                    message = ""
                }
            }) {
                Text("Enviar")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = { mainViewModel.disconnect() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Desconectar")
        }
    }
}
