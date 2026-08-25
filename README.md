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

## Восстановление данных

Импорт JSON-бэкапа из оригинального приложения: **Настройки → Резервная копия**.

## CI

GitHub Actions собирает debug APK и запускает unit-тесты при push/PR в `main`.
