package com.example.zplanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.zplanner.data.ApiTask
import java.time.*
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(viewModel: TaskViewModel = hiltViewModel(), onSignOut: () -> Unit = {}, darkMode: Boolean = false, onDarkModeChange: (Boolean) -> Unit = {}) {
    val dateText by viewModel.date.collectAsState(); val tasks by viewModel.tasks.collectAsState(); val loading by viewModel.loading.collectAsState(); val error by viewModel.error.collectAsState()
    var tab by remember { mutableStateOf(0) }; var editor by remember { mutableStateOf<ApiTask?>(null) }; var showEditor by remember { mutableStateOf(false) }; var settings by remember { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(true) }; val date = LocalDate.parse(dateText); val today = LocalDate.now()
    Scaffold(topBar = { TopAppBar(title = { Text("ZPlanner") }, actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Cài đặt") } }) }, bottomBar = {
        NavigationBar { listOf("Trang chủ" to Icons.Default.Home, "Lịch" to Icons.Default.DateRange, "Công việc" to Icons.Default.Check, "Hồ sơ" to Icons.Default.Person).forEachIndexed { i, item -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) }) } }
    }, floatingActionButton = { FloatingActionButton(onClick = { editor = null; showEditor = true }) { Icon(Icons.Default.Add, "Thêm") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (tab == 1) CalendarHeader(date, viewModel::goTo)
            else if (tab == 3) ProfilePanel(onSignOut)
            else {
                Text(if (tab == 0) "Xin chào, bạn!" else "Công việc", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                if (tab == 0) ProgressSummary(tasks)
                DateNavigator(date, viewModel::previousDay, viewModel::nextDay)
                if (loading && tasks.isEmpty()) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (tasks.isEmpty() && !loading) Text("Chưa có công việc trong ngày này.", modifier = Modifier.padding(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(tasks, key = { it.id }) { task -> TaskCard(task, date = today, onEdit = { editor = task; showEditor = true }, onDelete = { viewModel.delete(task) }) } }
            }
        }
    }
    if (showEditor) TaskEditorDialog(editor, dateText, tasks, { showEditor = false }, { viewModel.save(it); showEditor = false })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Cài đặt") }, text = { Column { Row(verticalAlignment = Alignment.CenterVertically) { Text("Giao diện tối", Modifier.weight(1f)); Switch(darkMode, onDarkModeChange) }; Row(verticalAlignment = Alignment.CenterVertically) { Text("Nhắc nhở công việc", Modifier.weight(1f)); Switch(reminders, { reminders = it }) } } }, confirmButton = { TextButton({ settings = false }) { Text("Xong") } })
}

@Composable private fun ProgressSummary(tasks: List<ApiTask>) { val done = tasks.count { it.status == "done" }; Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Column(Modifier.padding(16.dp)) { Text("Tiến độ hôm nay", style = MaterialTheme.typography.titleMedium); LinearProgressIndicator(if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size, Modifier.fillMaxWidth().padding(vertical = 8.dp)); Text("$done/${tasks.size} công việc hoàn thành") } } }
@Composable private fun DateNavigator(date: LocalDate, previous: () -> Unit, next: () -> Unit) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { IconButton(previous) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Trước") }; Text(date.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy"))); IconButton(next) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Sau") } } }
@Composable private fun CalendarHeader(selected: LocalDate, goTo: (LocalDate) -> Unit) { Text("Lịch tuần", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)); val monday = selected.with(DayOfWeek.MONDAY); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { (0..6).forEach { offset -> val d = monday.plusDays(offset.toLong()); TextButton(onClick = { goTo(d) }) { Text("${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("vi"))}\n${d.dayOfMonth}", color = if (d == selected) MaterialTheme.colorScheme.primary else Color.Unspecified) } } } }
@Composable private fun ProfilePanel(onSignOut: () -> Unit) { Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Hồ sơ cá nhân", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(16.dp)); Text("Quản lý thông tin và tài khoản của bạn"); Spacer(Modifier.height(24.dp)); OutlinedButton(onClick = onSignOut) { Text("Đăng xuất") } } }
@Composable private fun TaskCard(task: ApiTask, date: LocalDate, onEdit: () -> Unit, onDelete: () -> Unit) { val overdue = task.status != "done" && (task.date < date.toString() || (task.date == date.toString() && runCatching { LocalTime.parse(task.endTime, timeFormat).isBefore(LocalTime.now()) }.getOrDefault(false))); Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(task.title, fontWeight = FontWeight.SemiBold); Text("${task.startTime} - ${task.endTime} · ${task.priority}"); if (overdue) Text("Quá hạn", color = MaterialTheme.colorScheme.error) }; IconButton(onEdit) { Icon(Icons.Default.Edit, "Sửa") }; IconButton(onDelete) { Icon(Icons.Default.Delete, "Xóa") } } } }

@Composable private fun TaskEditorDialog(task: ApiTask?, defaultDate: String, existing: List<ApiTask>, dismiss: () -> Unit, save: (ApiTask) -> Unit) { var title by remember(task) { mutableStateOf(task?.title.orEmpty()) }; var date by remember(task) { mutableStateOf(task?.date ?: defaultDate) }; var start by remember(task) { mutableStateOf(task?.startTime ?: "09:00") }; var end by remember(task) { mutableStateOf(task?.endTime ?: "10:00") }; var priority by remember(task) { mutableStateOf(task?.priority ?: "medium") }; var status by remember(task) { mutableStateOf(task?.status ?: "todo") }; var warning by remember { mutableStateOf<String?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text(if (task == null) "Thêm công việc" else "Sửa công việc") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Tiêu đề") }, singleLine = true); OutlinedTextField(date, { date = it }, label = { Text("Ngày yyyy-MM-dd") }, singleLine = true); OutlinedTextField(start, { start = it }, label = { Text("Bắt đầu HH:mm") }, singleLine = true); OutlinedTextField(end, { end = it }, label = { Text("Kết thúc HH:mm") }, singleLine = true); TextButton({ priority = if (priority == "high") "medium" else "high" }) { Text("Ưu tiên: $priority") }; TextButton({ status = if (status == "done") "todo" else "done" }) { Text("Trạng thái: $status") }; warning?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { val overlap = existing.any { it.id != task?.id && it.date == date && runCatching { LocalTime.parse(start, timeFormat).isBefore(LocalTime.parse(it.endTime, timeFormat)) && LocalTime.parse(end, timeFormat).isAfter(LocalTime.parse(it.startTime, timeFormat)) }.getOrDefault(false) }; warning = when { title.isBlank() -> "Tiêu đề không được để trống."; overlap -> "Cảnh báo: thời gian trùng với công việc khác."; else -> null }; if (warning == null) save(ApiTask(task?.id ?: 0, title.trim(), date = date, startTime = start, endTime = end, priority = priority, status = status)) }) { Text("Lưu") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Hủy") } }) }
