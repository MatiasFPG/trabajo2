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
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import coil.compose.rememberAsyncImagePainter
import com.example.trabajo2.Services.ApiService
import com.example.trabajo2.data.ContentBody
import com.example.trabajo2.data.PartBody
import com.example.trabajo2.data.RequestBody
import com.example.trabajo2.ui.theme.Trabajo2Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import android.graphics.Color as AndroidColor

class Message(
    val itsMine: Boolean,
    val partBody: PartBody,
    val createdAt: String
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

fun sendToApi(apiService: ApiService, apiKey: String, messages: List<Message>, onResponse: (String?) -> Unit) {
    val partBodies: List<PartBody> = messages.map { it.partBody }
    val ultimaPregunta = messages.lastOrNull()?.partBody?.text ?: ""

    val promptText = """
        Eres un asistente experto en Minecraft. Los usuarios te preguntan cómo crear objetos y tú debes responder con la receta exacta.
        Pregunta del usuario: "$ultimaPregunta".
        Responde de forma precisa y clara.
    """.trimIndent()

    val modifiedPartBodies = partBodies.toMutableList()
    if (modifiedPartBodies.isNotEmpty()) {
        val lastPart = modifiedPartBodies.removeLast()
        val newText = "$promptText\n\n${lastPart.text}"
        modifiedPartBodies.add(PartBody(text = newText))
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
    val context = LocalContext.current
    var isInHistory by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<Message>() }
    val chatHistories = remember { mutableStateListOf<List<Message>>() }
    var chatMode by remember { mutableStateOf<ChatMode>(ChatMode.ACTIVE) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val savedChats = loadChats(context)
        chatHistories.addAll(savedChats)
    }

    if (isInHistory) {
        HistoryScreen(
            chatHistories = chatHistories,
            onNewChat = {
                chatHistories.add(messages.toList())
                saveChats(context, chatHistories)
                messages.clear()
                chatMode = ChatMode.ACTIVE
                isInHistory = false
            },
            onChatSelected = { selectedChat ->
                messages.clear()
                messages.addAll(selectedChat)
                chatMode = ChatMode.VIEW_ONLY
                isInHistory = false
            }
        )
    } else {
        ChatScreen(
            messages = messages,
            onViewHistory = {
                isInHistory = true
            },
            chatMode = chatMode,
            onChatModeChange = { newMode -> chatMode = newMode }
        )
    }
}

fun saveChats(context: Context, chats: List<List<Message>>) {
    val sharedPreferences: SharedPreferences = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    val gson = Gson()

    val chatsJson = gson.toJson(chats)
    editor.putString("chats", chatsJson)
    editor.apply()
}

fun loadChats(context: Context): MutableList<List<Message>> {
    val sharedPreferences: SharedPreferences = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    val gson = Gson()
    val chatsJson = sharedPreferences.getString("chats", null)

    return if (chatsJson != null) {
        val type = object : TypeToken<MutableList<List<Message>>>() {}.type
        gson.fromJson(chatsJson, type)
    } else {
        mutableListOf()
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
        topBar = { TopAppBar(title = { Text("Historial de Chats") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (chatHistories.isEmpty()) {
                Text(
                    text = "No hay chats guardados",
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    for ((index, chat) in chatHistories.withIndex()) {
                        val firstMessage = chat.firstOrNull()
                        val previewText = firstMessage?.partBody?.text?.take(10) ?: "Chat vacío"
                        val time = firstMessage?.createdAt ?: "Sin hora"

                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            onClick = { onChatSelected(chat) }
                        ) {
                            Text("$previewText... - $time")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    modifier = Modifier
                        .width(120.dp)
                        .padding(top = 8.dp),
                    onClick = onNewChat
                ) {
                    Text("Nuevo chat")
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
    chatMode: ChatMode = ChatMode.ACTIVE,
    onChatModeChange: (ChatMode) -> Unit
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
                BottomBar(messages, isLoading, onLoadingChange = { isLoading = it })
            } else {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    onClick = {
                        onChatModeChange(ChatMode.ACTIVE)
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
    object ACTIVE : ChatMode()
    object VIEW_ONLY : ChatMode()
}

@Composable
fun BubbleMessage(message: Message) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = if (message.itsMine) Arrangement.End else Arrangement.Start
    ) {
        if (!message.itsMine) {
            Image(
                painter = rememberAsyncImagePainter(model = R.drawable.ic_minecraft_logo),
                contentDescription = "Logo de Minecraft",
                modifier = Modifier
                    .size(40.dp)
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

        if (message.itsMine) {
            Image(
                painter = rememberAsyncImagePainter(model = R.drawable.ic_user),
                contentDescription = "Imagen de usuario",
                modifier = Modifier
                    .size(40.dp)
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
            enabled = !isLoading
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
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text(text = "Enviar")
            }
        }
    }
}

fun getCurrentTime(): String {
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
