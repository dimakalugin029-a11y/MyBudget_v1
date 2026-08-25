package ru.mybudget.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.setup.AppLockPreferences
import ru.mybudget.app.setup.BudgetTemplateId
import ru.mybudget.app.setup.BudgetTemplates
import ru.mybudget.app.setup.SetupPreferences

object ScreenTestSeed {
    data class SeedData(
        val billId: Int,
        val categoryId: Int,
        val categoryName: String,
    )

    fun prepare(context: Context): SeedData = runBlocking {
        val appContext = context.applicationContext
        val db = BudgetDatabase.getInstance(appContext)
        BudgetTemplates.apply(
            db.budgetDao(),
            db.utilityDao(),
            BudgetTemplateId.MINIMAL,
            appContext,
        )
        SetupPreferences.markSetupCompleted(appContext, BudgetTemplateId.MINIMAL)
        AppLockPreferences.setEnabled(appContext, false)

        val billId = db.utilityDao()
            .insertBill(UtilityBillEntity(year = 2026, month = 8, apartmentArea = 52.0))
            .toInt()
        db.utilityDao().insertBill(UtilityBillEntity(year = 2026, month = 7, apartmentArea = 52.0))

        val manager = BudgetManager.getInstance(appContext)
        manager.reloadCategoriesFromDatabase()
        val category = manager.getCategoriesAsync(forceReload = true)
            .first { it.parentId == 0 }

        SeedData(
            billId = billId,
            categoryId = category.id,
            categoryName = category.name,
        )
    }
}
