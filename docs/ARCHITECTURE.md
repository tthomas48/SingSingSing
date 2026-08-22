# Architecture

Sing Sing Sing is an all-in-one Android TV app that hosts a Jackbox-style party and drives the official Tidal TV app.

## Components

- **JoinActivity** — TV-facing screen with QR code / LAN URL, Open Tidal, a Wi‑Fi-missing warning, and a Settings menu (notification access, accessibility, party notifications, Tidal device login, karaoke library playlist picker).
- **PartyForegroundService** — keeps the embedded HTTP/WebSocket server and media bridge alive; notifies on new guests; watches LAN changes and republishes the join URL.
- **LanMonitor / LanAddressPicker** — prefers the WiFi IPv4 for the advertised join URL even when Ethernet is the default route; holds a WiFi `requestNetwork` so the radio can stay up as a secondary network.
- **PartyServer (Ktor CIO)** — serves the guest web UI and JSON APIs on the LAN (`0.0.0.0`). `GET /api/health` reports `advertisedHost`, `wifiHost`, `ethernetHost`, and `wifiAvailable`.
- **PartySession / PartyQueue / PartyQueueStore** — party state and the sing-along queue we own; the full queue/playhead is persisted in `SharedPreferences` and restored without auto-play.
- **TidalAuthClient / TidalTokenStore / TidalCatalogClient** — client-credentials for catalog search; Authorization Code + PKCE user OAuth (persisted refresh token) for playlist library read/write against `openapi.tidal.com`. Device-code login is unavailable to third-party Tidal apps.
- **TidalMediaControllerBridge** — obtains Tidal's `MediaController` via notification-listener access and issues play/pause/skip/`playFromUri`/`playFromSearch`/`skipToQueueItem`.
- **LyricsAccessibilityService** — best-effort click of Tidal's `lyricsButton` (auto on track start + guest **Open Lyrics**).
- **LrcLibClient** — synced/plain lyrics for guest phones via [LRCLIB](https://lrclib.net/docs).

## Data flow

1. Guests join `http://<tv-wifi-ip>:8787`, enter a name, and open the Add Song modal. The QR / join URL prefers the TV's WiFi IPv4; Ethernet is advertised only when WiFi has no usable address.
2. Library browse filters the host-selected Tidal playlist; full catalog search uses the app token and returns paired song/video hits.
3. Adding a track or video updates our party queue and asks the bridge to start that item on Tidal when nothing is playing.
4. Transport controls, reorder, jump-to-track, and auto-advance keep our queue authoritative.
5. Hearting a queued item appends that track or video to the library playlist via the user token.
6. Live state is pushed to phones over WebSocket.

The persisted party queue and Tidal OAuth refresh token survive process death and app upgrades (`adb install -r`). A full uninstall or clear-data removes both.

## Permissions

- **Notification access** — required for third-party `MediaSession` control.
- **CHANGE_NETWORK_STATE** — used to request that WiFi stay available as a secondary network when Ethernet is plugged in.
- **Accessibility** — optional; used to open Tidal's on-TV lyrics UI.
- **Tidal user OAuth** — optional; required for karaoke library browse and heart-to-playlist.

## Confirmed Tidal integration points

See investigation dumps (`tidal_package_dump.txt`, `tidal_ui.txt` if kept locally):

- Package `com.aspiro.tidal`, session `MusicService`
- Actions include play/pause/next/previous/seek/`PLAY_FROM_SEARCH`/`PLAY_FROM_URI`
- TV now-playing exposes `app:id/lyricsButton`
