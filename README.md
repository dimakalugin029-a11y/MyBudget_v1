# MyBudget

Личный бюджет для Android — восстановленный Kotlin-проект из APK `ru.mybudget.app` (versionCode 16).

## Требования

- JDK 17+
- Android SDK (compileSdk 34, minSdk 24)

## Сборка

```bat
gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Установка на эмулятор / телефон

Обычное **обновление** (данные сохраняются):

```powershell
.\tools\install-debug.ps1
```

Или через Gradle:

```bat
gradlew.bat installDebug
```

**Важно:** не делайте `adb uninstall` перед каждой установкой — так удаляются все данные и приходится импортировать бюджет заново.

Если установка падает с `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (другая подпись — например, стоял оригинальный APK):

1. Экспортируйте бэкап в приложении: **Настройки → Резервная копия → Экспорт**
2. Один раз: `.\tools\install-debug.ps1 -ForceReinstall`
3. Импортируйте бэкап обратно

После этого все следующие `installDebug` / `install-debug.ps1` работают как обновление.

Debug-подпись зафиксирована в `keystore/debug.keystore` (см. `keystore/README.md`).

## Тесты

```bat
gradlew.bat testDebugUnitTest
```

## Структура

| Путь | Назначение |
|------|------------|
| `app/src/main/kotlin/ru/mybudget/app/` | Экраны, менеджеры, Room |
| `app/src/main/kotlin/ru/mybudget/app/utilities/` | ЖКХ, Excel, счётчики |
| `app/src/main/kotlin/ru/mybudget/app/backup/` | JSON-бэкап, автоэкспорт |
| `app/src/main/kotlin/ru/mybudget/app/security/` | PIN, шифрование бэкапа |
| `RECOVERY.md` | План восстановления и статус этапов |
| `docs/ORIGINAL_PARITY.md` | Сверка компонентов с оригинальным APK |

## Восстановление данных

Импорт JSON-бэкапа из оригинального приложения: **Настройки → Резервная копия**.

## CI

GitHub Actions собирает debug APK и запускает unit-тесты при push/PR в `main`.
