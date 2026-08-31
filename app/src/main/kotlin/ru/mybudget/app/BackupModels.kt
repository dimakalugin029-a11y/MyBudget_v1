package ru.mybudget.app

import com.google.gson.annotations.SerializedName
import ru.mybudget.app.data.AuditActionEntity
import ru.mybudget.app.data.BudgetCategoryEntity
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.SavingsGoalEntity
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityBillPhotoEntity
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.data.UtilityPropertyEntity
import ru.mybudget.app.data.UtilitySectionEntity
import ru.mybudget.app.data.UtilityTariffEntity
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.data.UtilityTemplateSectionEntity

internal data class BackupDataDto(
    @SerializedName("version") val version: Int? = null,
    @SerializedName("exportedAt") val exportedAt: Long? = null,
    @SerializedName("budgetProfiles") val budgetProfiles: List<BudgetProfileEntity>? = null,
    @SerializedName("categories") val categories: List<BudgetCategoryEntity>? = null,
    @SerializedName("transactions") val transactions: List<TransactionEntity>? = null,
    @SerializedName("reminders") val reminders: List<PaymentReminderEntity>? = null,
    @SerializedName("savingsGoals") val savingsGoals: List<SavingsGoalEntity>? = null,
    @SerializedName("recurringTransactions") val recurringTransactions: List<RecurringTransactionEntity>? = null,
    @SerializedName("plannedObligations") val plannedObligations: List<PlannedObligationEntity>? = null,
    @SerializedName("plannedIncomeSources") val plannedIncomeSources: List<PlannedIncomeSourceEntity>? = null,
    @SerializedName("utilityProperties") val utilityProperties: List<UtilityPropertyEntity>? = null,
    @SerializedName("utilityBills") val utilityBills: List<UtilityBillEntity>? = null,
    @SerializedName("utilityBillPhotos") val utilityBillPhotos: List<UtilityBillPhotoEntity>? = null,
    @SerializedName("utilitySections") val utilitySections: List<UtilitySectionEntity>? = null,
    @SerializedName("utilityLineItems") val utilityLineItems: List<UtilityLineItemEntity>? = null,
    @SerializedName("utilityMeterReadings") val utilityMeterReadings: List<UtilityMeterReadingEntity>? = null,
    @SerializedName("utilityMeterInfo") val utilityMeterInfo: List<UtilityMeterInfoEntity>? = null,
    @SerializedName("utilityTemplateSections") val utilityTemplateSections: List<UtilityTemplateSectionEntity>? = null,
    @SerializedName("utilityTemplateLines") val utilityTemplateLines: List<UtilityTemplateLineEntity>? = null,
    @SerializedName("utilityTariffs") val utilityTariffs: List<UtilityTariffEntity>? = null,
    @SerializedName("monthlyCategoryPlans") val monthlyCategoryPlans: List<MonthlyCategoryPlanEntity>? = null,
    @SerializedName("auditActions") val auditActions: List<AuditActionEntity>? = null,
)

data class BackupData(
    @SerializedName("version") val version: Int = CURRENT_VERSION,
    @SerializedName("exportedAt") val exportedAt: Long = System.currentTimeMillis(),
    @SerializedName("budgetProfiles") val budgetProfiles: List<BudgetProfileEntity> = emptyList(),
    @SerializedName("categories") val categories: List<BudgetCategoryEntity> = emptyList(),
    @SerializedName("transactions") val transactions: List<TransactionEntity> = emptyList(),
    @SerializedName("reminders") val reminders: List<PaymentReminderEntity> = emptyList(),
    @SerializedName("savingsGoals") val savingsGoals: List<SavingsGoalEntity> = emptyList(),
    @SerializedName("recurringTransactions") val recurringTransactions: List<RecurringTransactionEntity> = emptyList(),
    @SerializedName("plannedObligations") val plannedObligations: List<PlannedObligationEntity> = emptyList(),
    @SerializedName("plannedIncomeSources") val plannedIncomeSources: List<PlannedIncomeSourceEntity> = emptyList(),
    @SerializedName("utilityProperties") val utilityProperties: List<UtilityPropertyEntity> = emptyList(),
    @SerializedName("utilityBills") val utilityBills: List<UtilityBillEntity> = emptyList(),
    @SerializedName("utilityBillPhotos") val utilityBillPhotos: List<UtilityBillPhotoEntity> = emptyList(),
    @SerializedName("utilitySections") val utilitySections: List<UtilitySectionEntity> = emptyList(),
    @SerializedName("utilityLineItems") val utilityLineItems: List<UtilityLineItemEntity> = emptyList(),
    @SerializedName("utilityMeterReadings") val utilityMeterReadings: List<UtilityMeterReadingEntity> = emptyList(),
    @SerializedName("utilityMeterInfo") val utilityMeterInfo: List<UtilityMeterInfoEntity> = emptyList(),
    @SerializedName("utilityTemplateSections") val utilityTemplateSections: List<UtilityTemplateSectionEntity> = emptyList(),
    @SerializedName("utilityTemplateLines") val utilityTemplateLines: List<UtilityTemplateLineEntity> = emptyList(),
    @SerializedName("utilityTariffs") val utilityTariffs: List<UtilityTariffEntity> = emptyList(),
    @SerializedName("monthlyCategoryPlans") val monthlyCategoryPlans: List<MonthlyCategoryPlanEntity> = emptyList(),
    @SerializedName("auditActions") val auditActions: List<AuditActionEntity> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 16
    }
}

data class BackupImportResult(
    val success: Boolean,
    val message: String? = null,
    val backupVersion: Int = 0,
)
