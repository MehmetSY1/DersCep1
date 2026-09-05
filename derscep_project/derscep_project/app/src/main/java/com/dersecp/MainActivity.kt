package com.dersecp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ============================================================
// DATA LAYER — Entities
// ============================================================

enum class Priority { LOW, MEDIUM, HIGH }

@Entity(tableName = "class_schedule")
data class ClassSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val dayOfWeek: Int, // 1=Pazartesi ... 7=Pazar
    val startMinute: Int,
    val endMinute: Int,
    val teacherName: String? = null,
    val room: String? = null,
    val colorHex: String = "#6C63FF"
)

@Entity(tableName = "homework")
data class Homework(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val courseName: String? = null,
    val dueDateEpochDay: Long,
    val priority: Priority = Priority.MEDIUM,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "exam")
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val courseName: String? = null,
    val examDateEpochDay: Long,
    val topics: String? = null
)

class Converters {
    @TypeConverter fun fromPriority(p: Priority): String = p.name
    @TypeConverter fun toPriority(v: String): Priority = Priority.valueOf(v)
}

// ============================================================
// DATA LAYER — DAOs
// ============================================================

@Dao
interface ClassScheduleDao {
    @Query("SELECT * FROM class_schedule ORDER BY dayOfWeek, startMinute")
    fun getAll(): Flow<List<ClassSchedule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClassSchedule): Long

    @Delete
    suspend fun delete(item: ClassSchedule)
}

@Dao
interface HomeworkDao {
    @Query("SELECT * FROM homework ORDER BY isCompleted ASC, dueDateEpochDay ASC")
    fun getAll(): Flow<List<Homework>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Homework): Long

    @Update
    suspend fun update(item: Homework)

    @Delete
    suspend fun delete(item: Homework)

    @Query("UPDATE homework SET isCompleted = :completed, completedAtEpochMillis = :completedAt WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean, completedAt: Long?)
}

@Dao
interface ExamDao {
    @Query("SELECT * FROM exam ORDER BY examDateEpochDay ASC")
    fun getAll(): Flow<List<Exam>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Exam): Long

    @Delete
    suspend fun delete(item: Exam)
}

// ============================================================
// DATA LAYER — Database
// ============================================================

