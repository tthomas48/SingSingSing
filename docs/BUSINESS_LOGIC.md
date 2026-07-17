# Business logic

## Party queue

- The sing-along queue is owned by this app, not by Tidal's internal queue.
- Guests may only mutate the queue after joining with a display name.
- The session keeps the **full ordered list** of songs for the party: already sung, now playing, and up next.
- The first track added while nothing is playing starts immediately.
- Later adds wait in "Up next".
- Guests may not add a track that is already now playing or in "Up next" (already-sung history may be re-queued).
- Skip / next moves the playhead forward; sung songs remain in history.
- Previous moves the playhead back through history.
- Reorder moves an upcoming item within "Up next" only, via per-item up/down buttons (up on the left, down at the far right after heart/play).
- Jump-to-track sets the playhead to any session item without discarding others.
- When the current track is within ~2.5s of its end and something is up next, we **proactively** start the next party track (pause first) so Tidal autoplay does not win the race.
- While launching a party track, brief foreign MediaSession metadata is ignored.
- Stale MediaSession events whose track ID is already in history (already sung) are ignored so they cannot overwrite position or trigger a second skip.
- Near-end advance only runs when the MediaSession track ID matches the party now-playing track.
- If Tidal still switches to a different track ID afterward (not one we already finished), we pause and reclaim with our next queued track when one exists (we do not call Tidal's skip-to-next / radio).
- Guest UI shows history + up next in a fixed-height viewport with the next track pinned at the top; scroll up for already-sung (dimmed) songs.
- The full session queue (history, current item, and upcoming items) is persisted on the TV and restored after process death or an `adb install -r` deployment. Restore never auto-starts playback.

## Attribution

- Every queue add produces a party message: "`{name} added {title} by {artist}`".
- Join / skip / previous / reorder / jump / heart also emit short fun messages for the chatter feed.
- Guests can post their own chatter from a compose box; user messages use the same narrative attribution: "`{name} says {text}`" (trimmed, capped at 120 chars, blank rejected).
- The guest UI splits Session queue and Party chatter into tabs; the chatter tab uses a fixed-height viewport (~5–8 lines) like the queue, with the compose box below it.
- New chatter messages surface as toasts everywhere so guests don't need the chatter tab open: a toast on every guest phone (deduped by message id, backlog not re-toasted on connect) and an Android `Toast` on the TV.
- New guest joins produce a high-priority Android TV notification so arrivals are visible while Tidal has focus.
- Messages are capped (newest kept) so the feed stays readable on phones.

## Search & playback

- Search uses Tidal catalog OAuth (client credentials). Credentials live in `local.properties` and are compiled into `BuildConfig`.
- Catalog search returns **tracks only**, with nested artist/album includes so artist names resolve correctly.
- Guests can tap an artist on a search result to browse that artist's tracks.
- Search failures are surfaced as toast-friendly errors; the client does not auto-retry — guests decide when to try again.
- Playback prefers `MediaController.playFromUri` with Tidal track URIs, then falls back to `playFromSearch` with title/artist extras (the path proven via adb/`MEDIA_PLAY_FROM_SEARCH`).

## Karaoke library

- The host signs into Tidal on the TV via Authorization Code + PKCE (phone opens the login URL / QR; callback hits the TV party server). Device-code login is not available to third-party developer apps.
- The access token, refresh token, expiry, user id, and selected library playlist are persisted on the TV. Expired access tokens are refreshed automatically, and an `adb install -r` deployment preserves this data.
- A full uninstall or clearing app data removes both the Tidal login and the persisted party queue.
- Redirect URI must be registered in the Tidal developer portal and match `http://<tv-lan-ip>:<port>/oauth/callback`.
- After sign-in, the host picks one of their playlists as the karaoke library.
- Guests open a full-screen Add Song modal that auto-loads the full karaoke library browse list (filter is optional; Enter submits the filter). "Search all Tidal" is the fallback for songs not in the library.
- A heart on each queued track appends that track to the configured library playlist (`playlists.write`) and updates the shared "in library" set for everyone.
- Library tracks are cached in TV memory and on disk after load / heart / playlist change, so cold starts and repeat opens skip re-paginating Tidal. Guests also keep a session cache of the last full library response for instant modal reopen.

## Lyrics

- On successful track start, the accessibility service attempts to click Tidal's `lyricsButton`.
- Guests can also tap **Open Lyrics** to request that click on demand.
- Next to the now-playing heart, guests can tap the lyrics icon to fetch LRCLIB lyrics for the current track and open a live-synced modal.
- When LRCLIB returns LRC-timed lines, the server parses sync codes into `{ timeMs, text }` and the phone bolds the current line from WebSocket `nowPlaying.positionMs` (with client-side interpolation between snapshot updates).
- If the now-playing track changes while the modal is open, the guest must open lyrics again for the new track.
