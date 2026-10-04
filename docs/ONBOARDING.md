# First-run setup

The first-run experience is designed to introduce only the decisions the app actually needs. It is implemented in `ui/screens/OnboardingScreen.kt` and persisted by `data/SetupStore.kt`.

## Flow

1. **Welcome** — short product introduction and an optional Demo Mode path. Choosing Demo jumps directly to a simulated pairing experience instead of asking for Bluetooth.
2. **Bluetooth** — explains why nearby-device access is needed before Android shows the system prompt during a real-hardware setup.
3. **Pair** — either scans for compatible advertisements or, in Demo setup, displays several fake nearby scooters with simulated signal strength. Selecting a fake scooter creates the local demo session and continues through the same naming/theme flow.
4. **Name** — stores an optional local nickname for the connected scooter.
5. **Appearance** — previews and applies System, Light, Dark, or Midnight immediately.
6. **Ready** — summarizes the selected setup and enters the connected dashboard.

The current step and whether setup is in simulated-discovery mode are saved after every transition, so killing or backgrounding the app does not force the user back to the beginning. Completion is saved separately. Real-hardware setup now requires an active scooter connection before personalization can continue; Demo Mode is the supported offline path. `Run setup again` resets only onboarding completion/progress; it does not erase unrelated preferences.

## Permission behavior

The app does not request Bluetooth permission immediately at process start. The permission dialog appears only when the user reaches the Bluetooth step or explicitly asks to scan later. On Android 12+ this is Nearby devices (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`). Android 6–11 require the older location permission for BLE discovery because of the platform API design.

A user can enter Demo Mode without granting Bluetooth permission. There is intentionally no separate “skip pairing” path: users either pair a real scooter or choose a simulated one.

## Demo Mode

Demo Mode begins with a simulated discovery list on the normal pairing step. After the user chooses a fake nearby scooter, the app uses the same dashboard, settings, telemetry models, and view-model action surface as a real connection. `ScooterBleManager` substitutes local state changes for radio commands while `demoMode == true`.

Safety properties:

- no Bluetooth GATT client is opened;
- no outgoing BLE frame is written;
- the session is visibly labeled `DEMO`;
- the synthetic scooter is not stored as the last real scooter;
- disconnect exits the simulation and resets telemetry.

This makes Demo Mode useful for screenshots, UI development, contributor testing, and evaluating the app before a scooter is nearby.
