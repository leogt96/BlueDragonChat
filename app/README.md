# BlueDragon Chat 🐉

**Un prototipo de mensajería descentralizada y offline-first construida con Android Nativo y Bluetooth Low Energy.**

---

## 🎯 Objetivo del Proyecto

BlueDragon Chat es una aplicación de mensajería para Android diseñada para funcionar **sin necesidad de Internet o servidores centrales**. La comunicación se establece directamente entre dispositivos (Peer-to-Peer) utilizando Bluetooth Low Energy (BLE).

El objetivo principal de este proyecto es explorar y construir una red de malla (Mesh Network) simple, donde los mensajes pueden "saltar" de un dispositivo a otro para alcanzar a destinatarios que no están directamente al alcance. Es una prueba de concepto que demuestra cómo se puede mantener la comunicación en escenarios donde la conectividad tradicional no está disponible.

---

## ✨ Funcionalidades Implementadas

*   **Comunicación Peer-to-Peer con BLE:** Los dispositivos pueden descubrirse, conectarse e intercambiar mensajes directamente.
*   **Red de Malla Simple (Flooding):** Un mensaje enviado a la red es retransmitido por los nodos vecinos, permitiendo que la comunicación vaya más allá del alcance directo de un solo dispositivo.
*   **Protocolo de Chismorreo (Gossip):** Los dispositivos comparten activamente información sobre otros nodos que conocen, permitiendo que cada participante construya un "mapa" de la red.
*   **Persistencia Local con Room:** El historial de chat y la lista de nodos conocidos se guardan en una base de datos local, por lo que la información no se pierde si se cierra la aplicación.
*   **Lógica "Store-and-Forward":** Si envías un mensaje y no hay nadie conectado, la aplicación lo guarda. Tan pronto como se establece una nueva conexión, la app intenta enviar todos los mensajes pendientes.
*   **Confirmación de Entrega (ACK):** El dispositivo receptor envía un "acuse de recibo" al remitente, permitiendo que el estado del mensaje se actualice de `SENT` a `DELIVERED`.
*   **Fiabilidad en Segundo Plano con WorkManager:** Las tareas periódicas, como el reintento de envío de mensajes pendientes y el chismorreo, se gestionan de forma fiable para que la red siga funcionando incluso si la app no está en primer plano.
*   **Stack Tecnológico Moderno:** Construido 100% en Kotlin, con Jetpack Compose para la UI, Coroutines y Flow para la asincronía, y una arquitectura MVVM.

---

## 🤔 ¿Cómo Funciona? La Lógica Explicada

Imagina que estás en un lugar sin señal. Así es como BlueDragon Chat te permitiría comunicarte:

#### 1. Descubrimiento y Chismorreo
Cuando abres la app, tu teléfono empieza a **anunciarse** y **escanear** dispositivos cercanos. Al conectarte con un amigo, no solo abres un canal de chat, sino que también intercambian información. Gracias a un **protocolo de chismorreo (Gossip)** periódico, tu teléfono le "chismorrea" a tu amigo sobre todos los demás nodos que conoce, y viceversa. Así, cada dispositivo construye un mapa de la red.

#### 2. Envío de un Mensaje con "Store-and-Forward"
Cuando envías un mensaje:
1.  La app crea un `MeshMessage` (una "carta digital" con un ID único, un TTL o "tiempo de vida", y tu texto).
2.  **Inmediatamente lo guarda** en la base de datos local (Room) con el estado `PENDING`.
3.  Intenta enviarlo a los amigos conectados. Si la conexión Bluetooth confirma el envío, el estado cambia a `SENT`.
4.  Si el destinatario final recibe la "carta", envía una confirmación (`ACK_DELIVERED`). Cuando esta confirmación te llega, el estado del mensaje original cambia a `DELIVERED`.

#### 3. La Magia de la Red de Malla y WorkManager

- **El Salto de Mensajes (Flooding):** Si envías un mensaje y tu amigo está lejos, pero hay un amigo en común en el medio, el mensaje "saltará" a través del dispositivo intermediario para llegar a su destino.
- **¿Qué pasa si cierras la app?** Aquí es donde brilla **WorkManager**. Cada 15 minutos, una tarea en segundo plano se despierta y le ordena a la app:
  1.  Revisar la base de datos en busca de mensajes `PENDING`. Si encuentra alguno, intenta reenviarlo.
  2.  Enviar un mensaje de "chismorreo" para mantener la red actualizada.
  
Esto asegura que la red siga viva y que los mensajes se entreguen de forma fiable, convirtiendo a la app en una verdadera herramienta de comunicación offline.

---

## 🏗️ Estructura del Proyecto y Arquitectura

El proyecto sigue el patrón **MVVM (Model-View-ViewModel)**.

```
app/
└── src/main/java/com/bluedragon/chat/
    ├── MainActivity.kt         # (Vista) Punto de entrada de la UI con Jetpack Compose.
    |
    ├── viewmodel/
    │   └── MainViewModel.kt    # (ViewModel) Conecta la UI con la lógica de negocio.
    |
    ├── bluetooth/
    │   ├── BluetoothController.kt  # (Modelo) ¡El cerebro de la app! Orquesta todo el BLE.
    │   ├── GattServerManager.kt  # (Modelo) Gestiona el rol de servidor BLE (escucha).
    │   ├── MeshMessage.kt      # Define la estructura de los mensajes.
    │   └── NodeInfo.kt         # Define la estructura del "chismorreo".
    |
    ├── data/local/
    │   ├── AppDatabase.kt          # Define la base de datos Room.
    │   ├── ...Entities.kt          # Define las tablas de la base de datos.
    │   └── ...Daos.kt              # Interfaces para acceder a las tablas.
    |
    └── workers/
        └── PendingMessageWorker.kt # Tarea en segundo plano para reintentos y chismorreo.
```

