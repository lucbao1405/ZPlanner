package com.example.zplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.zplanner.data.ApiTask
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TaskScreen(
    viewModel: TaskViewModel = hiltViewModel(),
    onSignOut: () -> Unit = {}
) {
    val date by viewModel.date.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    var editingTask by remember { mutableStateOf<ApiTask?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = viewModel::previousDay) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Ngày trước")
                }
                Text(date, style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = onSignOut) { Text("Đăng xuất") }
                    IconButton(onClick = viewModel::nextDay) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ngày sau")
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingTask = null; showEditor = true }) {
                Icon(Icons.Default.Add, "Thêm công việc")
            }
        }
    ) { padding ->
        when {
            loading && tasks.isEmpty() -> CircularProgressIndicator(modifier = Modifier.padding(padding))
            error != null && tasks.isEmpty() -> Column(Modifier.padding(padding).padding(24.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("Thử lại") }
            }
            tasks.isEmpty() -> Text("Chưa có công việc trong ngày này.", Modifier.padding(padding).padding(24.dp))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = ApiTask::id) { task ->
                    TaskCard(task, onEdit = { editingTask = task; showEditor = true }, onDelete = { viewModel.delete(task) })
                }
            }
        }
    }

    if (showEditor) {
        TaskEditorDialog(
            task = editingTask,
            defaultDate = date,
            onDismiss = { showEditor = false },
            onSave = { viewModel.save(it); showEditor = false }
        )
    }
}

@Composable
private fun TaskCard(task: ApiTask, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text("${task.startTime} - ${task.endTime} · ${task.priority} · ${task.status}")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Sửa công việc") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Xóa công việc") }
        }
    }
}

@Composable
private fun TaskEditorDialog(task: ApiTask?, defaultDate: String, onDismiss: () -> Unit, onSave: (ApiTask) -> Unit) {
    var title by remember(task) { mutableStateOf(task?.title.orEmpty()) }
    var date by remember(task) { mutableStateOf(task?.date ?: defaultDate) }
    var start by remember(task) { mutableStateOf(task?.startTime ?: "09:00") }
    var end by remember(task) { mutableStateOf(task?.endTime ?: "10:00") }
    var priority by remember(task) { mutableStateOf(task?.priority ?: "medium") }
    var status by remember(task) { mutableStateOf(task?.status ?: "todo") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Thêm công việc" else "Sửa công việc") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Tiêu đề") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Ngày (yyyy-MM-dd)") }, singleLine = true)
                OutlinedTextField(start, { start = it }, label = { Text("Bắt đầu (HH:mm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(end, { end = it }, label = { Text("Kết thúc (HH:mm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                ChoiceField("Ưu tiên", priority, listOf("low", "medium", "high")) { priority = it }
                ChoiceField("Trạng thái", status, listOf("todo", "in_progress", "done")) { status = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = validate(title, date, start, end)
                if (error == null) onSave(ApiTask(task?.id ?: 0, title.trim(), date = date, startTime = start, endTime = end, priority = priority, status = status))
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun ChoiceField(label: String, value: String, values: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) { Text("$label: $value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

private fun validate(title: String, date: String, start: String, end: String): String? {
    if (title.isBlank()) return "Tiêu đề không được để trống."
    return try {
        java.time.LocalDate.parse(date)
        if (!LocalTime.parse(end, timeFormatter).isAfter(LocalTime.parse(start, timeFormatter))) "Giờ kết thúc phải sau giờ bắt đầu." else null
    } catch (_: DateTimeParseException) { "Ngày hoặc giờ không đúng định dạng." }
}
