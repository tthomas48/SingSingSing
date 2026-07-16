# Architecture

Sing Tidal To Me is an all-in-one Android TV app that hosts a Jackbox-style party and drives the official Tidal TV app.

## Components

- **JoinActivity** — TV-facing screen with QR code / LAN URL and permission shortcuts.
- **PartyForegroundService** — keeps the embedded HTTP/WebSocket server and media bridge alive.
- **PartyServer (Ktor CIO)** — serves the guest web UI and JSON APIs on the LAN.
- **PartySession / PartyQueue** — party state: guests, attribution messages, and the sing-along queue we own.
- **TidalAuthClient / TidalCatalogClient** — OAuth client-credentials (+ optional device-code) and catalog search against `openapi.tidal.com`.
- **TidalMediaControllerBridge** — obtains Tidal's `MediaController` via notification-listener access and issues play/pause/skip/`playFromUri`/`playFromSearch`/`skipToQueueItem`.
- **LyricsAccessibilityService** — best-effort click of Tidal's `lyricsButton`.
- **LrcLibClient** — synced/plain lyrics for guest phones via [LRCLIB](https://lrclib.net/docs).

## Data flow

1. Guests join `http://<tv-ip>:8787`, enter a name, and search Tidal.
2. Search hits the official Tidal API with an app token.
3. Adding a track updates our party queue and asks the bridge to start that track on Tidal.
4. Transport controls and auto-advance keep our queue authoritative even if Tidal would continue its own queue.
5. Live state is pushed to phones over WebSocket.

## Permissions

- **Notification access** — required for third-party `MediaSession` control.
- **Accessibility** — optional; used only to open Tidal's on-TV lyrics UI.

## Confirmed Tidal integration points

See investigation dumps (`tidal_package_dump.txt`, `tidal_ui.txt` if kept locally):

- Package `com.aspiro.tidal`, session `MusicService`
- Actions include play/pause/next/previous/seek/`PLAY_FROM_SEARCH`/`PLAY_FROM_URI`
- TV now-playing exposes `app:id/lyricsButton`
