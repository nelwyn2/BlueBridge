# BlueBridge for Android

An unofficial, open-source Android app for controlling Hyundai and Kia vehicles via the Bluelink / UVO / Kia Connect API — inspired by the iOS app BetterBlue.

## Features

- 🔒 **Lock & Unlock** doors remotely
- 🚗 **Remote Start / Stop** engine with full climate pre-configuration
- ❄️ **Climate Control** — temperature, defrost, heated steering wheel, seat heat levels
- 🔋 **EV Support** — battery status, start/stop charging, set AC & DC charge targets
- 📍 **Vehicle Status** — doors, hood, trunk, tires, ignition, odometer
- 🏠 **Home screen widgets** — full, battery, lock, unlock, climate, refresh, and compact controls widgets
- 🔐 **Biometric lock** option
- 🔑 **Secure token storage** via DataStore

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 26+ (Android 8.0+)
- A Hyundai Bluelink or Kia Connect account (existing — same login as the official app)

## Setup

1. Clone or unzip this project
2. Open the `BlueBridgeAndroid/` folder in **Android Studio**
3. Wait for Gradle to sync and download dependencies (~2–3 min first time)
4. Connect an Android device or start an emulator
5. Click **Run ▶**
6. Sign in with your existing Bluelink / Kia Connect email and password

## Home Screen Widgets

BlueBridge includes several Android home-screen widgets backed by `VehicleWidgetProvider` and small provider subclasses. Each widget reads the cached selected-vehicle snapshot from DataStore. Refresh and command widgets call the same `VehicleRepository` used by the main app, then refresh and cache the latest battery, range, charging, and lock-state data.

Available widget entries:

| Widget | Default size | Purpose |
|--------|--------------|---------|
| BlueBridge Full | 4×2 | Battery, range, lock state, refresh, lock, unlock, and climate |
| BlueBridge Battery | 2×1 | Compact battery, range, and refresh |
| BlueBridge Battery Wide | 3×2 | Larger battery/range view with lock state and refresh |
| BlueBridge Lock | 1×1 | One-tap lock |
| BlueBridge Unlock | 1×1 | One-tap unlock |
| BlueBridge Climate | 1×1 | One-tap cabin climate using the app default temperature |
| BlueBridge Refresh | 1×1 | One-tap status refresh |
| BlueBridge Lock Controls | 2×1 | Compact lock and unlock buttons |

Most launchers also allow these widgets to be resized after placement, within Android launcher constraints.

### Widget Behavior

- Widgets use the currently selected vehicle from the main app.
- Battery and lock-state information is cached so widgets can render quickly without opening the app.
- Refresh updates the cached vehicle snapshot.
- Command widgets execute immediately through the same repository layer used by the in-app controls.
- After a command completes, widgets request a status refresh so the displayed state can catch up with the vehicle.
- Vehicle API status updates may be delayed by Hyundai/Kia servers, so lock, climate, and charging state can briefly lag behind the command result.

### Widget Stability Notes

The home-screen widgets use only Android `RemoteViews`-compatible view classes. Earlier widget layouts used `Space` separators, which some launchers reject while inflating widgets and display as a generic “Problem loading widget” or error tile. Separators are now implemented as empty `TextView` elements, and widget provider metadata no longer marks widgets as reconfigurable because no configuration activity is supplied.

BlueBridge registers multiple widget entries so the launcher can offer separate battery, refresh, lock, unlock, climate, compact controls, and full-size widgets.

### Widget Size and Dashboard-Style Updates

- The single-action widgets — Lock, Unlock, Climate, and Refresh — advertise Android's legacy 1×1 widget footprint using 40dp minimum width/height plus `targetCellWidth="1"` and `targetCellHeight="1"`.
- The compact Battery widget advertises a 2×1 footprint.
- The wide Battery widget remains a larger 3×2 option.
- Battery widgets use a dashboard-inspired dark blue gradient card, rounded inner status panel, large battery percentage typography, and a blue/green rounded progress bar matching the dashboard battery panel style.

### Climate Command Empty-Response Fix

