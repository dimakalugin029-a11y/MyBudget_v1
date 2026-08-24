package ru.mybudget.app.utilities

import android.app.DatePickerDialog
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.R
import ru.mybudget.app.data.UtilityMeterInfoEntity
import java.time.LocalDate

object UtilityMeterDialogs {
    fun showAddMeter(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        repository: MeterRepository,
        onSaved: () -> Unit,
    ) {
        showMeterDialog(activity, scope, repository, existing = null, onSaved)
    }

    fun showEditMeter(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        repository: MeterRepository,
        existing: UtilityMeterInfoEntity,
        onSaved: () -> Unit,
    ) {
        showMeterDialog(activity, scope, repository, existing, onSaved)
    }

    private fun showMeterDialog(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        repository: MeterRepository,
        existing: UtilityMeterInfoEntity?,
        onSaved: () -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_edit_meter, null)
        val groupInput = view.findViewById<EditText>(R.id.meterGroupInput)
        val nameInput = view.findViewById<EditText>(R.id.meterNameInput)
        val verificationInput = view.findViewById<EditText>(R.id.meterVerificationInput)
        if (existing != null) {
            groupInput.setText(existing.groupName)
            nameInput.setText(existing.meterName)
            verificationInput.setText(existing.verificationDateLabel)
        }
        AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.meter_add_title else R.string.meter_edit_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val group = groupInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                val verRaw = verificationInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(activity, R.string.meter_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val parsed = if (verRaw.isBlank()) null else MeterDateParser.parseVerificationDateInput(verRaw)
                val verLabel = parsed?.first ?: verRaw
                val verEpoch = parsed?.second
                scope.launch(Dispatchers.IO) {
                    val ok = if (existing == null) {
                        repository.createMeter(group, name, verLabel, verEpoch)
                    } else {
                        repository.updateMeter(
                            existing,
                            existing.copy(
                                groupName = group,
                                meterName = name,
                                verificationDateLabel = verLabel,
                                verificationEpochDay = verEpoch,
                            ),
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            onSaved()
                        } else {
                            Toast.makeText(activity, R.string.meter_duplicate, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showDeleteMeter(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        repository: MeterRepository,
        info: UtilityMeterInfoEntity,
        readingsCount: Int,
        onDeleted: () -> Unit,
    ) {
        val message = if (readingsCount > 0) {
            activity.getString(R.string.meter_delete_with_readings, readingsCount)
        } else {
            activity.getString(R.string.meter_delete_confirm)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.meter_delete_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    repository.deleteMeter(info)
                    withContext(Dispatchers.Main) { onDeleted() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEditVerificationDate(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        repository: MeterRepository,
        meter: UtilityMeterInfoEntity,
        onSaved: () -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_edit_verification, null)
        val labelView = view.findViewById<TextView>(R.id.verificationMeterLabel)
        val dateInput = view.findViewById<EditText>(R.id.verificationDateInput)
        val displayName = meter.meterName.trim()
        labelView.text = if (meter.groupName.isBlank()) displayName else "${meter.groupName} · $displayName"
        val current = meter.verificationDateLabel.trim()
        if (MeterDateParser.looksLikeVerificationDate(current)) {
            dateInput.setText(current)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.meter_verification_edit_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val verRaw = dateInput.text.toString().trim()
                val verLabel: String
                val verEpoch: Long?
                if (verRaw.isBlank()) {
                    verLabel = ""
                    verEpoch = null
                } else {
                    val parsed = MeterDateParser.parseVerificationDateInput(verRaw)
                    if (parsed == null) {
                        Toast.makeText(activity, R.string.meter_verification_invalid_date, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    verLabel = parsed.first
                    verEpoch = parsed.second
                }
                scope.launch(Dispatchers.IO) {
                    repository.updateVerificationDate(meter, verLabel, verEpoch)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, R.string.meter_verification_saved, Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showAddReading(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        repository: MeterRepository,
        groupName: String,
        meterName: String,
        onSaved: () -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_add_meter_reading, null)
        val dateInput = view.findViewById<EditText>(R.id.readingDateInput)
        val valueInput = view.findViewById<EditText>(R.id.readingValueInput)
        val consumptionInput = view.findViewById<EditText>(R.id.readingConsumptionInput)
        var selectedEpochDay = LocalDate.now().toEpochDay()
        fun refreshDate() {
            dateInput.setText(MeterDateParser.formatEpochDay(selectedEpochDay))
        }
        refreshDate()
        dateInput.setOnClickListener {
            val initial = LocalDate.ofEpochDay(selectedEpochDay)
            DatePickerDialog(
                activity,
                { _, year, month, day ->
                    selectedEpochDay = LocalDate.of(year, month + 1, day).toEpochDay()
                    refreshDate()
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            ).show()
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.meter_reading_add_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = MoneyFormat.parseQuantity(valueInput.text)
                if (value == null) {
                    Toast.makeText(activity, R.string.meter_reading_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val consumptionRaw = consumptionInput.text.toString().trim()
                val consumption = if (consumptionRaw.isEmpty()) {
                    null
                } else {
                    MoneyFormat.parseQuantity(consumptionRaw)
                }
                if (consumptionRaw.isNotEmpty() && consumption == null) {
                    Toast.makeText(activity, R.string.meter_reading_consumption_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                scope.launch(Dispatchers.IO) {
                    val result = repository.addMeterReading(
                        groupName,
                        meterName,
                        selectedEpochDay,
                        value,
                        consumption,
                    )
                    withContext(Dispatchers.Main) {
                        val message = MeterRepository.messageFor(result)
                        if (message != null) {
                            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                        }
                        if (result is MeterReadingSaveResult.Saved) {
                            dialog.dismiss()
                            onSaved()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
