# Business logic

## Party queue

- The sing-along queue is owned by this app, not by Tidal's internal queue.
- Guests may only mutate the queue after joining with a display name.
- The first track added while nothing is playing starts immediately.
- Later adds wait in "Up next".
- Skip discards the current party track and starts the next party track.
- Previous restores the last party track and pushes the interrupted current track to the front of the queue.
- When Tidal advances on its own to a different track ID, we reclaim control by starting our next queued track if one exists.

## Attribution

- Every queue add produces a party message: "`{name} added {title} by {artist}`".
- Join / skip / previous also emit short fun messages for the chatter feed.
- Messages are capped (newest kept) so the feed stays readable on phones.

## Search & playback

- Search uses Tidal catalog OAuth (client credentials). Credentials live in `local.properties` and are compiled into `BuildConfig`.
- Playback prefers `MediaController.playFromUri` with Tidal track URIs, then falls back to `playFromSearch` with title/artist extras (the path proven via adb/`MEDIA_PLAY_FROM_SEARCH`).

## Lyrics

- On successful track start, the accessibility service attempts to click Tidal's `lyricsButton`.
- Guest phones can separately load synced lyrics from LRCLIB for the current track.
