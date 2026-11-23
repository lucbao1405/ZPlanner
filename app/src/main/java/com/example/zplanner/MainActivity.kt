package com.example.zplanner

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.util.Base64
import android.util.AtomicFile
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.zplanner.data.ApiTask
import com.example.zplanner.data.ApiEvent
import com.example.zplanner.data.ZPlannerApi
import com.example.zplanner.data.ResetCodeRequest
import com.example.zplanner.data.ResetPasswordRequest
import com.example.zplanner.data.ProfilePatch
import com.example.zplanner.data.UserProfile
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.SecureRandom
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var api: ZPlannerApi
    @Inject lateinit var tokenStore: com.example.zplanner.data.TokenStore
    private lateinit var auth: AuthViewModel
    private lateinit var taskViewModel: TaskViewModel
    private lateinit var eventViewModel: EventViewModel
    private lateinit var reminderScheduler: ReminderScheduler
    private var reminderSyncJob: Job? = null
    private var notificationPermissionRequested = false
    private var screen = Screen.LOGIN
    private var profileEditorAvatar: ImageView? = null
    private var profileRevision = 0L
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { loadAvatarBitmap(contentResolver, uri) }.getOrNull() }
            if (screen != Screen.PROFILE) return@launch
            if (bitmap == null) Toast.makeText(this@MainActivity, R.string.avatar_load_error, Toast.LENGTH_SHORT).show()
            else showAvatarCrop(bitmap)
        }
    }
    private var authUiJob: kotlinx.coroutines.Job? = null
    private var activeEventDialog: AlertDialog? = null
    private var activeEventSaveButton: Button? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private var swipeConsumed = false
    private enum class Screen { LOGIN, REGISTER, HOME, CALENDAR, PROFILE }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Handle swipes at the Activity boundary so a completed swipe cannot
        // also activate the button that received the initial ACTION_DOWN.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.x
            swipeStartY = event.y
            swipeTracking = screen == Screen.HOME || screen == Screen.CALENDAR || screen == Screen.PROFILE
            swipeConsumed = false
        } else if (swipeTracking && !swipeConsumed && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.x - swipeStartX
            val dy = event.y - swipeStartY
            val threshold = 100f * resources.displayMetrics.density
            if (kotlin.math.abs(dx) >= threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                swipeConsumed = true
                if (hasWindowFocus()) showAdjacentScreen(if (dx < 0) 1 else -1)
                return true
            }
        } else if (swipeConsumed && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            if (event.actionMasked != MotionEvent.ACTION_MOVE) swipeTracking = false
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swipeTracking = false
        return super.dispatchTouchEvent(event)
    }

    private fun showAdjacentScreen(direction: Int) {
        when (screen) {
            Screen.HOME -> if (direction > 0) showWeekCalendar()
            Screen.CALENDAR -> if (direction > 0) showProfile() else showHome()
            Screen.PROFILE -> if (direction < 0) showWeekCalendar()
            Screen.LOGIN, Screen.REGISTER -> Unit
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val darkMode = newBase.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark_mode", false)
        val override = Configuration().apply {
            uiMode = if (darkMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = ViewModelProvider(this)[AuthViewModel::class.java]
        taskViewModel = ViewModelProvider(this)[TaskViewModel::class.java]
        eventViewModel = ViewModelProvider(this)[EventViewModel::class.java]
        reminderScheduler = ReminderScheduler(this)
        reminderScheduler.ensureChannel()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { auth.accountGeneration.collect { _ ->
                    taskViewModel.clearData()
                    eventViewModel.clearData()
                    if (auth.signedIn.value) {
                        taskViewModel.load()
                        eventViewModel.loadWeek(LocalDate.parse(taskViewModel.date.value))
                    }
                } }
                launch { auth.signedIn.collect { signedIn ->
                    if (signedIn && (screen == Screen.LOGIN || screen == Screen.REGISTER)) { taskViewModel.clearData(); eventViewModel.clearData(); taskViewModel.load(); showHome() }
                    else if (!signedIn) { reminderSyncJob?.cancel(); reminderScheduler.cancelAll(); taskViewModel.clearData(); eventViewModel.clearData(); if (screen != Screen.LOGIN && screen != Screen.REGISTER) showLogin() }
                    if (signedIn) syncReminders()
                    if (signedIn && Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionRequested = true
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } }
                launch { taskViewModel.sessionExpired.collect { expired -> if (expired) { taskViewModel.clearSessionExpiry(); auth.signOut() } } }
                launch { eventViewModel.sessionExpired.collect { expired -> if (expired) { eventViewModel.sessionExpired.value = false; auth.signOut() } } }
                launch { taskViewModel.tasks.collect { renderTasks(it); renderCalendar(it); syncReminders() } }
                launch { eventViewModel.events.collect { renderCalendar(taskViewModel.tasks.value); syncReminders() } }
                launch { eventViewModel.overlap.collect { overlap -> if (overlap != null) showEventOverlap(overlap) } }
                launch { eventViewModel.error.collect { error -> if (error != null) Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show() } }
                launch {
                    taskViewModel.date.collect { date ->
                        findViewById<TextView?>(R.id.txtSelectedDate)?.text = date
                        val selectedDate = LocalDate.parse(date)
                        renderCalendar(taskViewModel.tasks.value)
                        eventViewModel.loadWeek(selectedDate)
                    }
                }
                launch { taskViewModel.loading.collect { loading -> findViewById<ProgressBar?>(R.id.progressTasks)?.visibility = if (loading) View.VISIBLE else View.GONE } }
                launch { taskViewModel.error.collect { error -> findViewById<TextView?>(R.id.txtTaskError)?.apply { text = error.orEmpty(); visibility = if (error == null) View.GONE else View.VISIBLE } } }
            }
        }
        when {
            !auth.signedIn.value -> showLogin()
            savedInstanceState?.getBoolean("show_profile") == true -> showProfile()
            else -> showHome()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("show_profile", screen == Screen.PROFILE)
    }

    override fun onBackPressed() {
        when (screen) {
            Screen.LOGIN -> super.onBackPressed()
            Screen.REGISTER -> showLogin()
            Screen.HOME -> super.onBackPressed()
            Screen.CALENDAR, Screen.PROFILE -> showHome()
        }
    }

    private fun syncReminders() {
        reminderSyncJob?.cancel()
        if (!auth.signedIn.value || !settings.getBoolean("reminders", true)) {
            reminderScheduler.cancelAll()
            return
        }
        val account = authEmail()
        reminderSyncJob = lifecycleScope.launch {
            try {
                val tasks = api.reminderTasks(LocalDate.now().toString(), "9999-12-31")
                val events = api.reminderEvents()
                if (tasks.isSuccessful && events.isSuccessful && auth.signedIn.value && authEmail() == account && settings.getBoolean("reminders", true)) {
                    reminderScheduler.schedule(tasks.body()?.tasks.orEmpty(), events.body()?.events.orEmpty(), account, true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    private fun authEmail(): String = authEmailStore()

    private fun authEmailStore(): String = tokenStore.readEmail().trim()

    private fun showLogin() {
        // Stop collectors while they still point at the previous layout. Otherwise a
        // final emission from the old screen can update views that are no longer shown.
        authUiJob?.cancel()
        authUiJob = null
        auth.error.value = null
        screen = Screen.LOGIN
        setContentView(R.layout.dangnhap)
        val email = findViewById<EditText>(R.id.edtEmail); val password = findViewById<EditText>(R.id.edtPassword)
        val login = findViewById<Button>(R.id.btnLogin); val progress = findViewById<ProgressBar>(R.id.progressLogin)
        val error = findViewById<TextView>(R.id.txtPasswordError)
        findViewById<View>(R.id.btnTogglePassword).setOnClickListener { togglePassword(password) }
        findViewById<View>(R.id.btnRegister).setOnClickListener { showRegister() }
        findViewById<View>(R.id.btnForgotPassword).setOnClickListener { showForgotPassword() }
        findViewById<View>(R.id.btnGoogle).setOnClickListener { startGoogleSignIn(link = false) }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                login.performClick()
                true
            } else false
        }
        login.setOnClickListener {
            val e = email.text.toString().trim(); val p = password.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) { error.text = "Vui lòng nhập email hợp lệ."; error.visibility = View.VISIBLE; return@setOnClickListener }
            if (p.isBlank()) { error.text = "Vui lòng nhập mật khẩu."; error.visibility = View.VISIBLE; return@setOnClickListener }
            error.visibility = View.GONE; auth.submitLogin(e, p)
        }
        authUiJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { auth.loading.collect { loading -> if (screen == Screen.LOGIN) { progress.visibility = if (loading) View.VISIBLE else View.GONE; login.isEnabled = !loading } } }
                launch { auth.error.collect { message -> if (screen == Screen.LOGIN) { error.text = message.orEmpty(); error.visibility = if (message == null) View.GONE else View.VISIBLE } } }
            }
        }
    }

    private fun togglePassword(password: EditText) {
        val visible = password.transformationMethod !is android.text.method.HideReturnsTransformationMethod
        val selection = password.selectionStart.coerceIn(0, password.length())
        password.transformationMethod = if (visible) {
            android.text.method.HideReturnsTransformationMethod.getInstance()
        } else {
            android.text.method.PasswordTransformationMethod.getInstance()
        }
        password.setSelection(selection)
    }

    private fun showRegister() {
        // Cancel the login collectors before replacing its views. Register owns its
        // own collector and therefore receives loading/errors only for this screen.
        authUiJob?.cancel()
        authUiJob = null
        auth.error.value = null
        screen = Screen.REGISTER
        setContentView(R.layout.dangky)
        val email = findViewById<EditText>(R.id.edtRegisterEmail)
        val password = findViewById<EditText>(R.id.edtRegisterPassword)
        val confirm = findViewById<EditText>(R.id.edtRegisterConfirmPassword)
        val submit = findViewById<Button>(R.id.btnRegisterSubmit)
        val progress = findViewById<ProgressBar>(R.id.progressRegister)
        val emailError = findViewById<TextView>(R.id.txtRegisterEmailError)
        val passwordError = findViewById<TextView>(R.id.txtRegisterPasswordError)
        val confirmError = findViewById<TextView>(R.id.txtRegisterConfirmError)
        findViewById<View>(R.id.btnToggleRegisterPassword).setOnClickListener { togglePassword(password) }
        findViewById<View>(R.id.btnToggleRegisterConfirmPassword).setOnClickListener { togglePassword(confirm) }
        findViewById<View>(R.id.btnBackToLogin).setOnClickListener { showLogin() }
        submit.setOnClickListener {
            emailError.visibility = View.GONE; passwordError.visibility = View.GONE; confirmError.visibility = View.GONE
            val e = email.text.toString().trim(); val p = password.text.toString(); val c = confirm.text.toString()
            var valid = true
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) { emailError.text = "Email không hợp lệ."; emailError.visibility = View.VISIBLE; valid = false }
            if (p.length !in 8..128) { passwordError.text = "Mật khẩu phải dài từ 8 đến 128 ký tự."; passwordError.visibility = View.VISIBLE; valid = false }
            if (p != c) { confirmError.text = "Mật khẩu nhập lại không trùng khớp."; confirmError.visibility = View.VISIBLE; valid = false }
            if (valid) auth.submitRegister(e, p)
        }
        authUiJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { auth.loading.collect { loading -> progress.visibility = if (loading) View.VISIBLE else View.GONE; submit.isEnabled = !loading } }
                launch {
                    auth.error.collect { message ->
                        emailError.text = message.orEmpty()
                        emailError.visibility = if (message == null) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }


    private fun startGoogleSignIn(link: Boolean) {
        lifecycleScope.launch {
            val nonceBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(nonce).build()
            try {
                val result = credentialManager.getCredential(this@MainActivity, GetCredentialRequest.Builder().addCredentialOption(option).build())
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val google = GoogleIdTokenCredential.createFrom(credential.data)
                    if (link) auth.linkGoogle(google.idToken, nonce) else auth.googleSignIn(google.idToken, nonce)
                } else Toast.makeText(this@MainActivity, getString(R.string.google_signin_failed), Toast.LENGTH_LONG).show()
            } catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) {
                if (link) auth.cancelPendingGoogleLink()
                Toast.makeText(this@MainActivity, getString(R.string.google_signin_cancelled), Toast.LENGTH_SHORT).show()
            } catch (_: GoogleIdTokenParsingException) {
                Toast.makeText(this@MainActivity, getString(R.string.google_signin_failed), Toast.LENGTH_LONG).show()
            } catch (_: androidx.credentials.exceptions.GetCredentialException) {
                Toast.makeText(this@MainActivity, getString(R.string.google_signin_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHome() {
        screen = Screen.HOME
        authUiJob?.cancel()
        setContentView(R.layout.trangchu)
        findViewById<View>(R.id.btnPreviousDay).setOnClickListener { taskViewModel.previousDay() }
        findViewById<View>(R.id.btnNextDay).setOnClickListener { taskViewModel.nextDay() }
        findViewById<View>(R.id.btnAddTask).setOnClickListener { showTaskEditor(null) }
        findViewById<View>(R.id.btnAddEvent).setOnClickListener { showEventEditor(null) }
        findViewById<View>(R.id.navHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.navCalendar).setOnClickListener { showWeekCalendar() }
        findViewById<View>(R.id.navProfile).setOnClickListener { showProfile() }
        findViewById<View>(R.id.txtSelectedDate).setOnClickListener { showWeekCalendar() }
        findViewById<TextView>(R.id.txtSelectedDate).text = taskViewModel.date.value
        renderTasks(taskViewModel.tasks.value)
        eventViewModel.loadWeek(LocalDate.parse(taskViewModel.date.value))
    }

    private fun renderTasks(tasks: List<ApiTask>) {
        if (screen != Screen.HOME) return
        val list = findViewById<LinearLayout?>(R.id.taskList) ?: return
        list.removeAllViews()
        val emptyView = findViewById<TextView?>(R.id.txtEmptyTasks)
        emptyView?.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE

        val done = tasks.count { it.status.equals("done", true) }
        findViewById<TextView?>(R.id.txtProgress)?.text = "Hoàn thành $done/${tasks.size} công việc trong ngày"
        findViewById<ProgressBar?>(R.id.progressDay)?.apply { max = tasks.size.coerceAtLeast(1); progress = done }

        tasks.sortedBy { it.startTime }.forEach { task ->
            list.addView(createTaskCardView(task, list))
        }
    }

    private fun createTaskCardView(task: ApiTask, parent: android.view.ViewGroup? = null): View {
        val view = layoutInflater.inflate(R.layout.item_task, parent, false)
        view.findViewById<TextView>(R.id.txtTaskTime).text = "⏰ ${task.startTime} - ${task.endTime}"

        val priorityTv = view.findViewById<TextView>(R.id.txtTaskPriority)
        val (pBg, pColor, pText) = when (task.priority.lowercase()) {
            "high" -> Triple(R.drawable.bg_badge_high, R.color.zp_priority_high_text, "Cao")
            "medium" -> Triple(R.drawable.bg_badge_medium, R.color.zp_priority_medium_text, "Vừa")
            else -> Triple(R.drawable.bg_badge_low, R.color.zp_priority_low_text, "Thấp")
        }
        priorityTv.text = pText
        priorityTv.setTextColor(getColor(pColor))
        priorityTv.setBackgroundResource(pBg)

        val statusTv = view.findViewById<TextView>(R.id.txtTaskStatus)
        val (sBg, sColor, sText) = when (task.status.lowercase()) {
            "done" -> Triple(R.drawable.bg_badge_done, R.color.zp_status_done_text, "✓ Hoàn thành")
            "in_progress" -> Triple(R.drawable.bg_badge_in_progress, R.color.zp_status_in_progress_text, "⚡ Đang làm")
            else -> Triple(R.drawable.bg_badge_todo, R.color.zp_status_todo_text, "○ Cần làm")
        }
        statusTv.text = sText
        statusTv.setTextColor(getColor(sColor))
        statusTv.setBackgroundResource(sBg)

        val titleTv = view.findViewById<TextView>(R.id.txtTaskTitle)
        titleTv.text = task.title
        if (task.status.equals("done", true)) {
            titleTv.paintFlags = titleTv.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }

        val descTv = view.findViewById<TextView>(R.id.txtTaskDescription)
        if (task.description.isNotBlank()) {
            descTv.text = task.description
            descTv.visibility = View.VISIBLE
        } else {
            descTv.visibility = View.GONE
        }

        view.findViewById<ImageButton>(R.id.btnEditTask).setOnClickListener { showTaskEditor(task) }
        view.findViewById<ImageButton>(R.id.btnDeleteTask).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa công việc?")
                .setMessage("Bạn có chắc muốn xóa \"${task.title}\"?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa") { _, _ -> taskViewModel.delete(task) }
                .show()
        }

        return view
    }

    private fun createEventCardView(event: ApiEvent, parent: android.view.ViewGroup? = null): View {
        val view = layoutInflater.inflate(R.layout.item_event, parent, false)
        view.findViewById<TextView>(R.id.txtEventTime).text = "📅 ${localDateTime(event.startAt)}"
        view.findViewById<TextView>(R.id.txtEventTitle).text = event.title

        val descTv = view.findViewById<TextView>(R.id.txtEventDescription)
        if (event.description.isNotBlank()) {
            descTv.text = event.description
            descTv.visibility = View.VISIBLE
        } else {
            descTv.visibility = View.GONE
        }

        val locTv = view.findViewById<TextView>(R.id.txtEventLocation)
        if (event.location.isNotBlank()) {
            locTv.text = "📍 ${event.location}"
            locTv.visibility = View.VISIBLE
        } else {
            locTv.visibility = View.GONE
        }

        view.findViewById<ImageButton>(R.id.btnEditEvent).setOnClickListener { showEventEditor(event) }
        view.findViewById<ImageButton>(R.id.btnDeleteEvent).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa sự kiện?")
                .setMessage("Bạn có chắc muốn xóa \"${event.title}\"?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa") { _, _ -> eventViewModel.delete(event) }
                .show()
        }

        return view
    }

    private fun formatDayName(dayOfWeek: DayOfWeek): String {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY -> "T2"
            DayOfWeek.TUESDAY -> "T3"
            DayOfWeek.WEDNESDAY -> "T4"
            DayOfWeek.THURSDAY -> "T5"
            DayOfWeek.FRIDAY -> "T6"
            DayOfWeek.SATURDAY -> "T7"
            DayOfWeek.SUNDAY -> "CN"
        }
    }

    private fun showWeekCalendar() {
        screen = Screen.CALENDAR
        setContentView(R.layout.calendar_week)
        findViewById<View>(R.id.btnCalendarBack).setOnClickListener { showHome() }
        findViewById<View>(R.id.navHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.navCalendar).setOnClickListener { showWeekCalendar() }
        findViewById<View>(R.id.navProfile).setOnClickListener { showProfile() }
        val selectedDate = LocalDate.parse(taskViewModel.date.value)
        eventViewModel.loadWeek(selectedDate)
        renderCalendar(taskViewModel.tasks.value)
    }

    private fun renderCalendar(tasks: List<ApiTask>) {
        if (screen != Screen.CALENDAR) return
        val selected = LocalDate.parse(taskViewModel.date.value)
        val start = selected.with(DayOfWeek.MONDAY)
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM")
        findViewById<TextView?>(R.id.txtWeekRange)?.text = "${start.format(dateFormatter)} – ${start.plusDays(6).format(dateFormatter)}"
        findViewById<TextView?>(R.id.txtCalendarDate)?.text = "Lịch ngày ${selected.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"

        val days = findViewById<LinearLayout?>(R.id.weekDays) ?: return
        days.removeAllViews()

        repeat(7) { i ->
            val day = start.plusDays(i.toLong())
            val isSelected = day == selected
            val btn = Button(this).apply {
                text = "${formatDayName(day.dayOfWeek)}\n${day.dayOfMonth}"
                isAllCaps = false
                textSize = 12f
                setPadding(0, 4, 0, 4)
                if (isSelected) {
                    setBackgroundResource(R.drawable.bg_day_chip_selected)
                    setTextColor(getColor(R.color.zp_on_primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    setBackgroundResource(R.drawable.bg_day_chip_normal)
                    setTextColor(getColor(R.color.zp_text))
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                setOnClickListener { taskViewModel.goTo(day) }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = if (i < 6) 6 else 0
            }
            days.addView(btn, params)
        }

        findViewById<LinearLayout?>(R.id.calendarTaskList)?.apply {
            removeAllViews()
            val eventsList = eventViewModel.events.value
                .filter { eventIntersectsLocalDate(it, selected) }
                .sortedBy { localDateTime(it.startAt) }
            if (tasks.isEmpty() && eventsList.isEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "Không có công việc hoặc sự kiện nào trong ngày này."
                    textSize = 14f
                    setTextColor(getColor(R.color.zp_muted))
                    setPadding(16, 32, 16, 32)
                    gravity = android.view.Gravity.CENTER
                })
            }
            tasks.sortedBy { it.startTime }.forEach { addView(createTaskCardView(it, this)) }
            eventsList.sortedBy { it.startAt }.forEach { addView(createEventCardView(it, this)) }
        }
    }

    private fun createCustomDialog(view: View): AlertDialog {
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    private fun showTaskEditor(existing: ApiTask?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_task, null)
        val title = view.findViewById<EditText>(R.id.edtTaskTitle)
        val description = view.findViewById<EditText>(R.id.edtTaskDescription)
        val date = view.findViewById<EditText>(R.id.edtTaskDate)
        val start = view.findViewById<EditText>(R.id.edtTaskStart)
        val end = view.findViewById<EditText>(R.id.edtTaskEnd)
        val priority = view.findViewById<Spinner>(R.id.spinnerPriority)
        val status = view.findViewById<Spinner>(R.id.spinnerStatus)

        val priorities = listOf("low", "medium", "high")
        val priorityLabels = listOf("Mức ưu tiên: Thấp", "Mức ưu tiên: Trung bình", "Mức ưu tiên: Cao")
        val statuses = listOf("todo", "in_progress", "done")
        val statusLabels = listOf("Trạng thái: Cần làm", "Trạng thái: Đang làm", "Trạng thái: Hoàn thành")

        priority.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, priorityLabels)
        status.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, statusLabels)

        title.setText(existing?.title.orEmpty())
        description.setText(existing?.description.orEmpty())
        date.setText(existing?.date ?: taskViewModel.date.value)
        start.setText(existing?.startTime ?: "09:00")
        end.setText(existing?.endTime ?: "10:00")
        priority.setSelection(priorities.indexOf(existing?.priority).coerceAtLeast(0))
        status.setSelection(statuses.indexOf(existing?.status).coerceAtLeast(0))

        setupPickerClick(date) { showDatePicker(date) }
        setupPickerClick(start) { showTimePicker(start) }
        setupPickerClick(end) { showTimePicker(end) }

        val dialog = createCustomDialog(view)

        view.findViewById<Button?>(R.id.btnCancelTask)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<Button?>(R.id.btnSaveTask)?.setOnClickListener {
            val saveButton = view.findViewById<Button>(R.id.btnSaveTask)
            if (!saveButton.isEnabled) return@setOnClickListener
            val d = runCatching { LocalDate.parse(date.text.toString()) }.getOrNull()
            val s = runCatching { LocalTime.parse(start.text.toString()) }.getOrNull()
            val e = runCatching { LocalTime.parse(end.text.toString()) }.getOrNull()
            if (title.text.isNullOrBlank() || d == null || s == null || e == null || !s.isBefore(e)) {
                Toast.makeText(this, "Vui lòng nhập tiêu đề, ngày (yyyy-MM-dd) và thời gian hợp lệ.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val selectedPriority = priorities.getOrElse(priority.selectedItemPosition) { "low" }
            val selectedStatus = statuses.getOrElse(status.selectedItemPosition) { "todo" }
            val overlap = taskViewModel.tasks.value.any { it.id != existing?.id && it.date == d.toString() && s < LocalTime.parse(it.endTime) && e > LocalTime.parse(it.startTime) }
            val save: () -> Unit = {
                saveButton.isEnabled = false
                taskViewModel.save(ApiTask(existing?.id ?: 0, title.text.toString().trim(), description.text.toString().trim(), d.toString(), s.toString(), e.toString(), selectedPriority, selectedStatus)) { success ->
                    if (success) dialog.dismiss() else saveButton.isEnabled = true
                }
            }
            if (overlap) {
                AlertDialog.Builder(this)
                    .setTitle("Thời gian bị trùng")
                    .setMessage("Đã có công việc khác trong khoảng thời gian này. Bạn vẫn muốn lưu?")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Lưu dù trùng") { _, _ -> save() }
                    .show()
            } else save()
        }
        dialog.show()
    }

    private fun setupPickerClick(editText: EditText, action: () -> Unit) {
        editText.isFocusable = false
        editText.isClickable = true
        editText.setOnClickListener { action() }
    }

    private fun showDatePicker(editText: EditText) {
        val current = runCatching { LocalDate.parse(editText.text.toString()) }.getOrDefault(LocalDate.now())
        android.app.DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = LocalDate.of(year, month + 1, dayOfMonth)
                editText.setText(selected.toString())
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth
        ).show()
    }

    private fun showTimePicker(editText: EditText) {
        val current = runCatching { LocalTime.parse(editText.text.toString()) }.getOrDefault(LocalTime.of(9, 0))
        android.app.TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val selected = LocalTime.of(hourOfDay, minute)
                editText.setText(selected.format(DateTimeFormatter.ofPattern("HH:mm")))
            },
            current.hour,
            current.minute,
            true
        ).show()
    }

    private fun localDateTime(value: String): String = runCatching { ZonedDateTime.parse(value).withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault(value)

    private fun eventIntersectsLocalDate(event: ApiEvent, date: LocalDate): Boolean {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val eventStart = runCatching { java.time.Instant.parse(event.startAt) }.getOrNull() ?: return false
        val eventEnd = runCatching { java.time.Instant.parse(event.endAt) }.getOrNull() ?: return false
        return eventStart < dayEnd && dayStart < eventEnd
    }

    private fun showEventEditor(existing: ApiEvent?) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_event, null)
        val date = v.findViewById<EditText>(R.id.edtEventDate)
        val start = v.findViewById<EditText>(R.id.edtEventStart)
        val end = v.findViewById<EditText>(R.id.edtEventEnd)
        val zone = ZoneId.systemDefault()
        val startLocal = existing?.let { ZonedDateTime.parse(it.startAt).withZoneSameInstant(zone) }
        val endLocal = existing?.let { ZonedDateTime.parse(it.endAt).withZoneSameInstant(zone) }

        v.findViewById<EditText>(R.id.edtEventTitle).setText(existing?.title.orEmpty())
        v.findViewById<EditText>(R.id.edtEventDescription).setText(existing?.description.orEmpty())
        v.findViewById<EditText>(R.id.edtEventLocation).setText(existing?.location.orEmpty())
        date.setText(startLocal?.toLocalDate()?.toString() ?: taskViewModel.date.value)
        start.setText(startLocal?.toLocalTime()?.toString()?.take(5) ?: "09:00")
        end.setText(endLocal?.toLocalTime()?.toString()?.take(5) ?: "10:00")
        v.findViewById<EditText>(R.id.edtEventReminder).setText(existing?.reminderMinutes?.toString() ?: "0")

        setupPickerClick(date) { showDatePicker(date) }
        setupPickerClick(start) { showTimePicker(start) }
        setupPickerClick(end) { showTimePicker(end) }

        val dialog = createCustomDialog(v)
        activeEventDialog = dialog
        val saveButton = v.findViewById<Button>(R.id.btnSaveEvent)
        activeEventSaveButton = saveButton
        v.findViewById<Button?>(R.id.btnCancelEvent)?.setOnClickListener { dialog.dismiss() }
        fun buildEvent(): ApiEvent {
            val day = LocalDate.parse(date.text.toString())
            val s = LocalTime.parse(start.text.toString())
            val e = LocalTime.parse(end.text.toString())
            if (!s.isBefore(e)) error("Thời gian bắt đầu phải trước thời gian kết thúc")
            return ApiEvent(existing?.id ?: 0, v.findViewById<EditText>(R.id.edtEventTitle).text.toString().trim(), v.findViewById<EditText>(R.id.edtEventDescription).text.toString(), v.findViewById<EditText>(R.id.edtEventLocation).text.toString(), ZonedDateTime.of(day, s, zone).toInstant().toApiTimestamp(), ZonedDateTime.of(day, e, zone).toInstant().toApiTimestamp(), v.findViewById<EditText>(R.id.edtEventReminder).text.toString().toIntOrNull() ?: 0)
        }
        fun submit(confirmOverlap: Boolean = false) {
            if (!saveButton.isEnabled) return
            saveButton.isEnabled = false
            eventViewModel.save(buildEvent(), confirmOverlap) { success ->
                if (success) {
                    activeEventDialog = null
                    activeEventSaveButton = null
                    dialog.dismiss()
                } else if (dialog.isShowing) {
                    saveButton.isEnabled = true
                }
            }
        }
        saveButton.setOnClickListener {
            runCatching { buildEvent() }.onSuccess { submit() }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        }
        dialog.show()
    }

    private fun showEventOverlap(events: List<ApiEvent>) {
        val inputDialog = activeEventDialog
        val saveButton = activeEventSaveButton
        val enabledBeforeConfirm = saveButton?.isEnabled ?: false
        val overlapDialog = AlertDialog.Builder(this)
            .setTitle("Sự kiện bị trùng")
            .setMessage(events.joinToString("\n") { "${it.title}: ${localDateTime(it.startAt)}" }.ifBlank { "Có sự kiện khác trong khoảng thời gian này." })
            .setNegativeButton("Hủy") { _, _ ->
                eventViewModel.clearOverlap()
                if (activeEventDialog === inputDialog && inputDialog?.isShowing == true) saveButton?.isEnabled = enabledBeforeConfirm
            }
            .setPositiveButton("Tiếp tục lưu", null)
            .create()
        overlapDialog.setOnShowListener {
            overlapDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val button = overlapDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                if (!button.isEnabled) return@setOnClickListener
                button.isEnabled = false
                if (activeEventDialog === inputDialog) saveButton?.isEnabled = false
                eventViewModel.confirmOverlap { success ->
                    if (success) {
                        if (activeEventDialog === inputDialog && inputDialog?.isShowing == true) inputDialog.dismiss()
                        if (activeEventDialog === inputDialog) {
                            activeEventDialog = null
                            activeEventSaveButton = null
                        }
                        overlapDialog.dismiss()
                    } else {
                        // The overlap dialog is only a retry prompt after a failed confirm.
                        // The input dialog stays open, with its current text intact.
                        overlapDialog.dismiss()
                        if (activeEventDialog === inputDialog && inputDialog?.isShowing == true) saveButton?.isEnabled = true
                    }
                }
            }
        }
        overlapDialog.setOnCancelListener {
            eventViewModel.clearOverlap()
            if (activeEventDialog === inputDialog && inputDialog?.isShowing == true) saveButton?.isEnabled = enabledBeforeConfirm
        }
        overlapDialog.show()
    }

    private fun showProfile() {
        screen = Screen.PROFILE
        setContentView(R.layout.trangcanhan)
        val email = authEmail()
        val customName = tokenStore.readProfileName(email)
        findViewById<TextView>(R.id.txtProfileName).text = customName ?: email
        findViewById<ImageView>(R.id.imgProfileAvatar).apply {
            clipToOutline = true
            val uri = tokenStore.readProfileAvatarUri(email)
            if (uri.isNullOrBlank()) setImageResource(R.drawable.ic_avatar_default) else setImageURI(Uri.parse(uri))
        }
        findViewById<TextView>(R.id.txtProfileEmail).apply {
            text = email
            visibility = if (customName != null && customName != email) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.btnProfileBack).setOnClickListener { showHome() }
        val darkMode = findViewById<Switch>(R.id.btnDarkMode)
        val reminders = findViewById<Switch>(R.id.btnReminders)
        darkMode.isChecked = settings.getBoolean("dark_mode", false)
        reminders.isChecked = settings.getBoolean("reminders", true)
        darkMode.setOnCheckedChangeListener { _, enabled ->
            settings.edit().putBoolean("dark_mode", enabled).apply()
            recreate()
        }
        reminders.setOnCheckedChangeListener { _, enabled ->
            settings.edit().putBoolean("reminders", enabled).apply()
            syncReminders()
        }
        val linkGoogle = findViewById<View>(R.id.btnLinkGoogleProfile)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                auth.googleLinkRequired.collect { linkGoogle.visibility = if (it) View.VISIBLE else View.GONE }
            }
        }
        linkGoogle.setOnClickListener { startGoogleSignIn(link = true) }
        findViewById<View>(R.id.btnLogoutProfile).setOnClickListener { showLogout() }
        findViewById<View>(R.id.navHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.navCalendar).setOnClickListener { showWeekCalendar() }
        findViewById<View>(R.id.navProfile).setOnClickListener { showProfile() }
        findViewById<View>(R.id.btnEditProfile).setOnClickListener { showProfileEditor() }
        findViewById<View>(R.id.btnHelp).setOnClickListener { showInfoDialog("Trung tâm trợ giúp", "Bạn có thể quản lý lịch, nhiệm vụ và cài đặt trong ứng dụng.") }
        findViewById<View>(R.id.btnAbout).setOnClickListener { showInfoDialog("Về ZPlanner", "ZPlanner v1.0\nỨng dụng lập kế hoạch cá nhân.") }
        loadProfileFromCloud(email)
    }

    private fun loadProfileFromCloud(email: String) {
        val session = tokenStore.read() ?: return
        val revision = ++profileRevision
        lifecycleScope.launch {
            val fetched = runCatching { api.profile() }.getOrNull()
            if (fetched?.isSuccessful != true || authEmail() != email || tokenStore.read() != session || revision != profileRevision) return@launch
            var profile = fetched.body()?.profile ?: return@launch
            val localName = tokenStore.readProfileName(email)
            val localUri = tokenStore.readProfileAvatarUri(email)?.takeIf { it.isNotBlank() }
            val migrateName = profile.displayName == null && !localName.isNullOrBlank()
            val migrateAvatar = profile.avatarBase64 == null && localUri != null
            if (migrateName || migrateAvatar) {
                val localAvatar = if (migrateAvatar) withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = loadAvatarBitmap(contentResolver, Uri.parse(localUri))
                            ?: error("Unreadable avatar")
                        try { encodeProfileAvatar(bitmap) } finally { bitmap.recycle() }
                    }.getOrNull()
                } else null
                if (!migrateName && localAvatar == null) return@launch
                val patched = runCatching {
                    api.updateProfile(ProfilePatch(
                        displayName = localName.takeIf { migrateName },
                        avatarBase64 = localAvatar
                    ))
                }.getOrNull()
                if (patched?.isSuccessful != true) return@launch
                profile = patched.body()?.profile ?: return@launch
            }
            if (authEmail() != email || tokenStore.read() != session || revision != profileRevision) return@launch
            applyCloudProfile(email, profile)
        }
    }

    private suspend fun applyCloudProfile(email: String, profile: UserProfile) {
        tokenStore.saveProfileName(email, profile.displayName)
        val avatarUri = profile.avatarBase64?.let { encoded ->
            withContext(Dispatchers.IO) {
                runCatching { saveProfileAvatarFile(email, Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
            }
        }
        if (avatarUri != null) tokenStore.saveProfileAvatarUri(email, avatarUri.toString())
        if (screen != Screen.PROFILE || authEmail() != email) return
        findViewById<TextView>(R.id.txtProfileName)?.text = profile.displayName ?: email
        findViewById<TextView>(R.id.txtProfileEmail)?.apply {
            text = email
            visibility = if (profile.displayName == null) View.GONE else View.VISIBLE
        }
        if (avatarUri != null) findViewById<ImageView>(R.id.imgProfileAvatar)?.apply {
            setImageDrawable(null)
            setImageURI(avatarUri)
        }
    }

    private fun encodeProfileAvatar(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        try {
            for (quality in listOf(75, 60, 45, 30)) {
                val output = ByteArrayOutputStream()
                if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= 39_000) {
                    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                }
            }
            throw IllegalArgumentException("Avatar is too large")
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun saveProfileAvatarFile(email: String, bytes: ByteArray): Uri {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        check(decoded != null)
        decoded.recycle()
        val avatar = AtomicFile(File(filesDir, "profile-avatar-${tokenStore.profileKey(email)}.jpg"))
        val stream = avatar.startWrite()
        try {
            stream.write(bytes)
            avatar.finishWrite(stream)
        } catch (error: Exception) {
            avatar.failWrite(stream)
            throw error
        }
        return Uri.fromFile(avatar.baseFile)
    }

    private fun showForgotPassword() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_quen_mat_khau, null)
        val email = v.findViewById<EditText>(R.id.edtResetEmail)
        val dialog = createCustomDialog(v)
        v.findViewById<Button>(R.id.btnCancelReset).setOnClickListener { dialog.dismiss() }
        val send = v.findViewById<Button>(R.id.btnSendReset)
        send.setOnClickListener {
            val address = email.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                email.error = "Nhập email hợp lệ"
            } else {
                send.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val response = api.requestResetCode(ResetCodeRequest(address))
                        if (response.isSuccessful) {
                            dialog.dismiss()
                            showResetCodeDialog(address)
                        } else Toast.makeText(this@MainActivity, "Không thể gửi mã (HTTP ${response.code()}).", Toast.LENGTH_LONG).show()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { Toast.makeText(this@MainActivity, "Không thể kết nối máy chủ.", Toast.LENGTH_LONG).show() }
                    finally { if (dialog.isShowing) send.isEnabled = true }
                }
            }
        }
        dialog.show()
    }

    private fun showResetCodeDialog(email: String) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_nhap_ma_dat_lai, null)
        val code = v.findViewById<EditText>(R.id.edtResetCode)
        val password = v.findViewById<EditText>(R.id.edtResetNewPassword)
        val confirm = v.findViewById<EditText>(R.id.edtResetConfirmPassword)
        val dialog = createCustomDialog(v)
        val submit = v.findViewById<Button>(R.id.btnConfirmResetCode)
        v.findViewById<Button>(R.id.btnCancelResetCode).setOnClickListener { dialog.dismiss() }
        submit.setOnClickListener {
            val codeValue = code.text.toString()
            val newPassword = password.text.toString()
            if (!codeValue.matches(Regex("\\d{8}"))) { code.error = "Nhập mã 8 số"; return@setOnClickListener }
            if (newPassword.length !in 8..128) { password.error = "Mật khẩu cần 8–128 ký tự"; return@setOnClickListener }
            if (newPassword != confirm.text.toString()) { confirm.error = "Mật khẩu nhập lại không khớp"; return@setOnClickListener }
            submit.isEnabled = false
            lifecycleScope.launch {
                try {
                    val response = api.confirmPasswordReset(ResetPasswordRequest(email, codeValue, newPassword))
                    if (response.isSuccessful) {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Đã đổi mật khẩu. Hãy đăng nhập lại.", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this@MainActivity, if (response.code() == 400) "Mã không đúng, đã hết hạn hoặc đã dùng." else "Không thể đổi mật khẩu (HTTP ${response.code()}).", Toast.LENGTH_LONG).show()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "Không thể kết nối máy chủ.", Toast.LENGTH_LONG).show() }
                finally { if (dialog.isShowing) submit.isEnabled = true }
            }
        }
        dialog.show()
    }

    private fun showInfoDialog(title: String, message: String) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Đóng", null).create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        dialog.show()
    }

    private fun showProfileEditor() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_chinhsua_hoso, null)
        val name = v.findViewById<EditText>(R.id.edtProfileName)
        profileEditorAvatar = v.findViewById<ImageView>(R.id.imgProfileAvatarPreview).apply { clipToOutline = true }
        val email = authEmail()
        name.setText(tokenStore.readProfileName(email) ?: email)
        tokenStore.readProfileAvatarUri(email)?.takeIf { it.isNotBlank() }?.let { profileEditorAvatar?.setImageURI(Uri.parse(it)) }
            ?: profileEditorAvatar?.setImageResource(R.drawable.ic_avatar_default)
        val dialog = createCustomDialog(v)
        v.findViewById<View>(R.id.btnChangeAvatar).setOnClickListener {
            avatarPicker.launch(arrayOf("image/*"))
        }
        v.findViewById<Button>(R.id.btnCancelProfile).setOnClickListener { profileEditorAvatar = null; dialog.dismiss() }
        val save = v.findViewById<Button>(R.id.btnSaveProfile)
        save.setOnClickListener {
            val enteredName = name.text.toString().trim()
            if (enteredName.length > 80) { name.error = "Tên tối đa 80 ký tự"; return@setOnClickListener }
            val currentEmail = authEmail()
            val session = tokenStore.read()
            profileRevision++
            save.isEnabled = false
            lifecycleScope.launch {
                val result = runCatching { api.updateProfile(ProfilePatch(displayName = enteredName.takeUnless { it == currentEmail } ?: "")) }.getOrNull()
                if (authEmail() != currentEmail || tokenStore.read() != session) return@launch
                if (result?.isSuccessful == true) {
                    tokenStore.saveProfileName(currentEmail, result.body()?.profile?.displayName)
                    profileEditorAvatar = null
                    dialog.dismiss()
                    showProfile()
                } else {
                    Toast.makeText(this@MainActivity, "Không thể lưu tên lên cloud. Vui lòng thử lại.", Toast.LENGTH_LONG).show()
                    if (dialog.isShowing) save.isEnabled = true
                }
            }
        }
        dialog.setOnDismissListener { profileEditorAvatar = null }
        dialog.show()
    }

    private fun showAvatarCrop(bitmap: Bitmap) {
        val view = layoutInflater.inflate(R.layout.dialog_crop_avatar, null)
        val crop = view.findViewById<AvatarCropView>(R.id.avatarCrop)
        crop.setBitmap(bitmap)
        view.findViewById<SeekBar>(R.id.avatarZoom).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                crop.setZoom(1f + 3f * progress / seekBar.max)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.avatar_crop_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.avatar_save, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val currentEmail = authEmail()
            val session = tokenStore.read()
            profileRevision++
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.isEnabled = false
            lifecycleScope.launch {
                val rendered = crop.renderAvatar()
                val encoded = withContext(Dispatchers.IO) {
                    try { runCatching { encodeProfileAvatar(rendered) }.getOrNull() }
                    finally { rendered.recycle() }
                }
                if (encoded == null) {
                    Toast.makeText(this@MainActivity, R.string.avatar_save_error, Toast.LENGTH_SHORT).show()
                    save.isEnabled = true
                    return@launch
                }
                val response = runCatching { api.updateProfile(ProfilePatch(avatarBase64 = encoded)) }.getOrNull()
                if (authEmail() != currentEmail || tokenStore.read() != session) return@launch
                if (response?.isSuccessful != true) {
                    Toast.makeText(this@MainActivity, "Không thể lưu ảnh lên cloud. Vui lòng thử lại.", Toast.LENGTH_LONG).show()
                    save.isEnabled = true
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) {
                    runCatching { saveProfileAvatarFile(currentEmail, Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
                }
                if (uri != null) {
                    tokenStore.saveProfileAvatarUri(currentEmail, uri.toString())
                    listOfNotNull(profileEditorAvatar, findViewById<ImageView>(R.id.imgProfileAvatar)).forEach { image ->
                        image.setImageDrawable(null)
                        image.setImageURI(uri)
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Ảnh đã lưu trên cloud; sẽ tải lại khi mở hồ sơ.", Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
        }
    }

    private fun showLogout() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_xac_nhan_dang_xuat, null)
        val dialog = createCustomDialog(v)
        v.findViewById<Button>(R.id.btnCancelLogout).setOnClickListener { dialog.dismiss() }
        v.findViewById<Button>(R.id.btnConfirmLogout).setOnClickListener { dialog.dismiss(); reminderSyncJob?.cancel(); reminderScheduler.cancelAll(); auth.signOut() }
        dialog.show()
    }
}
