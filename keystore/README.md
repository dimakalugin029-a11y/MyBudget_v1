# Debug keystore

`debug.keystore` is the **development signing key** for this project.

All `installDebug` builds use it so updates on the emulator/device **replace** the existing app and **keep** budget data (Room DB + preferences).

- Store password: `android`
- Key alias: `androiddebugkey`
- Key password: `android`

This file is safe to commit: it is debug-only, not used for Play Store release.

**One-time migration:** if you previously installed the original release APK or an old debug build with a different key, export a JSON backup, run `tools\install-debug.ps1 -ForceReinstall` once, then import the backup. After that, normal installs preserve data.
