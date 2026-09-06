package com.balesky.messenger4friends

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID

// --- МОДЕЛИ ДАННЫХ ---

data class UserProfile(
    val uid: String = "",
    val uniqueId: String = "",
    val nickname: String = "",
    val email: String = ""
)

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val text: String = "",
    val imageUrl: String? = null,
    val timestamp: Long = 0L
)

sealed interface Screen {
    data object Auth : Screen
    data object FriendsList : Screen
    data class Chat(val friend: UserProfile) : Screen
}

// --- ОСНОВНАЯ АКТИВНОСТЬ ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MessengerApp()
                }
            }
        }
    }
}

// --- УПРАВЛЕНИЕ СЕТЕВЫМ ПРИСУТСТВИЕМ (ONLINE / OFFLINE) ---

fun setupPresence(uid: String) {
    val db = FirebaseDatabase.getInstance()
    val statusRef = db.getReference("status").child(uid)
    val connectedRef = db.getReference(".info/connected")

    connectedRef.addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val connected = snapshot.getValue(Boolean::class.java) ?: false
            if (connected) {
                statusRef.onDisconnect().setValue(
                    mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP)
                )
                statusRef.setValue(
                    mapOf("isOnline" to true, "lastSeen" to ServerValue.TIMESTAMP)
                )
            }
        }
        override fun onCancelled(error: DatabaseError) {}
    })
}

// --- НАВИГАЦИЯ И КОРНЕВОЙ ЭКРАН ---

@Composable
fun MessengerApp() {
    val auth = FirebaseAuth.getInstance()
    var currentScreen by remember {
        mutableStateOf<Screen>(
            if (auth.currentUser != null) {
                setupPresence(auth.currentUser!!.uid)
                Screen.FriendsList
            } else {
                Screen.Auth
            }
        )
    }

    when (val screen = currentScreen) {
        is Screen.Auth -> {
            AuthScreen(onAuthSuccess = { uid ->
                setupPresence(uid)
                currentScreen = Screen.FriendsList
            })
        }
        is Screen.FriendsList -> {
            FriendsListScreen(
                currentUid = auth.currentUser?.uid ?: "",
                onUserClick = { user -> currentScreen = Screen.Chat(user) },
                onLogout = {
                    auth.currentUser?.uid?.let { uid ->
                        FirebaseDatabase.getInstance().getReference("status").child(uid)
                            .setValue(mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP))
                    }
                    auth.signOut()
                    currentScreen = Screen.Auth
                }
            )
        }
        is Screen.Chat -> {
            ChatScreen(
                currentUid = auth.currentUser?.uid ?: "",
                friend = screen.friend,
                onBack = { currentScreen = Screen.FriendsList }
            )
        }
    }
}

// --- 1. ЭКРАН АВТОРИЗАЦИИ И РЕГИСТРАЦИИ ---

@Composable
fun AuthScreen(onAuthSuccess: (String) -> Unit) {
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isRegister) "Создать аккаунт" else "Вход в чат",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isRegister) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Ваш никнейм") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Электронная почта") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    if (email.isBlank() || password.isBlank() || (isRegister && nickname.isBlank())) {
                        Toast.makeText(context, "Заполните все поля", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    val auth = FirebaseAuth.getInstance()
                    val firestore = FirebaseFirestore.getInstance()

                    if (isRegister) {
                        auth.createUserWithEmailAndPassword(email.trim(), password.trim())
                            .addOnSuccessListener { result ->
                                val uid = result.user?.uid ?: return@addOnSuccessListener
                                val uniqueId = "#" + (100000..999999).random()
                                val user = UserProfile(
                                    uid = uid,
                                    uniqueId = uniqueId,
                                    nickname = nickname.trim(),
                                    email = email.trim()
                                )
                                firestore.collection("users").document(uid).set(user)
                                    .addOnSuccessListener {
                                        isLoading = false
                                        onAuthSuccess(uid)
                                    }
                                    .addOnFailureListener { e ->
                                        isLoading = false
                                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_LONG).show()
                                    }
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                Toast.makeText(context, e.localizedMessage, Toast.LENGTH_LONG).show()
                            }
                    } else {
                        auth.signInWithEmailAndPassword(email.trim(), password.trim())
                            .addOnSuccessListener { result ->
                                isLoading = false
                                result.user?.uid?.let { onAuthSuccess(it) }
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                Toast.makeText(context, e.localizedMessage, Toast.LENGTH_LONG).show()
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRegister) "Зарегистрироваться" else "Войти")
            }

            TextButton(onClick = { isRegister = !isRegister }) {
                Text(
                    if (isRegister) "Уже есть аккаунт? Войти"
                    else "Нет аккаунта? Зарегистрироваться"
                )
            }
        }
    }
}

