# Architecture

## Overview

Escooter Control is a single-activity Jetpack Compose Android application. UI state flows from the BLE and local-data layers into `ScooterViewModel`, then into Compose screens. User actions travel in the opposite direction through the view model to `ScooterBleManager`.

```text
Compose screens
      │ actions / observed state
      ▼
ScooterViewModel
   ├── ScooterBleManager ── Android BluetoothGatt ── scooter
   ├── ScooterStore ─────── SharedPreferences
   ├── FeatureSupportStore ─ SharedPreferences
   └── AppearanceStore ───── SharedPreferences
```

## BLE layer

### `ScooterBleManager`

Owns scanning, connection lifecycle, service discovery, notification setup, MTU negotiation, the serialized GATT write queue, timeouts, command confirmation, optimistic control intent, and incoming-frame dispatch. Android BLE permits only one in-flight GATT operation at a time, so writes are serialized and guarded against callbacks that never arrive. Rapid dashboard changes are coalesced per opcode so an input burst does not flood the GATT queue.

This class is intentionally the transport boundary: Compose code should not call Android Bluetooth APIs directly.

### `ProtocolCodec`

Builds and validates protocol frames and contains small protocol-specific conversions. Framing logic is kept separate from Bluetooth transport so it can be unit-tested without hardware.

### `Telemetry`

Defines the immutable scooter state exposed to the rest of the app and decodes known incoming telemetry fields. Unknown or absent fields remain nullable rather than being invented.

### `FrameLog`

Keeps a bounded diagnostic history of transmitted and received frames. Internally it uses a ring buffer and lightweight add/clear events, so ordinary BLE traffic does not rebuild a 400-entry immutable list when the Debug screen is closed. The public UI uses it for troubleshooting; protocol conclusions should still be backed by repeatable captures or source evidence.

## Data layer

`ScooterStore` remembers the last successfully connected scooter and optional local nicknames. `FeatureSupportStore` records which optional controls the user has confirmed are present on a particular class of scooter. `AppearanceStore` persists the selected app theme. `SetupStore` persists first-run completion, the current onboarding step, and whether setup is using simulated discovery so an interrupted setup can resume cleanly. These stores are local-only and do not require an account or backend.

## UI layer

Screens are stateless where practical: they receive current state plus callbacks. Navigation and app-level state live in `MainActivity`. The visual system is defined centrally under `ui/theme` so colors, shapes, typography, and future motion changes do not need to be duplicated across screens.

The design deliberately uses native Material 3 controls for Android behavior and accessibility while borrowing broader premium-interface principles such as strong hierarchy, consistent alignment, restrained depth, and purposeful animation.

## Connection lifecycle

1. Request the platform Bluetooth permissions required for the Android version.
2. Reconnect to a previously successful scooter or scan for known advertisement prefixes.
3. Connect over LE transport and discover services.
4. Select a compatible service/characteristic pair.
5. Enable notifications.
6. Negotiate MTU with a timeout fallback.
7. Mark the connection ready and perform the protocol handshake.
8. Decode notifications into `ScooterTelemetry` and expose them as state flows.
9. Serialize outgoing commands through the GATT operation queue.
10. Close the GATT client on disconnect so stale callbacks cannot poison later sessions.

## Reliability rules

- Never fabricate telemetry when a field has not been observed.
- Keep one GATT operation in flight at a time.
- Treat write callbacks and protocol replies as separate layers of confirmation.
- Keep hardware-reported telemetry separate from temporary optimistic UI intent; stale status heartbeats must not visually undo a newer user action while it is still being confirmed.
- Coalesce rapid repeated writes to the same dashboard control so the newest intent wins.
- Time out operations that can otherwise wedge the queue.
- Reset connection-scoped state between sessions.
- Keep optional hardware behind feature visibility rather than assuming every scooter implements every opcode.
- Document whether a finding is confirmed, inferred, or still unknown.

## Testing

`ProtocolCodecTest` covers frame construction/parsing, state-bit interpretation, toggle polarity, and scaling logic. BLE integration still requires real hardware because emulator behavior cannot validate a physical scooter's GATT implementation.