---

## 🎙️ Guía: Desafíos Técnicos y Decisiones

Esta sección profundiza en el *porqué* detrás de las decisiones clave del proyecto.

#### P: ¿Por qué elegiste Bluetooth Low Energy (BLE) en lugar de Wi-Fi Direct?
**R:** La elección se basó en el caso de uso: una red de malla que necesita estar "siempre activa" de forma pasiva.
- **Bajo Consumo:** BLE está optimizado para consumir muy poca energía durante el escaneo y la publicidad, lo que es ideal para una app que se ejecuta constantemente en segundo plano. Wi-Fi Direct es más potente pero consume mucha más batería.
- **Conexiones Múltiples:** Aunque BLE tiene limitaciones, el modelo de cliente/servidor GATT permite que un dispositivo actúe como servidor para múltiples clientes, lo que se adapta bien a la estructura de la red de malla.
- **Simplicidad de Descubrimiento:** El sistema de `Advertising` de BLE es perfecto para que los nodos se descubran pasivamente sin necesidad de un emparejamiento complejo.

#### P: ¿Cómo funciona tu protocolo de enrutamiento y por qué elegiste "Flooding"?
**R:** Para el MVP, implementé un protocolo de enrutamiento simple y robusto llamado **Flooding controlado por TTL (Time-To-Live)**.
- **Funcionamiento:** Cuando un nodo recibe un mensaje que no es para él, lo retransmite a todos sus vecinos, excepto al que se lo envió. Cada "salto" reduce el TTL del mensaje. Cuando el TTL llega a 0, el mensaje se descarta. Esto evita bucles de retransmisión infinitos.
- **¿Por qué esta estrategia?** Para una red pequeña y un MVP, el flooding es muy fiable y fácil de implementar. No requiere que los nodos mantengan complejas tablas de enrutamiento. La alternativa, como AODV o DSDV, añade una sobrecarga significativa que no era necesaria en esta fase inicial. El **protocolo de chismorreo (Gossip)** que implementé sienta las bases para un enrutamiento más inteligente en el futuro, ya que permite que los nodos conozcan la topología de la red.

#### P: ¿Cómo resolviste el desafío de la mensajería offline (sin conexión)?
**R:** El núcleo de la solución es la estrategia **"Store-and-Forward"**, que se apoya en dos componentes clave: **Room** y **WorkManager**.
1.  **Store (Almacenar):** Cuando un usuario envía un mensaje, este se guarda *inmediatamente* en la base de datos local (Room) con el estado `PENDING`. Esto garantiza que ningún mensaje se pierda, incluso si no hay conexión en ese momento.
2.  **Forward (Reenviar):** La aplicación intenta reenviar los mensajes `PENDING` en dos escenarios:
    - **Reactivamente:** Cuando se establece una nueva conexión Bluetooth, se activa una función que busca y reenvía todos los mensajes pendientes.
    - **Proactivamente:** Gracias a **WorkManager**, una tarea en segundo plano se ejecuta periódicamente (cada 15 minutos) y hace lo mismo. Esto asegura que los mensajes se intenten enviar de forma fiable incluso si la aplicación no está en primer plano.

#### P: ¿Cómo gestionaste el estado de la UI con datos que cambian en tiempo real?
**R:** Utilicé un enfoque reactivo moderno con **Kotlin Flows** y **Jetpack Compose**.
- El `DAO` de Room expone las consultas a la base de datos como un `Flow<List<...>>`.
- El `BluetoothController` consume estos Flows y los transforma en un `StateFlow`, que representa la fuente única de verdad para el estado de la UI (mensajes, nodos conocidos, etc.).
- El `ViewModel` expone estos `StateFlow`s.
- La `UI (Compose)` utiliza `collectAsState()` para suscribirse a estos `StateFlow`s. Cuando los datos cambian en la base de datos (por ejemplo, llega un nuevo mensaje), el `Flow` emite un nuevo valor, que se propaga hasta la UI, que se recompone automáticamente para mostrar la información actualizada. Este patrón elimina la necesidad de manejar manualmente las actualizaciones de la UI.

---

## 🧪 Cómo Probar la Aplicación
Para probar la funcionalidad de la red de malla, necesitarás:
1.  **Dos o más dispositivos físicos Android** con Bluetooth activado. El emulador de Android no tiene soporte completo para las funcionalidades de BLE necesarias.
2.  Instalar la aplicación en todos los dispositivos.
3.  Abrir la aplicación y otorgar los permisos de Bluetooth y Ubicación.
4.  Observar cómo los dispositivos se descubren en la `DeviceListScreen`. Puedes conectar uno a otro para chatear directamente. Para probar la retransmisión, necesitarás tres dispositivos (A, B y C), conectar A con B y B con C, y enviar un mensaje desde A.

---

## 🚀 Próximos Pasos
- **Cifrado de Extremo a Extremo (E2E):** Implementar un protocolo de intercambio de claves para que solo el emisor y el receptor final puedan leer los mensajes.
- **Enrutamiento Inteligente:** Usar la información de la tabla de `known_nodes` para tomar decisiones más inteligentes sobre a quién reenviar un mensaje.
- **Confirmación de Lectura (`READ`):** Añadir la lógica y el tipo de mensaje para confirmar que un mensaje ha sido visto por el usuario.
- **Interfaz de Usuario Mejorada:** Añadir indicadores de estado de mensaje (`SENT`, `DELIVERED`), una vista de conversación por cada chat, etc.
