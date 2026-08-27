package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetCategory
import ru.mybudget.app.R
import ru.mybudget.app.data.BudgetDao
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.setup.ActiveBudgetPreferences
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper
import ru.mybudget.app.utilities.UtilityUserTemplate

data class BudgetTemplateInfo(
    val id: BudgetTemplateId,
    val title: String,
    val description: String,
    val articleCount: Int,
    val subcategoryCount: Int,
)

object BudgetTemplates {
    val all: List<BudgetTemplateInfo> = listOf(
        BudgetTemplateInfo(
            BudgetTemplateId.MINIMAL,
            "Минимальный",
            "Резерв, ЖКХ, быт и прочее — базовая структура для быстрого старта.",
            4,
            5,
        ),
        BudgetTemplateInfo(
            BudgetTemplateId.EXTENDED,
            "Расширенный",
            "Продукты, транспорт, здоровье, дом и развлечения — для семьи из 2–4 человек.",
            9,
            14,
        ),
        BudgetTemplateInfo(
            BudgetTemplateId.FULL,
            "Полный",
            "Популярные статьи: ЖКХ, авто, дети, долги, отпуск, питомцы и др.",
            20,
            48,
        ),
        BudgetTemplateInfo(
            BudgetTemplateId.CUSTOM,
            "Пустой",
            "Без готовых статей — создайте свой список в разделе «Бюджет».",
            0,
            0,
        ),
    )

    suspend fun apply(
        dao: BudgetDao,
        utilityDao: UtilityDao,
        templateId: BudgetTemplateId,
        context: Context,
    ) {
        dao.deleteAllTransactions()
        dao.deleteAllReminders()
        dao.deleteAllSavingsGoals()
        dao.deleteAllRecurring()
        dao.deleteAllPlannedObligations()
        dao.deleteAllMonthlyPlans()
        dao.deleteAllAuditActions()
        dao.deleteAllCategories()
        dao.deleteAllBudgetProfiles()
        UtilityUserTemplate.clearAllData(utilityDao)
        val defaultPropertyId = UtilityPropertyCopyHelper.ensureDefaultProperty(utilityDao)
        ActivePropertyPreferences.setActivePropertyId(context, defaultPropertyId)

        dao.insertBudgetProfile(
            BudgetProfileEntity(
                id = ActiveBudgetPreferences.DEFAULT_BUDGET_ID,
                name = context.getString(R.string.budget_profiles_default_name),
                sortOrder = 0,
                isActive = true,
            ),
        )
        ActiveBudgetPreferences.setActiveBudgetId(context, ActiveBudgetPreferences.DEFAULT_BUDGET_ID)

        for (category in buildCategories(templateId)) {
            dao.insertCategory(category.toEntity())
        }
    }

    private fun buildCategories(templateId: BudgetTemplateId): List<BudgetCategory> {
        return when (templateId) {
            BudgetTemplateId.MINIMAL -> minimalCategories()
            BudgetTemplateId.EXTENDED -> extendedCategories()
            BudgetTemplateId.FULL -> fullCategories()
            BudgetTemplateId.CUSTOM -> emptyList()
        }
    }

    private class CategoryBuilder {
        private val nodes = mutableListOf<Pair<String, Int>>()

        fun article(name: String): Int {
            val id = nodes.size + 1
            nodes += name to 0
            return id
        }

        fun sub(name: String, parentArticleId: Int) {
            nodes += name to parentArticleId
        }

        fun build(): List<BudgetCategory> {
            return nodes.mapIndexed { index, (name, parentId) ->
                BudgetCategory(id = index + 1, name = name, parentId = parentId)
            }
        }
    }

    private fun build(block: CategoryBuilder.() -> Unit): List<BudgetCategory> {
        return CategoryBuilder().apply(block).build()
    }

