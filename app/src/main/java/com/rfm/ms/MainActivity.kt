package com.rfm.ms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

val Purple = Color(0xFF6C3CE9)
val Gold = Color(0xFFFFB300)
const val DEV_NUMBER = "+5000000001"
private val db get() = FirebaseFirestore.getInstance()

fun label(n: String) = if (n == DEV_NUMBER) "⭐ المطور rFM ✔" else n

data class Chat(val id: String, val peer: String, val last: String, val ts: Long)
data class Msg(val from: String, val text: String, val ts: Long)

/** Sign in anonymously, then give the user a unique virtual number (once). */
suspend fun ensureNumber(): Pair<String, String> {
    val auth = FirebaseAuth.getInstance()
    val uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid
    db.collection("users").document(uid).get().await().getString("number")?.let { return uid to it }
    while (true) {
        // أول رقم بعد 500 من 1 إلى 9 حتى لا يتعارض مع أرقام المطور المحجوزة (+5000...)
        val n = "+500" + Random.nextInt(1, 10) + (1..6).joinToString("") { Random.nextInt(10).toString() }
        val ref = db.collection("numbers").document(n)
        val ok = db.runTransaction(Transaction.Function { t ->
            if (t.get(ref).exists()) false else { t.set(ref, mapOf("uid" to uid)); true }
        }).await()
        if (ok) {
            db.collection("users").document(uid).set(mapOf("number" to n)).await()
            return uid to n
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Purple)) { App() } }
    }
}

@Composable
fun App() {
    var me by remember { mutableStateOf<Pair<String, String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf<Chat?>(null) }
    LaunchedEffect(Unit) { try { me = ensureNumber() } catch (e: Exception) { err = e.message } }
    val m = me
    BackHandler(enabled = about || chat != null) { about = false; chat = null }
    when {
        err != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text("خطأ في الاتصال: $err") }
        m == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Purple)
                Spacer(Modifier.height(12.dp)); Text("جاري إنشاء رقمك...")
            }
        }
        about -> About { about = false }
        chat != null -> ChatScreen(m.first, m.second, chat!!) { chat = null }
        else -> Home(m.first, m.second, { chat = it }, { about = true })
    }
}