// --- 2. ЭКРАН СПИСКА ДРУЗЕЙ (ОНЛАЙН / ОФЛАЙН) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsListScreen(
    currentUid: String,
    onUserClick: (UserProfile) -> Unit,
    onLogout: () -> Unit
) {
    var users by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var onlineMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var currentUserProfile by remember { mutableStateOf<UserProfile?>(null) }

    // Загрузка профилей пользователей
    DisposableEffect(Unit) {
        val listener = FirebaseFirestore.getInstance().collection("users")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val list = mutableListOf<UserProfile>()
                    for (doc in snapshot.documents) {
                        val u = doc.toObject(UserProfile::class.java)
                        if (u != null) {
                            if (u.uid == currentUid) {
                                currentUserProfile = u
                            } else {
                                list.add(u)
                            }
                        }
                    }
                    users = list
                }
            }
        onDispose { listener.remove() }
    }

    // Отслеживание онлайн статусов
    DisposableEffect(Unit) {
        val statusRef = FirebaseDatabase.getInstance().getReference("status")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val map = mutableMapOf<String, Boolean>()
                for (child in snapshot.children) {
                    val isOnline = child.child("isOnline").getValue(Boolean::class.java) ?: false
                    map[child.key ?: ""] = isOnline
                }
                onlineMap = map
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        statusRef.addValueEventListener(listener)
        onDispose { statusRef.removeEventListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(currentUserProfile?.nickname ?: "Мои друзья")
                        currentUserProfile?.let {
                            Text(
                                "Ваш ID: ${it.uniqueId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Выйти")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(users) { user ->
                val isOnline = onlineMap[user.uid] == true
                UserListItem(user = user, isOnline = isOnline, onClick = { onUserClick(user) })
            }
        }
    }
}

@Composable
fun UserListItem(user: UserProfile, isOnline: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(50.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = user.nickname.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Кружок статуса (Зеленый = онлайн, Серый = офлайн)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .background(if (isOnline) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(user.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${user.uniqueId} • ${if (isOnline) "В сети" else "Не в сети"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isOnline) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- 3. ЭКРАН ЧАТА И ПЕРЕДАЧИ ФОТО ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    currentUid: String,
    friend: UserProfile,
    onBack: () -> Unit
) {
    val chatId = if (currentUid < friend.uid) "${currentUid}_${friend.uid}" else "${friend.uid}_${currentUid}"
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var messageText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isSending by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> selectedImageUri = uri }

    // Загрузка сообщений диалога
    DisposableEffect(chatId) {
        val listener = FirebaseFirestore.getInstance()
            .collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    messages = snapshot.documents.mapNotNull { it.toObject(ChatMessage::class.java) }
                }
            }
        onDispose { listener.remove() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(friend.nickname)
                        Text(friend.uniqueId, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
                    ChatBubble(message = msg, isMe = msg.senderId == currentUid)
                }
            }

            // Превью выбранной перед отправкой фотографии
            selectedImageUri?.let { uri ->
                Box(modifier = Modifier.padding(8.dp)) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { selectedImageUri = null },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Удалить фото", tint = Color.Red)
                    }
                }
            }

            // Панель ввода текста и прикрепления фото
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Прикрепить фото")
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("Сообщение...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                } else {
                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank() || selectedImageUri != null) {
                                isSending = true
                                val textToSend = messageText.trim()
                                val imageUriToSend = selectedImageUri

                                messageText = ""
                                selectedImageUri = null

                                if (imageUriToSend != null) {
                                    // Загрузка фото в Firebase Storage
                                    val storageRef = FirebaseStorage.getInstance().reference
                                        .child("chats/$chatId/${UUID.randomUUID()}.jpg")
                                    storageRef.putFile(imageUriToSend)
                                        .continueWithTask { task ->
                                            if (!task.isSuccessful) throw task.exception!!
                                            storageRef.downloadUrl
                                        }
                                        .addOnSuccessListener { downloadUrl ->
                                            sendFirestoreMessage(chatId, currentUid, textToSend, downloadUrl.toString())
                                            isSending = false
                                        }
                                        .addOnFailureListener {
                                            isSending = false
                                        }
                                } else {
                                    sendFirestoreMessage(chatId, currentUid, textToSend, null)
                                    isSending = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

fun sendFirestoreMessage(chatId: String, senderId: String, text: String, imageUrl: String?) {
    val message = ChatMessage(
        id = UUID.randomUUID().toString(),
        senderId = senderId,
        text = text,
        imageUrl = imageUrl,
        timestamp = System.currentTimeMillis()
    )
    FirebaseFirestore.getInstance()
        .collection("chats")
        .document(chatId)
        .collection("messages")
        .add(message)
}

@Composable
fun ChatBubble(message: ChatMessage, isMe: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                message.imageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = "Фото",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    if (message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
