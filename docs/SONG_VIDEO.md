# Song + Video search/add

Guests can queue either the audio track or the Tidal music video for a song from catalog search.

## UX

- **Search all Tidal** (and artist drill-down) returns merged rows: one hit per song with optional **Song** and **Video** buttons.
- A row may have only Song, only Video, or both.
- **Karaoke library** browse stays a flat list. Playlist items that are already videos show a **Video** add button (no counterpart lookup).
- Queue and now-playing show a small **Video** cue when `mediaType` is `video`.
- Song and video use distinct Tidal IDs, so both may be queued independently.

## Catalog pairing

Search requests `include=tracks.artists,tracks.albums,videos.artists,videos.thumbnailArt`.

`pairSearchHits(tracks, videos)`:

1. Normalize titles (lowercase, strip punctuation, drop suffixes like “Official Video”, “Music Video”, “Lyric Video”, “Official Audio”).
2. Match artist by `artistId` when both sides have one; otherwise by normalized artist name.
3. Attach at most one video per track.
4. Leftover videos become video-only hits.

Artist browse fetches `/artists/{id}/relationships/tracks` and `/videos`, then applies the same pairing.

## Data model

`TrackRef.mediaType` is `"track"` (default) or `"video"`. `tidalTrackId` holds the Tidal resource id for either type.

`POST /api/search` and `GET /api/artists/{id}/tracks` respond with:

```json
{ "results": [ { "title", "artist", "album", "artworkUrl", "artistId", "song", "video" } ] }
```

`POST /api/queue` still accepts a single `TrackRef` (the button that was tapped).

Attribution: `"{name} added {title} by {artist}"` vs `"{name} added video {title} by {artist}"`.

Heart-to-library posts the correct JSON:API type (`tracks` or `videos`) into the karaoke playlist.

## Playback

Videos are started with an Activity deep link (confirmed on Google TV):

```text
adb shell am start -a android.intent.action.VIEW -d "https://tidal.com/browse/video/{id}"
```

`TidalMediaControllerBridge.playTrack` for `mediaType == video`:

1. Pauses current audio (does **not** open Tidal’s TV home launcher first — that races the video player)
2. `Intent.ACTION_VIEW` → `https://tidal.com/browse/video/{id}` (package `com.aspiro.tidal`), then optional `tidal://video/{id}`
3. Waits for MediaSession metadata to match (id, title, or normalized “Official Video” title); retries the VIEW once if it does not
4. Returns false if it never matches so the party can say “Could not start …” instead of advancing the queue
5. Does **not** fall back to `playFromUri` / `playFromSearch` (those start audio)

Audio tracks still use `playFromUri` then `playFromSearch`.

After a video is playing, pause/skip/position still go through MediaController. Tidal’s MediaSession media id often differs from the catalog id for **audio and video**; party reclaim treats the queued item as still owned when the session title/artist matches (including “Official Video” suffixes) or the ids match after stripping URI / country suffixes. Videos also get a longer launch-grace window. Stale session events from an already-sung audio track are ignored so they cannot skip the video that is starting.
