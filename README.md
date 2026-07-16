# Sing Tidal To Me

Jackbox-style sing-along companion for **Tidal on Google TV**.

The Android TV app shows a QR code. Guests on the same Wi‑Fi open the page, join with a name, search Tidal, and manage a shared singing queue. The TV app drives the official Tidal app through Android `MediaController` APIs (no adb in production).

## Prerequisites

- JDK 17+
- Android SDK (`local.properties` → `sdk.dir`)
- Google TV with wireless debugging enabled
- Tidal developer app credentials from [developer.tidal.com](https://developer.tidal.com)

## Configure

```properties
# local.properties
sdk.dir=/home/you/Android/Sdk
TIDAL_CLIENT_ID=your_client_id
TIDAL_CLIENT_SECRET=your_client_secret
PARTY_PORT=8787
TIDAL_COUNTRY_CODE=US
```

## Build

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
./gradlew test
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

## Sideload to Google TV

From the machine that already has `adb` connected to the TV (your Windows host works):

```powershell
adb install -r app-debug.apk
adb shell am start -n com.singtidaltome.debug/com.singtidaltome.ui.JoinActivity
```

## First-run setup on the TV

1. Open **Sing Tidal To Me**.
2. Grant **notification access** (required to control Tidal's media session).
3. Optionally enable the **Open Tidal lyrics** accessibility service.
4. Start Tidal and play anything once so a media session exists.
5. Scan the QR code from phones on the same Wi‑Fi.

## Guest features

- Join with a name
- Search Tidal catalog
- Add songs to the shared queue with attribution messages
- Play / pause / skip / previous
- Load synced lyrics on the phone (LRCLIB)

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/BUSINESS_LOGIC.md](docs/BUSINESS_LOGIC.md)
- [docs/ROBOT_KARAOKE.md](docs/ROBOT_KARAOKE.md) — future: substitute-lyrics ("robot karaoke") design