    private fun minimalCategories(): List<BudgetCategory> = build {
        article("Резерв")
        val utilities = article("ЖКХ")
        sub("Квартплата", utilities)
        sub("Коммунальные услуги", utilities)
        sub("Связь и интернет", utilities)
        val daily = article("Быт")
        sub("Продукты", daily)
        sub("Транспорт", daily)
        article("Прочее")
    }

    private fun extendedCategories(): List<BudgetCategory> = build {
        val reserve = article("Резерв")
        sub("Подушка безопасности", reserve)
        sub("Цели и накопления", reserve)
        val utilities = article("ЖКХ")
        sub("Квартплата", utilities)
        sub("Электричество и газ", utilities)
        sub("Вода и отопление", utilities)
        sub("Связь и интернет", utilities)
        val food = article("Продукты")
        sub("Супермаркет", food)
        sub("Кафе и доставка", food)
        val transport = article("Транспорт")
        sub("Бензин и обслуживание", transport)
        sub("Общественный транспорт", transport)
        val health = article("Здоровье")
        sub("Аптека", health)
        sub("Врачи и анализы", health)
        val home = article("Дом и быт")
        sub("Хозтовары", home)
        sub("Мелкий ремонт", home)
        article("Развлечения")
        article("Одежда и обувь")
        article("Прочее")
    }

    private fun fullCategories(): List<BudgetCategory> = build {
        val reserve = article("Резерв")
        sub("Подушка безопасности", reserve)
        sub("Стабфонд", reserve)
        sub("Непредвиденные расходы", reserve)
        val utilities = article("ЖКХ")
        sub("Квартплата", utilities)
        sub("Электричество", utilities)
        sub("Газ и отопление", utilities)
        sub("Водоканал", utilities)
        sub("Капремонт и содержание", utilities)
        val telecom = article("Связь и подписки")
        sub("Мобильная связь", telecom)
        sub("Домашний интернет", telecom)
        sub("Стриминг и сервисы", telecom)
        val food = article("Продукты")
        sub("Супермаркет", food)
        sub("Рынок и фермерское", food)
        sub("Кафе и рестораны", food)
        val car = article("Авто")
        sub("Бензин", car)
        sub("ТО и ремонт", car)
        sub("ОСАГО и КАСКО", car)
        sub("Парковка и мойка", car)
        val transport = article("Транспорт")
        sub("Общественный транспорт", transport)
        sub("Такси и каршеринг", transport)
        val health = article("Здоровье")
        sub("Лекарства", health)
        sub("Стоматология", health)
        sub("Врачи и анализы", health)
        sub("Спорт и фитнес", health)
        val kids = article("Дети")
        sub("Сад и школа", kids)
        sub("Кружки и секции", kids)
        sub("Одежда и игрушки", kids)
        val clothes = article("Одежда и обувь")
        sub("Сезонные покупки", clothes)
        sub("Аксессуары", clothes)
        val beauty = article("Красота и уход")
        sub("Парикмахер", beauty)
        sub("Косметика", beauty)
        val funArticle = article("Развлечения")
        sub("Кино и театр", funArticle)
        sub("Хобби", funArticle)
        sub("Игры и приложения", funArticle)
        val education = article("Образование")
        sub("Курсы", education)
        sub("Книги и материалы", education)
        val gifts = article("Подарки")
        sub("Дни рождения", gifts)
        sub("Новый год и праздники", gifts)
        val home = article("Дом")
        sub("Мебель", home)
        sub("Бытовая техника", home)
        sub("Ремонт", home)
        val debts = article("Долги и кредиты")
        sub("Ипотека", debts)
        sub("Потребительские кредиты", debts)
        sub("Кредитные карты", debts)
        val taxes = article("Налоги и сборы")
        sub("Транспортный налог", taxes)
        sub("Прочие платежи", taxes)
        val vacation = article("Отпуск")
        sub("Накопления на отпуск", vacation)
        sub("Билеты и проживание", vacation)
        val pets = article("Питомцы")
        sub("Корм и расходники", pets)
        sub("Ветеринар", pets)
        article("Благотворительность")
        article("Прочее")
    }
}
