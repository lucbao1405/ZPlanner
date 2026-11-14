package com.example.zplanner

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.zplanner.data.ApiTask
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var auth: AuthViewModel
    private lateinit var taskViewModel: TaskViewModel
    private var screen = Screen.LOGIN
    private var registerMode = false
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private enum class Screen { LOGIN, HOME, CALENDAR, PROFILE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeColors()
        auth = ViewModelProvider(this)[AuthViewModel::class.java]
        taskViewModel = ViewModelProvider(this)[TaskViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { auth.signedIn.collect { signedIn -> if (signedIn && screen == Screen.LOGIN) showHome() else if (!signedIn && screen != Screen.LOGIN) showLogin() } }
                launch { taskViewModel.sessionExpired.collect { expired -> if (expired) { taskViewModel.clearSessionExpiry(); auth.signOut() } } }
                launch { taskViewModel.tasks.collect { renderTasks(it); renderCalendar(it) } }
                launch { taskViewModel.date.collect { date -> findViewById<TextView?>(R.id.txtSelectedDate)?.text = date } }
                launch { taskViewModel.loading.collect { loading -> findViewById<ProgressBar?>(R.id.progressTasks)?.visibility = if (loading) View.VISIBLE else View.GONE } }
                launch { taskViewModel.error.collect { error -> findViewById<TextView?>(R.id.txtTaskError)?.apply { text = error.orEmpty(); visibility = if (error == null) View.GONE else View.VISIBLE } } }
            }
        }
        if (auth.signedIn.value) showHome() else showLogin()
    }

    override fun onBackPressed() {
        when (screen) { Screen.LOGIN -> super.onBackPressed(); Screen.HOME -> super.onBackPressed(); Screen.CALENDAR, Screen.PROFILE -> showHome() }
    }

    private fun applyThemeColors() {
        val dark = settings.getBoolean("dark_mode", false)
        val background = if (dark) 0xFF121018.toInt() else getColor(R.color.zp_background)
        window.statusBarColor = background; window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.setBackgroundDrawableResource(if (dark) android.R.color.black else R.color.zp_background)
    }

    private fun showLogin() {
        screen = Screen.LOGIN; setContentView(R.layout.dangnhap)
        val email = findViewById<EditText>(R.id.edtEmail); val password = findViewById<EditText>(R.id.edtPassword)
        val login = findViewById<Button>(R.id.btnLogin); val progress = findViewById<ProgressBar>(R.id.progressLogin)
        val error = findViewById<TextView>(R.id.txtPasswordError)
        findViewById<View>(R.id.btnTogglePassword).setOnClickListener {
            val hidden = password.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            password.inputType = InputType.TYPE_CLASS_TEXT or if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.setSelection(password.length())
        }
        findViewById<View>(R.id.btnRegister).setOnClickListener { registerMode = !registerMode; login.text = if (registerMode) "Đăng ký" else "Đăng nhập"; (it as TextView).text = if (registerMode) "Đã có tài khoản? Đăng nhập" else "Đăng ký ngay" }
        findViewById<View>(R.id.btnForgotPassword).setOnClickListener { Toast.makeText(this, "Đặt lại mật khẩu chưa được máy chủ hỗ trợ.", Toast.LENGTH_LONG).show() }
        findViewById<View>(R.id.btnGoogle).setOnClickListener { Toast.makeText(this, "Đăng nhập Google chưa được cấu hình.", Toast.LENGTH_LONG).show() }
        login.setOnClickListener {
            val e = email.text.toString().trim(); val p = password.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches() || p.isBlank()) { error.text = "Vui lòng nhập email hợp lệ và mật khẩu."; error.visibility = View.VISIBLE; return@setOnClickListener }
            error.visibility = View.GONE; progress.visibility = View.VISIBLE; login.isEnabled = false
            auth.submit(e, p, registerMode)
        }
        // The single activity-level collector updates this screen without creating duplicate collectors.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { auth.loading.collect { loading -> if (screen == Screen.LOGIN) { progress.visibility = if (loading) View.VISIBLE else View.GONE; login.isEnabled = !loading } } } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { auth.error.collect { message -> if (screen == Screen.LOGIN) { error.text = message.orEmpty(); error.visibility = if (message == null) View.GONE else View.VISIBLE } } } }
    }

    private fun showHome() {
        screen = Screen.HOME; setContentView(R.layout.trangchu)
        findViewById<View>(R.id.btnPreviousDay).setOnClickListener { taskViewModel.previousDay() }
        findViewById<View>(R.id.btnNextDay).setOnClickListener { taskViewModel.nextDay() }
        findViewById<View>(R.id.btnAddTask).setOnClickListener { showTaskEditor(null) }
        findViewById<View>(R.id.navHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.navProfile).setOnClickListener { showProfile() }
        findViewById<View>(R.id.txtSelectedDate).setOnClickListener { showWeekCalendar() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.txtSelectedDate).text = taskViewModel.date.value
        renderTasks(taskViewModel.tasks.value)
    }

    private fun renderTasks(tasks: List<ApiTask>) {
        if (screen != Screen.HOME) return
        val list = findViewById<LinearLayout?>(R.id.taskList) ?: return
        list.removeAllViews(); findViewById<TextView>(R.id.txtEmptyTasks).visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        val done = tasks.count { it.status.equals("done", true) }
        findViewById<TextView>(R.id.txtProgress).text = "Hoàn thành $done/${tasks.size} công việc trong ngày"
        findViewById<ProgressBar>(R.id.progressDay).apply { max = tasks.size.coerceAtLeast(1); progress = done }
        tasks.sortedBy { it.startTime }.forEach { task ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 12, 8, 12); setBackgroundColor(getColor(R.color.zp_surface)) }
            row.addView(TextView(this).apply { text = "${task.startTime} - ${task.endTime}\n${task.title}\n${task.description}\nƯu tiên: ${task.priority} · ${task.status}"; textSize = 15f; setTextColor(getColor(R.color.zp_text)) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "Sửa"; setOnClickListener { showTaskEditor(task) } })
            row.addView(Button(this).apply { text = "Xóa"; setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle("Xóa công việc?").setMessage(task.title).setNegativeButton("Hủy", null).setPositiveButton("Xóa") { _, _ -> taskViewModel.delete(task) }.show() } })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        }
    }

    private fun showWeekCalendar() {
        screen = Screen.CALENDAR; setContentView(R.layout.calendar_week); renderCalendar(taskViewModel.tasks.value)
        findViewById<View>(R.id.btnCalendarBack).setOnClickListener { showHome() }
    }
    private fun renderCalendar(tasks: List<ApiTask>) {
        if (screen != Screen.CALENDAR) return
        val selected = LocalDate.parse(taskViewModel.date.value); val start = selected.with(DayOfWeek.MONDAY)
        findViewById<TextView?>(R.id.txtWeekRange)?.text = "$start – ${start.plusDays(6)}"
        findViewById<TextView?>(R.id.txtCalendarDate)?.text = "Công việc ngày ${taskViewModel.date.value}"
        val days = findViewById<LinearLayout?>(R.id.weekDays) ?: return; days.removeAllViews()
        repeat(7) { i -> val day = start.plusDays(i.toLong()); days.addView(Button(this).apply { text = "${day.dayOfMonth}\n${day.dayOfWeek.name.take(3)}"; isAllCaps = false; setOnClickListener { taskViewModel.goTo(day) } }, LinearLayout.LayoutParams(0, -1, 1f)) }
        findViewById<LinearLayout?>(R.id.calendarTaskList)?.apply { removeAllViews(); tasks.sortedBy { it.startTime }.forEach { addView(TextView(this@MainActivity).apply { text = "${it.startTime}–${it.endTime}  ${it.title}\n${it.description}"; textSize = 16f; setTextColor(getColor(R.color.zp_text)); setPadding(8, 16, 8, 16) }) } }
    }

    private fun showTaskEditor(existing: ApiTask?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_task, null); val title = view.findViewById<EditText>(R.id.edtTaskTitle); val description = view.findViewById<EditText>(R.id.edtTaskDescription); val date = view.findViewById<EditText>(R.id.edtTaskDate); val start = view.findViewById<EditText>(R.id.edtTaskStart); val end = view.findViewById<EditText>(R.id.edtTaskEnd); val priority = view.findViewById<Spinner>(R.id.spinnerPriority); val status = view.findViewById<Spinner>(R.id.spinnerStatus)
        val priorities = listOf("low", "medium", "high"); val statuses = listOf("todo", "in_progress", "done"); priority.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, priorities); status.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, statuses)
        title.setText(existing?.title.orEmpty()); description.setText(existing?.description.orEmpty()); date.setText(existing?.date ?: taskViewModel.date.value); start.setText(existing?.startTime ?: "09:00"); end.setText(existing?.endTime ?: "10:00"); priority.setSelection(priorities.indexOf(existing?.priority).coerceAtLeast(0)); status.setSelection(statuses.indexOf(existing?.status).coerceAtLeast(0))
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Thêm công việc" else "Sửa công việc").setView(view).setNegativeButton("Hủy", null).setPositiveButton("Lưu", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val d = runCatching { LocalDate.parse(date.text.toString()) }.getOrNull(); val s = runCatching { LocalTime.parse(start.text.toString()) }.getOrNull(); val e = runCatching { LocalTime.parse(end.text.toString()) }.getOrNull()
            if (title.text.isNullOrBlank() || d == null || s == null || e == null || !s.isBefore(e)) { Toast.makeText(this, "Kiểm tra tiêu đề, ngày và thời gian.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val overlap = taskViewModel.tasks.value.any { it.id != existing?.id && it.date == d.toString() && s < LocalTime.parse(it.endTime) && e > LocalTime.parse(it.startTime) }
            val save = { taskViewModel.save(ApiTask(existing?.id ?: 0, title.text.toString().trim(), description.text.toString().trim(), d.toString(), s.toString(), e.toString(), priority.selectedItem.toString(), status.selectedItem.toString())); dialog.dismiss() }
            if (overlap) AlertDialog.Builder(this).setTitle("Thời gian bị trùng").setMessage("Bạn vẫn muốn lưu công việc này?").setNegativeButton("Hủy", null).setPositiveButton("Lưu dù trùng") { _, _ -> save() }.show() else save()
        } }; dialog.show()
    }

    private fun showSettings() { val v = LayoutInflater.from(this).inflate(R.layout.dialog_cai_dat, null); val dark = v.findViewById<Switch>(R.id.switchDarkMode); val reminders = v.findViewById<Switch>(R.id.switchReminder); dark.isChecked = settings.getBoolean("dark_mode", false); reminders.isChecked = settings.getBoolean("reminders", true); AlertDialog.Builder(this).setView(v).create().also { d -> dark.setOnCheckedChangeListener { _, value -> settings.edit().putBoolean("dark_mode", value).apply(); d.dismiss(); recreate() }; reminders.setOnCheckedChangeListener { _, value -> settings.edit().putBoolean("reminders", value).apply() }; v.findViewById<Button>(R.id.btnCloseSettings).setOnClickListener { d.dismiss() }; d.show() } }

    private fun showProfile() { screen = Screen.PROFILE; setContentView(R.layout.trangcanhan); findViewById<TextView>(R.id.txtProfileName).text = settings.getString("profile_name", "Lục Bảo"); findViewById<TextView>(R.id.txtProfileEmail).text = settings.getString("profile_email", "").orEmpty().ifBlank { "Tài khoản ZPlanner" }; findViewById<View>(R.id.btnLogoutProfile).setOnClickListener { showLogout() }; findViewById<View>(R.id.navHome).setOnClickListener { showHome() }; findViewById<View>(R.id.btnEditProfile).setOnClickListener { showProfileEditor() } }
    private fun showProfileEditor() { val v = LayoutInflater.from(this).inflate(R.layout.dialog_chinhsua_hoso, null); val name = v.findViewById<EditText>(R.id.edtProfileName); name.setText(settings.getString("profile_name", "Lục Bảo")); AlertDialog.Builder(this).setView(v).create().also { d -> v.findViewById<Button>(R.id.btnCancelProfile).setOnClickListener { d.dismiss() }; v.findViewById<Button>(R.id.btnSaveProfile).setOnClickListener { if (name.text.isNullOrBlank()) { name.error = "Nhập họ tên" } else { settings.edit().putString("profile_name", name.text.toString().trim()).apply(); d.dismiss(); showProfile() } }; d.show() } }
    private fun showLogout() { val v = LayoutInflater.from(this).inflate(R.layout.dialog_xac_nhan_dang_xuat, null); AlertDialog.Builder(this).setView(v).create().also { d -> v.findViewById<Button>(R.id.btnCancelLogout).setOnClickListener { d.dismiss() }; v.findViewById<Button>(R.id.btnConfirmLogout).setOnClickListener { d.dismiss(); auth.signOut() }; d.show() } }
}
