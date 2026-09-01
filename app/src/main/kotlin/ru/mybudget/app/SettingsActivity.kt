package ru.mybudget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.backup.AutoBackupScheduler
import ru.mybudget.app.backup.AutoBackupWorker
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.security.AutoBackupSecrets
import ru.mybudget.app.security.BackupCrypto
import ru.mybudget.app.setup.AppLockPreferences
import ru.mybudget.app.setup.AutoBackupPreferences
import ru.mybudget.app.setup.BudgetTemplateId
import ru.mybudget.app.setup.BudgetTemplates
import ru.mybudget.app.setup.MeterReadingReminderPreferences
import ru.mybudget.app.setup.OverspendPreferences
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.backup.WebDavBackupClient
import ru.mybudget.app.security.WebDavSecrets
import ru.mybudget.app.setup.WebDavBackupPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var backupManager: BackupManager
    private var loadingDialog: AlertDialog? = null
    private var pendingExportPassword: String? = null
    private var pendingExportArchive = false
    private var pendingImportUri: Uri? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val password = pendingExportPassword
        val archive = pendingExportArchive
        pendingExportPassword = null
        pendingExportArchive = false
        showLoadingDialog(getString(R.string.settings_export_loading))
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (archive) {
                    backupManager.exportToArchiveFile(uri, password)
                } else {
                    backupManager.exportToFile(uri, password)
                }
            }
            hideLoadingDialog()
            if (ok) {
                Toast.makeText(this@SettingsActivity, R.string.settings_backup_export_done, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SettingsActivity, R.string.settings_backup_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val archive = withContext(Dispatchers.IO) {
                BackupArchiveHelper.readArchive(this@SettingsActivity, uri)
            }
            if (archive != null) {
                val payload = archive.json
                if (BackupCrypto.isEncryptedJson(payload)) {
                    pendingImportUri = uri
                    showImportPasswordDialog()
                } else {
                    chooseImportMode(uri, null)
                }
                return@launch
            }
            val content = withContext(Dispatchers.IO) { backupManager.readFileContent(uri) }
            if (content.isNullOrBlank()) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.backup_import_cannot_read_file,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            if (BackupCrypto.isEncryptedJson(content)) {
                pendingImportUri = uri
                showImportPasswordDialog()
            } else {
                chooseImportMode(uri, null)
            }
        }
    }

    private val autoBackupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess || runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!persisted) {
            Toast.makeText(this, R.string.auto_backup_folder_unavailable, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        AutoBackupPreferences.setFolderUri(this, uri)
        refreshAutoBackupFolderLabel()
        if (AutoBackupPreferences.isEnabled(this)) {
            AutoBackupScheduler.applyFromSettings(this, runImmediately = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.settings_title),
            getString(R.string.main_icon_settings),
        )

        backupManager = BackupManager(this)
        setupThemeSelector()
        setupAppLockSettings()
        setupBackupButtons()
        setupAutoBackup()
        setupWebDavBackup()
        setupOverspendNotifications()
        setupMeterReadingReminder()
        setupParticipants()

        findViewById<View>(R.id.defaultAmountsButton).setOnClickListener {
            startActivity(Intent(this, DefaultAmountsActivity::class.java))
        }
        findViewById<View>(R.id.rolloverButton).setOnClickListener {
            startActivity(Intent(this, RolloverActivity::class.java))
        }
        findViewById<View>(R.id.monthStartButton).setOnClickListener {
            startActivity(Intent(this, MonthStartActivity::class.java))
        }
        findViewById<View>(R.id.templatesButton).setOnClickListener {
            showTemplatesDialog()
        }
        findViewById<View>(R.id.helpButton).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<View>(R.id.aboutButton).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onDestroy() {
        hideLoadingDialog()
        super.onDestroy()
    }

    private fun setupParticipants() {
        val input = findViewById<EditText>(R.id.participantsInput)
        input.setText(ParticipantPreferences.getNames(this).joinToString("\n"))
        findViewById<Button>(R.id.saveParticipantsButton).setOnClickListener {
            val names = input.text.toString()
                .split('\n', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (!ParticipantPreferences.setNames(this, names)) {
                Toast.makeText(
                    this,
                    getString(R.string.settings_participants_limit, ParticipantPreferences.MAX_PARTICIPANTS),
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            input.setText(ParticipantPreferences.getNames(this).joinToString("\n"))
            Toast.makeText(this, R.string.settings_participants_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.participantsReportButton).setOnClickListener {
            startActivity(Intent(this, ParticipantsReportActivity::class.java))
        }
    }

    private fun setupThemeSelector() {
        val prefs = getSharedPreferences(BudgetApplication.PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString(BudgetApplication.KEY_THEME, BudgetApplication.VALUE_LIGHT)
        val checkedId = when (current) {
            BudgetApplication.VALUE_DARK -> R.id.themeDarkRadio
            BudgetApplication.VALUE_SYSTEM -> R.id.themeSystemRadio
            else -> R.id.themeLightRadio
        }
        findViewById<RadioGroup>(R.id.themeRadioGroup).check(checkedId)
        findViewById<RadioGroup>(R.id.themeRadioGroup).setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.themeDarkRadio -> BudgetApplication.VALUE_DARK
                R.id.themeSystemRadio -> BudgetApplication.VALUE_SYSTEM
                else -> BudgetApplication.VALUE_LIGHT
            }
            prefs.edit().putString(BudgetApplication.KEY_THEME, mode).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    BudgetApplication.VALUE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    BudgetApplication.VALUE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_NO
                },
            )
        }
    }

    private fun setupAppLockSettings() {
        val lockSwitch = findViewById<SwitchCompat>(R.id.appLockSwitch)
        val setPinButton = findViewById<Button>(R.id.setPinButton)
        lockSwitch.isChecked = AppLockPreferences.isEnabled(this)
        setPinButton.setText(
            if (AppLockPreferences.hasPin(this)) R.string.lock_change_pin else R.string.lock_set_pin,
        )
        setPinButton.setOnClickListener { showSetPinDialog() }
        lockSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked && !AppLockPreferences.hasPin(this)) {
                button.isChecked = false
                Toast.makeText(this, R.string.lock_set_pin, Toast.LENGTH_SHORT).show()
                showSetPinDialog()
            } else {
                AppLockPreferences.setEnabled(this, checked)
                if (checked) AppLockPreferences.markUnlocked(this)
            }
        }
    }

    private fun showSetPinDialog() {
        val first = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.lock_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_set_pin_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin1 = first.text.toString()
                if (pin1.length < 4) {
                    Toast.makeText(this, R.string.lock_pin_too_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val confirm = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.lock_confirm_pin_title)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (confirm.text.toString() != pin1) {
                            Toast.makeText(this, R.string.lock_pin_mismatch, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        AppLockPreferences.setPin(this, pin1)
                        AppLockPreferences.setEnabled(this, true)
                        findViewById<SwitchCompat>(R.id.appLockSwitch).isChecked = true
                        findViewById<Button>(R.id.setPinButton).setText(R.string.lock_change_pin)
                        Toast.makeText(this, R.string.lock_pin_set_done, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showWithIme(confirm)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithIme(first)
    }

    private fun setupBackupButtons() {
        findViewById<MaterialButton>(R.id.exportButton).setOnClickListener {
            showExportModeDialog()
        }
        findViewById<MaterialButton>(R.id.importButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun setupAutoBackup() {
        val enableSwitch = findViewById<SwitchCompat>(R.id.autoBackupSwitch)
        val encryptSwitch = findViewById<SwitchCompat>(R.id.autoBackupEncryptSwitch)
        val intervalSpinner = findViewById<Spinner>(R.id.autoBackupIntervalSpinner)
        val labels = listOf(
            getString(R.string.auto_backup_interval_7),
            getString(R.string.auto_backup_interval_14),
            getString(R.string.auto_backup_interval_30),
            getString(R.string.auto_backup_interval_60),
        )
        intervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        intervalSpinner.setSelection(AutoBackupPreferences.intervalIndex(this), false)
        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val days = AutoBackupPreferences.INTERVAL_DAYS.getOrElse(position) { 7 }
                if (days == AutoBackupPreferences.intervalDays(this@SettingsActivity)) return
                AutoBackupPreferences.setIntervalDays(this@SettingsActivity, days)
                if (AutoBackupPreferences.isEnabled(this@SettingsActivity)) {
                    AutoBackupScheduler.applyFromSettings(this@SettingsActivity, runImmediately = false)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        enableSwitch.setOnCheckedChangeListener(null)
        enableSwitch.isChecked = AutoBackupPreferences.isEnabled(this)
        enableSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked && AutoBackupPreferences.folderUri(this) == null) {
                button.isChecked = false
                Toast.makeText(this, R.string.auto_backup_no_folder, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            AutoBackupPreferences.setEnabled(this, checked)
            if (!checked) {
                encryptSwitch.setOnCheckedChangeListener(null)
                encryptSwitch.isChecked = false
                encryptSwitch.setOnCheckedChangeListener(encryptListener)
            }
            AutoBackupScheduler.applyFromSettings(this, runImmediately = checked)
        }
        encryptSwitch.setOnCheckedChangeListener(null)
        encryptSwitch.isChecked = AutoBackupPreferences.isEncryptEnabled(this)
        encryptSwitch.setOnCheckedChangeListener(encryptListener)
        findViewById<Button>(R.id.autoBackupFolderButton).setOnClickListener {
            autoBackupFolderLauncher.launch(AutoBackupPreferences.folderUri(this))
        }
        refreshAutoBackupFolderLabel()
    }

    private fun setupOverspendNotifications() {
        val overspendSwitch = findViewById<SwitchCompat>(R.id.overspendNotifySwitch)
        val thresholdInput = findViewById<EditText>(R.id.overspendThresholdInput)
        overspendSwitch.isChecked = OverspendPreferences.isEnabled(this)
        thresholdInput.setText(OverspendPreferences.getThresholdPercent(this).toString())
        overspendSwitch.setOnCheckedChangeListener { _, checked ->
            OverspendPreferences.setEnabled(this, checked)
        }
        thresholdInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val value = thresholdInput.text.toString().toIntOrNull() ?: 100
            OverspendPreferences.setThresholdPercent(this, value)
            thresholdInput.setText(OverspendPreferences.getThresholdPercent(this).toString())
        }
    }

    private fun setupMeterReadingReminder() {
        val enableSwitch = findViewById<SwitchCompat>(R.id.meterReadingReminderSwitch)
        val daySpinner = findViewById<Spinner>(R.id.meterReadingReminderDaySpinner)
        val dayLabels = MeterReadingReminderPreferences.DAY_OPTIONS.map { day ->
            getString(R.string.meter_reading_reminder_day_option, day)
        }
        daySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dayLabels)
        daySpinner.setSelection(MeterReadingReminderPreferences.dayIndex(this), false)
        daySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val day = MeterReadingReminderPreferences.DAY_OPTIONS.getOrElse(position) {
                    MeterReadingReminderPreferences.DEFAULT_DAY
                }
                if (day == MeterReadingReminderPreferences.reminderDay(this@SettingsActivity)) return
                MeterReadingReminderPreferences.setReminderDay(this@SettingsActivity, day)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        enableSwitch.setOnCheckedChangeListener(null)
        enableSwitch.isChecked = MeterReadingReminderPreferences.isEnabled(this)
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            MeterReadingReminderPreferences.setEnabled(this, checked)
        }
    }

    private val encryptListener = CompoundButton.OnCheckedChangeListener { button, checked ->
        if (checked) {
            showAutoBackupPasswordDialog(button)
        } else {
            AutoBackupPreferences.setEncrypt(this, false)
            if (AutoBackupPreferences.isEnabled(this)) {
                AutoBackupScheduler.applyFromSettings(this, runImmediately = false)
            }
        }
    }

    private fun showAutoBackupPasswordDialog(switch: CompoundButton) {
        val first = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.settings_backup_password_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_backup_password_title)
            .setMessage(R.string.auto_backup_password_message)
            .setView(first)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = first.text.toString()
                if (password.length < 4) {
                    switch.isChecked = false
                    Toast.makeText(this, R.string.settings_backup_password_too_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val confirm = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_backup_password_confirm_title)
                    .setView(confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (confirm.text.toString() != password) {
                            switch.isChecked = false
                            Toast.makeText(this, R.string.settings_backup_password_mismatch, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        if (!AutoBackupSecrets.savePassword(this, password)) {
                            switch.isChecked = false
                            Toast.makeText(this, R.string.auto_backup_encrypt_unavailable, Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        AutoBackupPreferences.setEncrypt(this, true)
                        if (AutoBackupPreferences.isEnabled(this)) {
                            AutoBackupScheduler.applyFromSettings(this, runImmediately = true)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> switch.isChecked = false }
                    .showWithIme(confirm)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> switch.isChecked = false }
            .showWithIme(first)
    }

    private fun setupWebDavBackup() {
        val enableSwitch = findViewById<SwitchCompat>(R.id.webDavBackupSwitch)
        val urlInput = findViewById<EditText>(R.id.webDavUrlInput)
        val usernameInput = findViewById<EditText>(R.id.webDavUsernameInput)
        val pathInput = findViewById<EditText>(R.id.webDavPathInput)
        urlInput.setText(WebDavBackupPreferences.baseUrl(this))
        usernameInput.setText(WebDavBackupPreferences.username(this))
        pathInput.setText(WebDavBackupPreferences.remotePath(this))
        enableSwitch.isChecked = WebDavBackupPreferences.isEnabled(this)
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            WebDavBackupPreferences.setEnabled(this, checked)
            if (checked) {
                WebDavBackupPreferences.setBaseUrl(this, urlInput.text.toString())
                WebDavBackupPreferences.setUsername(this, usernameInput.text.toString())
                WebDavBackupPreferences.setRemotePath(this, pathInput.text.toString())
            }
        }
        findViewById<Button>(R.id.webDavPasswordButton).setOnClickListener {
            showWebDavPasswordDialog()
        }
        findViewById<Button>(R.id.webDavTestButton).setOnClickListener {
            testWebDavConnection(urlInput, usernameInput, pathInput)
        }
    }

    private fun showWebDavPasswordDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.webdav_password_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString()
                if (password.isBlank() || !WebDavSecrets.savePassword(this, password)) {
                    Toast.makeText(this, R.string.webdav_password_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.webdav_password_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithIme(input)
    }

    private fun testWebDavConnection(urlInput: EditText, usernameInput: EditText, pathInput: EditText) {
        val password = WebDavSecrets.getPassword(this)
        if (password.isNullOrBlank()) {
            Toast.makeText(this, R.string.webdav_password_missing, Toast.LENGTH_SHORT).show()
            return
        }
        showLoadingDialog(getString(R.string.webdav_test_loading))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WebDavBackupClient.testConnection(
                    baseUrl = urlInput.text.toString().trim(),
                    username = usernameInput.text.toString().trim(),
                    password = password,
                    remotePath = pathInput.text.toString().trim(),
                )
            }
            hideLoadingDialog()
            WebDavBackupPreferences.setBaseUrl(this@SettingsActivity, urlInput.text.toString())
            WebDavBackupPreferences.setUsername(this@SettingsActivity, usernameInput.text.toString())
            WebDavBackupPreferences.setRemotePath(this@SettingsActivity, pathInput.text.toString())
            if (result.isSuccess) {
                Toast.makeText(this@SettingsActivity, R.string.webdav_test_success, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.webdav_test_failed, result.exceptionOrNull()?.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refreshAutoBackupFolderLabel() {
        val text = findViewById<TextView>(R.id.autoBackupFolderText)
        val uri = AutoBackupPreferences.folderUri(this)
        text.text = if (uri == null) {
            getString(R.string.auto_backup_folder_not_selected)
        } else {
            getString(
                R.string.auto_backup_folder_selected,
                AutoBackupWorker.folderDisplayName(this, uri),
            )
        }
    }

    private fun showExportModeDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_export_mode_title)
            .setItems(
                arrayOf(
                    getString(R.string.settings_export_mode_plain),
                    getString(R.string.settings_export_mode_encrypted),
                    getString(R.string.settings_export_mode_archive),
                ),
            ) { _, which ->
                when (which) {
                    0 -> launchExport(null, encrypted = false, archive = false)
                    1 -> showExportPasswordDialog(archive = false)
                    2 -> launchExport(null, encrypted = false, archive = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showExportPasswordDialog(archive: Boolean = false) {
        val first = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.settings_backup_password_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_password_title)
            .setView(first)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = first.text.toString()
                if (password.length < 4) {
                    Toast.makeText(this, R.string.settings_backup_password_too_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val confirm = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_backup_password_confirm_title)
                    .setView(confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (confirm.text.toString() != password) {
                            Toast.makeText(this, R.string.settings_backup_password_mismatch, Toast.LENGTH_SHORT).show()
                        } else {
                            launchExport(password, encrypted = true, archive = archive)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showWithIme(confirm)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithIme(first)
    }

    private fun launchExport(password: String?, encrypted: Boolean, archive: Boolean) {
        pendingExportPassword = password
        pendingExportArchive = archive
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val suffix = if (encrypted) "_encrypted" else ""
        if (archive) {
            exportLauncher.launch("MyBudget_backup_${date}$suffix.zip")
        } else {
            exportLauncher.launch("MyBudget_backup_${date}$suffix.json")
        }
    }

    private fun chooseImportMode(uri: Uri, password: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import_mode_title)
            .setItems(
                arrayOf(
                    getString(R.string.settings_import_mode_replace),
                    getString(R.string.settings_import_mode_merge),
                ),
            ) { _, which ->
                val mode = if (which == 1) BackupImportMode.MERGE else BackupImportMode.REPLACE
                confirmImport(uri, password, mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImportPasswordDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.settings_backup_password_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_import_password_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val uri = pendingImportUri ?: return@setPositiveButton
                val password = input.text.toString()
                if (password.isBlank()) {
                    Toast.makeText(this, R.string.backup_import_password_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingImportUri = null
                confirmImport(uri, password, BackupImportMode.REPLACE)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingImportUri = null
            }
            .showWithIme(input)
    }

    private fun confirmImport(uri: Uri, password: String?, mode: BackupImportMode) {
        val message = if (mode == BackupImportMode.MERGE) {
            R.string.settings_import_confirm_merge
        } else {
            R.string.settings_import_confirm
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoadingDialog(getString(R.string.settings_import_loading))
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        backupManager.importFromFile(uri, password, mode)
                    }
                    hideLoadingDialog()
                    if (result.success) {
                        withContext(Dispatchers.IO) {
                            BudgetManager.getInstance(this@SettingsActivity).reloadCategoriesFromDatabase()
                        }
                        Toast.makeText(
                            this@SettingsActivity,
                            result.message ?: getString(R.string.backup_import_success_version, result.backupVersion),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            result.message ?: getString(R.string.backup_import_failed, ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTemplatesDialog() {
        val labels = arrayOf(
            getString(R.string.template_minimal),
            getString(R.string.template_extended),
            getString(R.string.template_full),
            getString(R.string.template_custom),
        )
        val ids = arrayOf(
            BudgetTemplateId.MINIMAL,
            BudgetTemplateId.EXTENDED,
            BudgetTemplateId.FULL,
            BudgetTemplateId.CUSTOM,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.template_dialog_title)
            .setItems(labels) { _, which ->
                confirmApplyTemplate(ids[which], labels[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmApplyTemplate(templateId: BudgetTemplateId, label: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.template_confirm_title)
            .setMessage(getString(R.string.template_confirm_message, label))
            .setPositiveButton(R.string.template_apply) { _, _ ->
                applyTemplate(templateId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTemplate(templateId: BudgetTemplateId) {
        showLoadingDialog(getString(R.string.template_applying))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = BudgetDatabase.getInstance(this@SettingsActivity)
                BudgetTemplates.apply(db.budgetDao(), db.utilityDao(), templateId, this@SettingsActivity)
                BudgetManager.getInstance(this@SettingsActivity).reloadCategoriesFromDatabase()
                delay(200)
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    BudgetWidgetProvider.updateAll(this@SettingsActivity)
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.template_applied,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.welcome_apply_error,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showLoadingDialog(message: String) {
        hideLoadingDialog()
        loadingDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
}