## UI motion and progressive visual effects

The UI intentionally separates interaction semantics from visual polish. Material 3 remains the component and accessibility foundation, while `ui/components/PremiumUi.kt` centralizes the premium motion/effect layer.

- Page navigation uses a bounded 430 ms directional push/pop with subtle background parallax. Control presses use a separate spring response so taps still feel immediate.
- Press feedback can use `premiumPress()` with a shared `MutableInteractionSource`, keeping scale feedback consistent across interactive surfaces.
- Android 12+ (API 31+) can use Compose blur backed by the platform RenderEffect path for small, static glass surfaces.
- The live dashboard deliberately avoids full-screen blur because BLE telemetry can invalidate the screen many times per second; lightweight radial gradients preserve depth with far less GPU work.
- Android 6–11 does not attempt costly fake real-time blur. It receives the same hierarchy through translucent Material color, tonal contrast, rounded surfaces, and gradients.
- Blur is decorative only: text and controls are never made dependent on blur for readability.

This is deliberately Apple-inspired motion rather than an iOS clone: Android Material components, accessibility behavior, navigation, typography, and platform conventions remain native.


### Recomposition boundaries

High-frequency `ScooterTelemetry` is collected inside the active navigation destinations instead of at the root activity. This keeps live speed/battery frames from invalidating the theme, onboarding shell, navigation graph, and unrelated screens. The immutable telemetry and feature models also let Compose skip child work when their inputs did not change.

The speed display rounds the current value for presentation and applies a short direction-aware content transition only when that visible integer changes. It does not animate through every raw BLE sample, avoiding a backlog when telemetry arrives faster than an animation can finish. Dashboard quick controls intentionally avoid a second Material ripple animation and use a lightweight scale/alpha response instead, reducing overlapping draw work during rapid input while keeping taps visibly tactile.

## First-run setup

`OnboardingScreen` is the first surface shown until `SetupStore.completed` becomes true. The flow is intentionally progressive: welcome, Bluetooth explanation/permission, discovery and pairing, local scooter naming, appearance selection, then a ready summary. The permission prompt is not launched on app start; it is requested only when the user reaches the Bluetooth step or asks to scan later.

Setup can be restarted from scooter settings. Restarting disconnects the current session first, resets onboarding progress, and preserves ordinary app preferences such as the selected theme and saved nicknames.

## Demo Mode

`ScooterBleManager.enterDemoMode(name)` creates a local simulated `READY` session with representative telemetry, a synthetic address, and the fake discovery name selected during onboarding. The same public state flows and command entry points are used by the UI, so the demo exercises real screens rather than a parallel mock UI. Supported command calls mutate only the in-memory demo telemetry and are logged as simulated; no GATT object is opened and no Bluetooth write is sent.

Demo sessions are never saved as the last real scooter. The dashboard displays a visible `DEMO` badge, and disconnecting clears the simulated session exactly like a normal disconnect.

### Navigation and input stability

Navigation destinations render on their own opaque premium backdrop. Push/pop changes are translation-only: the incoming destination moves from the navigation edge while the previous page shifts a short parallax distance, and pop is the spatial inverse. Menu backgrounds never blur or fade during normal navigation. Compact Haze-backed controls temporarily pause live blur sampling while a full-screen route is moving, then resume once the transition settles; their material tint remains visible throughout. Dashboard presses use a draw-layer spring response, avoiding layout work and per-frame alpha compositing.

The project tracks the stable Compose BOM. The 0.2.8 dependency refresh moves from the June 2024 BOM to the September 2026 stable BOM so the app includes later Compose UI pointer/hover fixes relevant to scrolling and Android Studio device mirroring.

Lock state is projected into its own low-frequency `StateFlow<Boolean?>` in `ScooterViewModel`, so the app shell can react to lock/unlock without subscribing the NavHost to high-rate telemetry. When locked, an app-wide input-blocking overlay sits above every route. It uses the same optimistic telemetry state as the lock command, so it appears immediately after a lock request and disappears immediately when unlock becomes the current intent.
