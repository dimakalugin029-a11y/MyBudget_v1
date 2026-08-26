package ru.mybudget.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

internal data class BudgetWidgetContent(
    val profileName: String,
    val balanceText: String,
    val balanceColorRes: Int,
)

internal object BudgetWidgetViews {
    suspend fun loadContent(context: Context): BudgetWidgetContent {
        val budgetManager = BudgetManager.getInstance(context)
        budgetManager.getCategoriesAsync()
        val activeId = budgetManager.getActiveBudgetId()
        val profiles = budgetManager.getBudgetProfilesAsync()
        val name = profiles.firstOrNull { it.id == activeId }?.name
            ?: context.getString(R.string.budget_profiles_default_name)
        val balance = budgetManager.getTotalBalance(activeId)
        val night = isNightMode(context)
        return BudgetWidgetContent(
            profileName = name,
            balanceText = MoneyFormat.formatRub(balance),
            balanceColorRes = balanceColorRes(balance >= 0.0, night),
        )
    }

    fun buildRemoteViews(context: Context, content: BudgetWidgetContent): RemoteViews {
        val night = isNightMode(context)
        val views = RemoteViews(context.packageName, R.layout.widget_budget)
        applyTheme(context, views, night)
        views.setTextViewText(R.id.widgetBudgetName, content.profileName)
        views.setTextViewText(R.id.widgetBalance, content.balanceText)
        views.setTextColor(
            R.id.widgetBalance,
            ContextCompat.getColor(context, content.balanceColorRes),
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            BudgetWidgetProvider.WIDGET_PENDING_FLAGS,
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, openApp)
        views.setOnClickPendingIntent(
            R.id.widgetIncomeButton,
            activityPendingIntent(context, IncomeActivity::class.java, 1),
        )
        views.setOnClickPendingIntent(
            R.id.widgetExpenseButton,
            activityPendingIntent(context, QuickExpenseActivity::class.java, 2),
        )
        return views
    }

    private fun applyTheme(context: Context, views: RemoteViews, night: Boolean) {
        val cardBg = if (night) R.drawable.widget_card_gradient_dark else R.drawable.widget_card_gradient_light
        val incomeBtn = if (night) R.drawable.widget_btn_income_dark else R.drawable.widget_btn_income_light
        val expenseBtn = if (night) R.drawable.widget_btn_expense_dark else R.drawable.widget_btn_expense_light
        views.setInt(R.id.widgetRoot, "setBackgroundResource", cardBg)
        views.setInt(R.id.widgetIncomeButton, "setBackgroundResource", incomeBtn)
        views.setInt(R.id.widgetExpenseButton, "setBackgroundResource", expenseBtn)
        views.setTextColor(R.id.widgetBudgetName, ContextCompat.getColor(context, if (night) R.color.widget_text_primary_dark else R.color.widget_text_primary_light))
        views.setTextColor(R.id.widgetBalanceLabel, ContextCompat.getColor(context, if (night) R.color.widget_text_secondary_dark else R.color.widget_text_secondary_light))
        views.setTextColor(R.id.widgetAppLabel, ContextCompat.getColor(context, if (night) R.color.widget_text_muted_dark else R.color.widget_text_muted_light))
        views.setTextColor(R.id.widgetIncomeButton, ContextCompat.getColor(context, if (night) R.color.widget_income_text_dark else R.color.widget_income_text_light))
        views.setTextColor(R.id.widgetExpenseButton, ContextCompat.getColor(context, if (night) R.color.widget_expense_text_dark else R.color.widget_expense_text_light))
    }

    private fun balanceColorRes(positive: Boolean, night: Boolean): Int = when {
        positive && night -> R.color.widget_balance_positive_dark
        positive && !night -> R.color.widget_balance_positive_light
        !positive && night -> R.color.widget_balance_negative_dark
        else -> R.color.widget_balance_negative_light
    }

    private fun isNightMode(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun activityPendingIntent(context: Context, cls: Class<*>, requestCode: Int): PendingIntent {
        val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, intent, BudgetWidgetProvider.WIDGET_PENDING_FLAGS)
    }
}