@Composable
fun Home(uid: String, number: String, open: (Chat) -> Unit, onAbout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var peer by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf(listOf<Chat>()) }

    DisposableEffect(uid) {
        val reg = db.collection("chats").whereArrayContains("uids", uid).addSnapshotListener { s, _ ->
            if (s != null) chats = s.documents.map { d ->
                val nums = (d.get("numbers") as? List<*>)?.map { it.toString() } ?: listOf()
                Chat(d.id, nums.firstOrNull { it != number } ?: number, d.getString("last") ?: "", d.getLong("ts") ?: 0L)
            }.sortedByDescending { it.ts }
        }
        onDispose { reg.remove() }
    }

    fun start(p: String) {
        scope.launch {
            try {
                val pu = db.collection("numbers").document(p).get().await().getString("uid")
                if (pu == null) Toast.makeText(ctx, "الرقم غير موجود", Toast.LENGTH_SHORT).show()
                else if (pu == uid) Toast.makeText(ctx, "هذا رقمك أنت", Toast.LENGTH_SHORT).show()
                else {
                    val id = listOf(uid, pu).sorted().joinToString("_")
                    db.collection("chats").document(id)
                        .set(mapOf("uids" to listOf(uid, pu), "numbers" to listOf(number, p)), SetOptions.merge()).await()
                    peer = ""; open(Chat(id, p, "", 0L))
                }
            } catch (e: Exception) { Toast.makeText(ctx, "فشل: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val iAmDev = number == DEV_NUMBER
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Purple).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MS", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(if (iAmDev) "⭐ رقمك (المطور) ✔: $number" else "رقمك: $number",
                    color = if (iAmDev) Gold else Color.White.copy(alpha = .9f),
                    fontWeight = if (iAmDev) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("number", number))
                        Toast.makeText(ctx, "تم نسخ الرقم", Toast.LENGTH_SHORT).show()
                    })
            }
            TextButton(onClick = onAbout) { Text("عن المطور", color = Color.White) }
        }

        // بطاقة المطور المميزة
        if (!iAmDev) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .background(Color(0xFFFFF3D6), RoundedCornerShape(14.dp))
                .clickable { start(DEV_NUMBER) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⭐ المطور rFM ✔", fontWeight = FontWeight.Bold, color = Color(0xFF8A5A00))
                    Text("تواصل مع المطور مباشرة  •  $DEV_NUMBER", color = Color(0xFF8A5A00), fontSize = 12.sp)
                }
                Text("💬", fontSize = 22.sp)
            }
        }

        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(peer, { peer = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("أدخل رقم الشخص مثل +5001234567") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { start(peer.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("بدء") }
        }
        if (chats.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("لا توجد محادثات بعد", color = Color.Gray) }
        LazyColumn { items(chats) { c ->
            Column(Modifier.fillMaxWidth().clickable { open(c) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(label(c.peer), fontWeight = FontWeight.Bold,
                    color = if (c.peer == DEV_NUMBER) Color(0xFFB57F00) else Color.Unspecified)
                Text(c.last, color = Color.Gray, maxLines = 1)
            }
            HorizontalDivider()
        } }
    }
}

@Composable
fun ChatScreen(uid: String, number: String, chat: Chat, back: () -> Unit) {
    var msgs by remember { mutableStateOf(listOf<Msg>()) }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ref = db.collection("chats").document(chat.id)

    DisposableEffect(chat.id) {
        val reg = ref.collection("messages").orderBy("ts", Query.Direction.ASCENDING).addSnapshotListener { s, _ ->
            if (s != null) msgs = s.documents.map { Msg(it.getString("from") ?: "", it.getString("text") ?: "", it.getLong("ts") ?: 0L) }
        }
        onDispose { reg.remove() }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().background(Purple).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("رجوع", color = Color.White) }
            Text(label(chat.peer), color = if (chat.peer == DEV_NUMBER) Gold else Color.White,
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), state = listState) {
            items(msgs) { m ->
                val mine = m.from == uid
                val fromDev = (mine && number == DEV_NUMBER) || (!mine && chat.peer == DEV_NUMBER)
                val bg = if (fromDev) Gold else if (mine) Purple else Color(0xFFEDE7FB)
                val fg = if (fromDev) Color.Black else if (mine) Color.White else Color.Black
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
                    Text(m.text, color = fg,
                        modifier = Modifier.widthIn(max = 280.dp).background(bg, RoundedCornerShape(14.dp)).padding(10.dp))
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("اكتب رسالة") })
            Spacer(Modifier.width(8.dp))
            Button(colors = ButtonDefaults.buttonColors(containerColor = Purple), onClick = {
                val t = text.trim(); if (t.isEmpty()) return@Button
                text = ""
                val now = System.currentTimeMillis()
                ref.collection("messages").add(mapOf("from" to uid, "text" to t, "ts" to now))
                ref.set(mapOf("last" to t, "ts" to now), SetOptions.merge())
            }) { Text("إرسال") }
        }
    }
}

@Composable
fun About(back: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(90.dp).background(Purple, RoundedCornerShape(24.dp)), Alignment.Center) {
            Text("MS", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("تطبيق MS للمحادثات", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("⭐ المطور: rFM ✔", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB57F00))
        Text("رقم داخل التطبيق: $DEV_NUMBER", color = Color.Gray)
        Text("+201027814323", color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Button(colors = ButtonDefaults.buttonColors(containerColor = Purple), onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/201027814323")))
        }) { Text("تواصل مع المطور على واتساب") }
        TextButton(onClick = back) { Text("رجوع") }
    }
}
