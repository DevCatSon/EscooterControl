# Escooter Control

An independent, open-source Android companion app for compatible Vicont-based e-scooters. It uses Jetpack Compose, Material 3, and a BLE protocol implementation derived from interoperability research of the original companion app.

> This project is not affiliated with or endorsed by Vicont or a scooter manufacturer. Hardware support varies by model and firmware. Test controls while the scooter is stationary and follow local rules and manufacturer guidance.

## Android compatibility 

EscooterControl targets Android 16 / API 36 while supporting devices back to **Android 6.0 / API 23**. Target SDK and minimum SDK are independent: targeting current Android keeps the project compatible with modern publishing requirements without dropping older phones.

## Project structure

```text
app/src/main/java/com/devcat/escootercontrol/
├── ble/        BLE transport, framing, telemetry decoding, diagnostics
├── data/       Local preferences, saved scooter, feature and appearance state
├── ui/
│   ├── screens/ Compose screens
│   └── theme/   Color, typography, shape, and theme definitions
├── viewmodel/  UI-facing scooter actions and state
└── MainActivity.kt  App shell, permissions, navigation, lifecycle wiring
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the data flow and module responsibilities, [docs/ONBOARDING.md](docs/ONBOARDING.md) for the first-run experience, and [docs/PROTOCOL.md](docs/PROTOCOL.md) for the protocol findings used by the app.

## Build

Requirements: Android Studio with JDK 17 and Android SDK 36.

Pinned build toolchain for reproducible builds:

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin 2.1.20
- Compose Compiler Gradle plugin 2.1.20
- Compose BOM 2026.06.01 (Compose 1.11.x line)
- compileSdk / targetSdk 36; minSdk 23

```bash
./gradlew test
./gradlew assembleDebug
```

BLE requires a physical Android device. The minimum supported Android version is API 23 (Android 6.0).

### Release signing

Copy `app/keystore.properties.example` to `app/keystore.properties` and point it at your own signing keystore. The properties file and keystore files are ignored by Git. Without the properties file, the release variant remains unsigned.

```bash
./gradlew assembleRelease
```

Never commit signing passwords or private keys.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Keep protocol claims evidence-based: distinguish source-confirmed behavior, live-capture-confirmed behavior, and hypotheses.

## Privacy and security

The app stores preferences and saved scooter metadata locally. Bluetooth permissions are used to discover and communicate with nearby compatible scooters. See [SECURITY.md](SECURITY.md) for responsible vulnerability reporting and scope.

## License

MIT © 2026 Dev Cat. You may use, modify, and redistribute the project, including commercially, provided the copyright and license notice remain with copies or substantial portions of the software. See [LICENSE](LICENSE).
