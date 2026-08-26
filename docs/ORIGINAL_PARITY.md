# Сверка с оригинальным APK

**Источник:** `decompiled/resources/AndroidManifest.xml` из `app-debug.apk` (versionCode **16**, package `ru.mybudget.app`)

Дата проверки: 2026-08-25

## Версия приложения

| Поле | Оригинал | Восстановленный проект |
|------|----------|------------------------|
| `applicationId` | `ru.mybudget.app` | `ru.mybudget.app` ✅ |
| `versionCode` | 16 | 16 ✅ |
| `versionName` | 1 | 1 ✅ |
| `minSdk` | 24 | 24 ✅ |
| `targetSdk` / `compileSdk` | 34 | 34 ✅ |

## Room БД

| Параметр | Значение |
|----------|----------|
| `BudgetDatabase.version` | **24** (миграции 1…23→24) ✅ |

## Формат бэкапа JSON

| Параметр | Значение |
|----------|----------|
| `BackupData.CURRENT_VERSION` | **9** (v8 без планов месяца и audit — импортируется) ✅ |

## Activity (экраны)

В оригинале **34 activity** с префиксом `ru.mybudget.app.*`. В проекте — **34 Kotlin-файла** и все объявлены в `AndroidManifest.xml`.

| Activity | Статус |
|----------|--------|
| WelcomeActivity, MainActivity | ✅ |
| AboutActivity, HelpActivity, SettingsActivity | ✅ |
| DefaultAmountsActivity, ExpensePlanActivity | ✅ |
| BudgetProfilesActivity, BudgetActivity | ✅ |
| IncomeActivity, IncomeDistributionActivity | ✅ |
| RemainderDistributionActivity | ✅ |
| ExpenseActivity, ExpenseDistributionActivity | ✅ |
| TransactionsActivity, StatisticsActivity | ✅ |
| RemindersActivity, GoalsActivity | ✅ |
| PlannedObligationsActivity, RecurringActivity | ✅ |
| UtilitiesActivity, UtilityBillActivity | ✅ |
| UtilityMetersActivity, UtilityMeterVerificationActivity | ✅ |
| UtilityMeterHistoryActivity, UtilityMetersBatchActivity | ✅ |
| UtilityTemplateActivity, UtilityTariffsActivity | ✅ |
| UtilityCompareActivity | ✅ |
| LockActivity | ✅ |
| PlanFactActivity, MonthStartActivity | ✅ |
| RolloverActivity, PaymentCalendarActivity | ✅ |

## BroadcastReceiver

| Компонент | Оригинал | Проект |
|-----------|----------|--------|
| BudgetWidgetProvider | ✅ | ✅ |
| RecurringActionReceiver | ✅ | ✅ |
| BootCompletedReceiver | ❌ нет в APK | ✅ **добавлен** — перепланирование напоминаний, авто-бэкапа и виджета после перезагрузки |

## Разрешения (uses-permission)

Все 6 разрешений из оригинала присутствуют: `POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`.

## Layout-ресурсы

| | Оригинал (JADX) | Проект |
|---|-----------------|--------|
| Всего `res/layout` | 206 | 99 |
| Layout приложения | 99 | 99 ✅ |

107 «лишних» в декомпиляции — layout библиотек (Material, AppCompat). В Gradle-проекте они подтягиваются из AAR, дублировать не нужно.

## Kotlin-модули (129 файлов)

Основные подсистемы из APK восстановлены:

- Room + `BudgetManager` / `BudgetRepository`
- ЖКХ: квитанции, шаблон, тарифы, Excel, счётчики, сравнение месяцев
- Бэкап JSON (plain + encrypted), PIN, биометрия
- Автоэкспорт (`AutoBackupWorker`, `AutoBackupScheduler`)
- Статистика, виджет, повторяющиеся операции, календарь платежей
- `UtilityMeterBillLinker` — подстановка показаний в квитанцию (не Toast-заглушка)

## Что нельзя проверить автоматически

- **Поведение UI** — только ручной прогон на устройстве/эмуляторе
- **Байт-в-байт идентичность кода** — Kotlin переписан по декомпиляции
- **`decompiled/sources/`** — Java-код мог быть удалён; для повторной декомпиляции: `tools/jadx/bin/jadx.bat`

## Рекомендуемый чек-лист ручной проверки

1. Импорт JSON-бэкапа из оригинального приложения
2. Создание дохода/расхода, распределение по статьям
3. ЖКХ: месяц → квитанция → списание с бюджета
4. Счётчики → «Применить показания к квитанции»
5. Excel экспорт/импорт коммуналки
6. PIN-блокировка и зашифрованный бэкап
7. Виджет на рабочем столе

## Итог

**Структурное восстановление завершено:** все экраны и ключевые компоненты из манифеста APK присутствуют. Единственное осознанное расширение — `BootCompletedReceiver` для фоновых задач после reboot.