Some Hyundai/Bluelink remote climate endpoints can return HTTP success with an empty response body. BlueBridge treats successful empty responses as successful commands instead of letting Retrofit/Gson try to parse an empty body as JSON, which previously produced `End of input at line 1 column 1 path $` after climate start/stop succeeded.

## Regional Configuration

In the app go to **Settings → Region & Brand** and select:

| Option | Use for |
|--------|---------|
| USA — Hyundai | US Bluelink accounts |
| USA — Kia | US Kia Connect / UVO accounts |
| Canada — Hyundai | Canadian Bluelink |
| Europe | EU Hyundai/Kia |
| Australia / NZ | AUS Hyundai/Kia |

## Security & Privacy

- BlueBridge is a client app for your existing Hyundai Bluelink or Kia Connect account.
- Credentials and tokens are stored locally using the app's DataStore-backed storage layer.
- Biometric lock can be enabled to add an extra local access gate before using the app.
- Vehicle commands are sensitive actions. Treat any device with BlueBridge installed as capable of sending remote vehicle commands.
- This app is not intended for shared or unattended devices unless Android device-level security is enabled.

## Troubleshooting

| Issue | What to try |
|-------|-------------|
| Login fails | Confirm the same credentials work in the official Hyundai/Kia app, then verify the selected region and brand. |
| Vehicle list is empty | Refresh after login and confirm the account has an active enrolled vehicle in the official app. |
| Commands work but status looks stale | Use Refresh. Server-side status can lag behind successful commands. |
| Widget shows no vehicle | Open the app once, sign in, select a vehicle, then add or refresh the widget. |
| Widget command appears delayed | The command may have been accepted while the vehicle status endpoint has not updated yet. Refresh again after a short interval. |
| Climate start/stop shows success but status lags | The remote command and the vehicle status update are separate API flows; status may trail the accepted command. |
| Region-specific features are missing | Some endpoints and features vary by Hyundai/Kia region, vehicle model, account type, and EV vs ICE platform. |

## Architecture

```
app/
└── java/com/bluebridge/android/
    ├── data/
    │   ├── api/          # Retrofit API service + constants
    │   ├── models/       # All data classes (Vehicle, Status, etc.)
    │   └── repository/   # VehicleRepository + PreferencesManager
    ├── di/               # Hilt dependency injection
    ├── ui/
    │   ├── components/   # Reusable Compose components
    │   ├── navigation/   # NavHost / Screen routes
    │   ├── screens/      # Login, Dashboard, Controls, Status,
    │   │                 # RemoteStart, EVCharging, Settings
    │   └── theme/        # Colors, Typography, Theme
    ├── viewmodel/        # AuthViewModel, VehicleViewModel, SettingsViewModel
    └── widget/           # Home screen widget providers and RemoteViews layouts
```

**Stack:** Kotlin · Jetpack Compose · Hilt · Retrofit · OkHttp · DataStore · Navigation Compose · Android App Widgets

## Build Notes

- Open the project root in Android Studio before building.
- Let Android Studio sync Gradle dependencies before running the app.
- Use a physical device for best testing of widgets, biometrics, and background command behavior.
- Some emulators do not fully match real launcher widget sizing behavior.

## Known Limitations

- Hyundai/Kia APIs are unofficial and may change without notice.
- API availability varies by region, brand, vehicle model, and account state.
- Widgets depend on cached app state and Android launcher behavior.
- Android launchers may render widget spacing and exact grid size differently.
- Remote command status can be delayed even when a command was accepted successfully.
- Vehicle wake-up, network coverage, subscription status, and server-side throttling can affect command reliability.

## Future Ideas

- Push notifications for status changes
- Trip history parsing
- Scheduled remote start timer
- Android Auto companion experience
- Wear OS companion app
- More per-widget configuration options
- Optional custom widget themes
- Vehicle nickname and multi-vehicle widget selection

## Privacy

See [PRIVACY.md](PRIVACY.md) for details about local storage, connected-car API communication, and what to remove before sharing logs or screenshots.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

## Disclaimer

This app is **not affiliated with Hyundai Motor Company or Kia Corporation**. It communicates with the same API endpoints used by the official apps. Use at your own risk. The authors take no responsibility for any unintended vehicle actions.