@Database(
    entities = [ClassSchedule::class, Homework::class, Exam::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DersCepDatabase : RoomDatabase() {
    abstract fun classScheduleDao(): ClassScheduleDao
    abstract fun homeworkDao(): HomeworkDao
    abstract fun examDao(): ExamDao

    companion object {
        @Volatile private var INSTANCE: DersCepDatabase? = null

        fun getInstance(context: android.content.Context): DersCepDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DersCepDatabase::class.java,
                    "derscep.db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// ============================================================
// VIEWMODEL
// ============================================================

class MainViewModel(private val db: DersCepDatabase) : ViewModel() {

    val schedule: StateFlow<List<ClassSchedule>> = db.classScheduleDao().getAll()
        .let { flow ->
            val state = MutableStateFlow<List<ClassSchedule>>(emptyList())
            viewModelScope.launch { flow.collect { state.value = it } }
            state.asStateFlow()
        }

    val homework: StateFlow<List<Homework>> = db.homeworkDao().getAll()
        .let { flow ->
            val state = MutableStateFlow<List<Homework>>(emptyList())
            viewModelScope.launch { flow.collect { state.value = it } }
            state.asStateFlow()
        }

    val exams: StateFlow<List<Exam>> = db.examDao().getAll()
        .let { flow ->
            val state = MutableStateFlow<List<Exam>>(emptyList())
            viewModelScope.launch { flow.collect { state.value = it } }
            state.asStateFlow()
        }

    fun addHomework(title: String, courseName: String, dueDateEpochDay: Long) {
        viewModelScope.launch {
            db.homeworkDao().insert(
                Homework(title = title, courseName = courseName.ifBlank { null }, dueDateEpochDay = dueDateEpochDay)
            )
        }
    }

    fun toggleHomework(item: Homework) {
        viewModelScope.launch {
            db.homeworkDao().setCompleted(
                item.id,
                !item.isCompleted,
                if (!item.isCompleted) System.currentTimeMillis() else null
            )
        }
    }

    fun addExam(title: String, courseName: String, examDateEpochDay: Long) {
        viewModelScope.launch {
            db.examDao().insert(Exam(title = title, courseName = courseName.ifBlank { null }, examDateEpochDay = examDateEpochDay))
        }
    }

    fun addClass(courseName: String, dayOfWeek: Int, startMinute: Int, endMinute: Int) {
        viewModelScope.launch {
            db.classScheduleDao().insert(
                ClassSchedule(courseName = courseName, dayOfWeek = dayOfWeek, startMinute = startMinute, endMinute = endMinute)
            )
        }
    }

    class Factory(private val db: DersCepDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(db) as T
        }
    }
}

// ============================================================
// UI
// ============================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = DersCepDatabase.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(db))
                DersCepApp(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DersCepApp(vm: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Program", "Ödevler", "Sınavlar")
    val icons = listOf(Icons.Filled.DateRange, Icons.Filled.List, Icons.Filled.Edit)

    Scaffold(
        topBar = { TopAppBar(title = { Text("DersCep") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ScheduleScreen(vm)
                1 -> HomeworkScreen(vm)
                2 -> ExamScreen(vm)
            }
        }
    }
}

@Composable
fun ScheduleScreen(vm: MainViewModel) {
    val schedule by vm.schedule.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var courseName by remember { mutableStateOf("") }
    var dayIndex by remember { mutableStateOf(0) }
    val days = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")

    Box(modifier = Modifier.fillMaxSize()) {
        if (schedule.isEmpty()) {
            Text(
                "Henüz ders eklenmedi. Sağ alttaki + ile ekleyebilirsin.",
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(schedule) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(item.courseName, fontWeight = FontWeight.Bold)
                            Text("${days.getOrElse(item.dayOfWeek - 1) { "?" }} • ${item.startMinute / 60}:${(item.startMinute % 60).toString().padStart(2, '0')} - ${item.endMinute / 60}:${(item.endMinute % 60).toString().padStart(2, '0')}")
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Text("+") }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Ders Ekle") },
            text = {
                Column {
                    OutlinedTextField(value = courseName, onValueChange = { courseName = it }, label = { Text("Ders adı") })
                    Spacer(Modifier.height(8.dp))
                    Text("Gün: ${days[dayIndex]}")
                    Row {
                        TextButton(onClick = { dayIndex = (dayIndex - 1 + 7) % 7 }) { Text("◀") }
                        TextButton(onClick = { dayIndex = (dayIndex + 1) % 7 }) { Text("▶") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (courseName.isNotBlank()) {
                        vm.addClass(courseName, dayIndex + 1, 9 * 60, 10 * 60 + 30)
                        courseName = ""
                        showDialog = false
                    }
                }) { Text("Ekle") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("İptal") } }
        )
    }
}

@Composable
fun HomeworkScreen(vm: MainViewModel) {
    val homework by vm.homework.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        if (homework.isEmpty()) {
            Text("Henüz ödev yok.", modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(homework) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(item.title, fontWeight = FontWeight.Bold)
                                item.courseName?.let { Text(it) }
                                Text("Son tarih: ${LocalDate.ofEpochDay(item.dueDateEpochDay)}")
                            }
                            IconButton(onClick = { vm.toggleHomework(item) }) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Tamamla",
                                    tint = if (item.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Text("+") }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Ödev Ekle") },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Başlık") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Ders (opsiyonel)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        vm.addHomework(title, course, LocalDate.now().plusDays(7).toEpochDay())
                        title = ""; course = ""
                        showDialog = false
                    }
                }) { Text("Ekle") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("İptal") } }
        )
    }
}

@Composable
fun ExamScreen(vm: MainViewModel) {
    val exams by vm.exams.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        if (exams.isEmpty()) {
            Text("Henüz sınav eklenmedi.", modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(exams) { item ->
                    val daysLeft = item.examDateEpochDay - LocalDate.now().toEpochDay()
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(item.title, fontWeight = FontWeight.Bold)
                            item.courseName?.let { Text(it) }
                            Text(if (daysLeft >= 0) "$daysLeft gün kaldı" else "Geçti")
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Text("+") }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Sınav Ekle") },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Başlık") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Ders (opsiyonel)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        vm.addExam(title, course, LocalDate.now().plusDays(14).toEpochDay())
                        title = ""; course = ""
                        showDialog = false
                    }
                }) { Text("Ekle") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("İptal") } }
        )
    }
}
