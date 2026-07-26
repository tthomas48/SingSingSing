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

`TidalMediaControllerBridge.playTrack` tries:

- `tidal://video/{id}` / `https://tidal.com/browse/video/{id}` / `https://tidal.com/video/{id}` for videos
- the existing track URI set for audio

Then falls back to `playFromSearch` with video vs audio media-focus extras. Which URI the Tidal TV `MediaSession` accepts should be confirmed on device; keep the working candidates.
