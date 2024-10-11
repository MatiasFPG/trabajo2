package com.example.trabajo2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import coil.compose.rememberAsyncImagePainter
import com.example.trabajo2.Services.ApiService
import com.example.trabajo2.data.ContentBody
import com.example.trabajo2.data.PartBody
import com.example.trabajo2.data.RequestBody
import com.example.trabajo2.ui.theme.Trabajo2Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import android.graphics.Color as AndroidColor

// Clase para representar un mensaje con información adicional.
class Message(
    val itsMine : Boolean,
    val partBody: PartBody,
    val createdAt : String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Trabajo2Theme {
                MainScreen()
            }
        }
    }
}

// Función para hacer la llamada a la API fuera de un contexto @Composable
fun sendToApi(apiService: ApiService, apiKey: String, messages: List<Message>, onResponse: (String?) -> Unit) {
    val partBodies: List<PartBody> = messages.map { it.partBody }
    val ultimaPregunta = messages.lastOrNull()?.partBody?.text ?: ""

    // Crear un prompt mejorado para la IA
    val promptText = """
        Eres un asistente experto en Minecraft. Los usuarios te preguntan cómo crear objetos y tú debes responder con la receta exacta.
        Pregunta del usuario: "$ultimaPregunta".
        Responde de forma precisa y clara.
    """.trimIndent()

    // Modificar la última parte del mensaje para que incluya el prompt
    val modifiedPartBodies = partBodies.toMutableList()
    if (modifiedPartBodies.isNotEmpty()) {
        val lastPart = modifiedPartBodies.removeLast() // Sacamos la última parte
        val newText = "$promptText\n\n${lastPart.text}" // Añadimos el prompt antes del texto original
        modifiedPartBodies.add(PartBody(text = newText)) // Lo volvemos a agregar modificado
    }

    val requestBody = RequestBody(
        contents = listOf(
            ContentBody(
                parts = modifiedPartBodies
            )
        )
    )

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response = apiService.askToGemini(apiKey, requestBody)
            if (response.isSuccessful) {
                val responseData = response.body()
                val apiMessage = responseData?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                onResponse(apiMessage ?: "No pude encontrar la receta para ese objeto. Por favor, intenta con otra pregunta.")
            } else {
                onResponse("Error al comunicarse con la API.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onResponse("Hubo un problema al realizar la solicitud.")
        }
    }
}

@Composable
fun MainScreen() {
    // Estado para determinar si estamos en la pantalla de historial o en el chat
    var isInHistory by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<Message>() }
    val chatHistories = remember { mutableStateListOf<List<Message>>() }

    // Estado para controlar el modo del chat (ACTIVO o LECTURA)
    var chatMode by remember { mutableStateOf<ChatMode>(ChatMode.ACTIVE) }

    if (isInHistory) {
        // Mostrar la pantalla de historial
        HistoryScreen(
            chatHistories = chatHistories,
            onNewChat = {
                chatHistories.add(messages.toList())  // Guardamos el chat actual
                messages.clear()  // Borramos los mensajes para un nuevo chat
                chatMode = ChatMode.ACTIVE  // Reiniciamos el chat a modo activo
                isInHistory = false  // Volvemos a la pantalla de chat
            },
            onChatSelected = { selectedChat ->
                messages.clear()
                messages.addAll(selectedChat)  // Restaurar los mensajes del chat seleccionado
                chatMode = ChatMode.VIEW_ONLY  // Cuando seleccionamos, empieza en modo de solo lectura
                isInHistory = false  // Volvemos a la pantalla de chat
            }
        )
    } else {
        // Mostrar la pantalla del chat
        ChatScreen(
            messages = messages,
            onViewHistory = { isInHistory = true },  // Cambiar a la pantalla de historial
            chatMode = chatMode,  // Pasamos el modo actual del chat
            onChatModeChange = { newMode -> chatMode = newMode }  // Cambiar el modo de chat
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    chatHistories: List<List<Message>>,
    onNewChat: () -> Unit,
    onChatSelected: (List<Message>) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Historial de Chats") }) },
        bottomBar = {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                onClick = onNewChat
            ) {
                Text("Nuevo Chat")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (chatHistories.isEmpty()) {
                Text(
                    text = "No hay chats guardados",
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                // Mostrar los botones de los chats guardados
                for ((index, chat) in chatHistories.withIndex()) {
                    val firstMessage = chat.firstOrNull() // El primer mensaje del chat
                    val previewText = firstMessage?.partBody?.text?.take(15) ?: "Chat vacío"  // Los primeros 10 caracteres
                    val time = firstMessage?.createdAt ?: "Sin hora"  // La hora de creación

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        onClick = { onChatSelected(chat) }
                    ) {
                        Text("$previewText... - $time")  // Texto del botón con preview y hora
                    }
                }
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: SnapshotStateList<Message>,
    onViewHistory: () -> Unit,
    chatMode: ChatMode = ChatMode.ACTIVE, // Nuevo parámetro para el modo de chat
    onChatModeChange: (ChatMode) -> Unit // Callback para cambiar el modo de chat
) {
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            messages.add(
                Message(
                    itsMine = false,
                    createdAt = getCurrentTime(),
                    partBody = PartBody(text = "Hola, soy tu asistente de Minecraft. ¡Puedo ayudarte con cualquier crafteo que necesites!")
                )
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopBar(onViewHistory) },
        bottomBar = {
            if (chatMode == ChatMode.ACTIVE) {
                // Mostrar el input de texto y el botón de enviar cuando el chat está activo
                BottomBar(messages, isLoading, onLoadingChange = { isLoading = it })
            } else {
                // Mostrar el botón "Continuar chat" cuando el chat está en modo de lectura
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    onClick = {
                        onChatModeChange(ChatMode.ACTIVE) // Cambiar el modo a activo
                    }
                ) {
                    Text("Continuar chat")
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for (message in messages) {
                    BubbleMessage(message = message)
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

sealed class ChatMode {
    object ACTIVE : ChatMode() // Modo activo con input de texto y botón de enviar
    object VIEW_ONLY : ChatMode() // Modo de solo lectura, sin input ni botón de enviar
}


@Composable
fun BubbleMessage(message: Message) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = if (message.itsMine) Arrangement.End else Arrangement.Start
    ) {
        // Si el mensaje es de la IA, mostrar la imagen del logo de Minecraft a la izquierda
        if (!message.itsMine) {
            Image(
                painter = rememberAsyncImagePainter(model = R.drawable.ic_minecraft_logo),
                contentDescription = "Logo de Minecraft",
                modifier = Modifier
                    .size(40.dp) // Tamaño de la imagen
                    .padding(end = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .background(
                    color = if (message.itsMine) Color(AndroidColor.parseColor("#DCF8C6")) else Color(
                        AndroidColor.parseColor("#EFEFEF")
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(12.dp)
                .widthIn(max = 280.dp)
        ) {
            Text(
                text = message.partBody.text,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.createdAt,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.End)
            )
        }

        // Si el mensaje es del usuario, mostrar la imagen de WhatsApp sin perfil a la derecha
        if (message.itsMine) {
            Image(
                painter = rememberAsyncImagePainter(model = R.drawable.ic_user),
                contentDescription = "Imagen de usuario",
                modifier = Modifier
                    .size(40.dp) // Tamaño de la imagen
                    .padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun TopBar(onViewHistory: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.LightGray)
            .padding(5.dp)
            .then(modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Asiste IA", fontSize = 20.sp)
        Button(onClick = onViewHistory) {
            Text(text = "Ver historial")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBar(
    messages: SnapshotStateList<Message>,
    isLoading: Boolean,
    onLoadingChange: (Boolean) -> Unit
) {
    var message by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.LightGray)
            .padding(5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = message,
            onValueChange = { message = it },
            enabled = !isLoading // Desactivamos el input mientras carga
        )

        Button(
            modifier = Modifier.padding(start = 10.dp),
            onClick = {
                if (message.isNotBlank() && !isLoading) {
                    messages.add(
                        Message(
                            itsMine = true,
                            createdAt = getCurrentTime(),
                            partBody = PartBody(text = message)
                        )
                    )
                    message = ""
                    onLoadingChange(true)
                    val retrofit = Retrofit.Builder()
                        .baseUrl("https://generativelanguage.googleapis.com")
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()

                    coroutineScope.launch {
                        sendToApi(
                            apiService = retrofit.create(ApiService::class.java),
                            apiKey = "AIzaSyDrvinv6T2czWCN48s0yzShDvk8sk41sJo",
                            messages = messages
                        ) { apiResponse ->
                            apiResponse?.let {
                                messages.add(
                                    Message(
                                        itsMine = false,
                                        createdAt = getCurrentTime(),
                                        partBody = PartBody(text = it)
                                    )
                                )
                            }
                            onLoadingChange(false)
                        }
                    }
                }
            },
            enabled = !isLoading // Desactivar botón mientras carga
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text(text = "Enviar")
            }
        }
    }
}

fun getCurrentTime() : String {
    val now = LocalTime.now()
    val format = DateTimeFormatter.ofPattern("HH:mm")
    return now.format(format)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Trabajo2Theme {
        MainScreen()
    }
}
