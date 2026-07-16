# Robot Karaoke — substitute lyrics (future implementation)

> Status: design only. Nothing in this document is implemented yet.

## Concept

"Robot karaoke" is singing a song with **substitute lyrics**: a new set of words
(written by a person or an LLM) that preserve the original song's meter and
syllable counts, so they can be sung over the original recording. Since guest
phones already display synced lyrics from [LRCLIB](https://lrclib.net/docs),
the natural extension is letting guests pick a substitute version instead of
the official lyrics — the timing stays the same, only the words change.

## Goals

- Guests can **submit** substitute lyrics for any track from their phone
  (write or paste; they can use any LLM they like on their own).
- Guests can **browse and pick** from multiple named versions per track when
  singing.
- Versions are stored on a **shared community server** (LRCLIB-style), so
  submissions outlive a single party and are shared across all installs.
- Line-for-line versions reuse LRCLIB's timestamps for synced display;
  freeform plain-text versions are also allowed.

## Non-goals (for now)

- Built-in LLM generation inside the app (paste-first; may come later).
- Accounts/authentication for submitters — a display name string is enough
  initially, like the party's join-with-a-name model.
- Replacing lyrics on the TV screen. Tidal's on-TV lyrics UI shows official
  lyrics only; substitute lyrics live on guest phones.

## Architecture

```
guest phone ──► PartyServer (TV) ──► Robot Karaoke server (community, HTTPS)
                     │
                     └──► LRCLIB (timing + original lyrics)
```

A small hosted service ("Robot Karaoke server") modeled on LRCLIB:

- Public read API, open submit API (rate-limited), no accounts to start.
- The **TV app proxies** requests: phones talk only to `PartyServer` on the
  LAN, and `PartyServer` calls the community server. This keeps phones
  off the internet path (same pattern as Tidal search and LRCLIB today),
  lets the TV cache responses per party, and gives one place to rate-limit.

### Track identity

LRCLIB keys tracks by `(track name, artist name, album name, duration)`. We
should use the same natural key so a substitute version found via one music
service matches another. Additionally store, when known:

- `tidalTrackId` — we always have this at queue time.
- `lrclibId` — links a line-for-line version to the exact LRCLIB record whose
  timestamps it was written against. If LRCLIB later changes its synced
  lyrics for the track, we can detect drift (line count mismatch) and fall
  back to plain display.

### Data model (server)

```
Version
  id              server-assigned
  trackName       ─┐
  artistName       ├ natural key (LRCLIB-style)
  albumName        │
  duration         ─┘
  tidalTrackId    optional
  lrclibId        optional; set for line-for-line versions
  title           e.g. "Bohemian Catsody", "IT Department Rhapsody"
  author          display name, free text
  kind            "linefor" | "freeform"
  lines           for linefor: array of strings, same count as the LRCLIB
                  synced lyrics; timestamps are NOT stored (always taken
                  from LRCLIB at display time)
  text            for freeform: plain text blob
  language        optional
  createdAt
  reportCount     for moderation (see below)
```

Storing line-for-line versions **without timestamps** is deliberate: the
original synced lyrics are the single source of truth for timing, versions
can't drift out of sync with themselves, and the editor/display logic is a
simple `zip(timestamps, substituteLines)`.

### API sketch

```
GET  /api/versions?track_name=&artist_name=&album_name=&duration=
       → list of Version summaries (id, title, author, kind, createdAt)
GET  /api/versions/{id}
       → full Version
POST /api/versions
       → submit; body is the Version fields minus id/createdAt/reportCount
POST /api/versions/{id}/report
       → flag abusive content
```

Submissions validate:

- required fields present, size caps (e.g. lines ≤ 200, each ≤ 250 chars)
- for `kind = linefor`: line count must equal the referenced LRCLIB synced
  lyric line count at submit time (checked server-side against LRCLIB)
- basic rate limiting per IP

### App integration

`PartyServer` (on the TV) gains proxy endpoints mirroring the above, plus the
existing LRCLIB lookup:

```
GET  /party/robot-lyrics?trackId=…         → versions for the current track
GET  /party/robot-lyrics/{versionId}       → full version, merged with timing
POST /party/robot-lyrics                   → submit (guest must have joined)
```

A new `RobotKaraokeClient` sits next to `LrcLibClient`. Responses are cached
per track for the party's lifetime.

## Guest experience

### Singing

1. Phone's lyrics view fetches LRCLIB lyrics as today.
2. If substitute versions exist for the track, show a picker:
   **Official** / *version title — by author* (newest first).
3. Picking a `linefor` version swaps each line's text but keeps LRCLIB
   timestamps — synced display works unchanged.
4. Picking a `freeform` version shows static scrollable text (no sync).
5. If a `linefor` version's line count no longer matches LRCLIB (drift),
   degrade to static display rather than mis-synced lines.

### Submitting

1. From the lyrics view, "Write robot lyrics" opens an editor.
2. **Line-for-line mode** (preferred): two-column view, each original line
   next to an input for the substitute line. Original line's syllable count
   shown as a hint (heuristic counter; a warning, never a blocker — this is
   "loose" by design).
3. **Freeform mode**: one big text box for pasting anything.
4. Guest names the version and submits under their party display name.
5. Version is immediately available to everyone at the party (TV cache is
   updated optimistically while the community server processes it).

## Party integration

- Queue adds can optionally reference a version id: "*Kim added Bohemian
  Catsody (robot lyrics by Pat) — Bohemian Rhapsody by Queen*".
- Attribution messages for submissions: "*Pat wrote robot lyrics for
  Bohemian Rhapsody*".

## Moderation & abuse (open, must resolve before launch)

An open-submission lyrics server will attract spam and abuse.
Minimum viable approach:

- `report` endpoint + auto-hide above a report threshold
- size caps and rate limiting on submit
- no URLs allowed in lyric lines
- an admin delete endpoint behind a token

## Copyright note

Substitute lyrics are derivative works of the original composition. Parody
has meaningful fair-use protection (in the US), but non-parody rewrites are
murkier. The server should carry a takedown contact and honor removal
requests. Worth a closer look before the community server is public.

## Open questions

- **Hosting**: who runs the community server, on what stack? (A tiny
  API + SQLite/Postgres is enough; LRCLIB itself is a good template and is
  open source.)
- **Fallback**: should the TV keep a local cache of versions used at past
  parties so robot karaoke works if the community server is down?
- **Duplicate control**: dedupe identical submissions? Allow editing your
  own version (needs some identity, e.g. an edit token returned on submit)?
- **LLM assist (later)**: a "generate a draft" button using a configurable
  API key, seeding the line-for-line editor with the original lyrics,
  syllable counts, and a topic prompt.

## Implementation phases (later, separate plans)

1. **Phase 0 — party-local prototype**: submissions stored in `PartySession`
   memory only, picker + editor UX proven at a real party. No server.
2. **Phase 1 — community server**: stand up the hosted API, move storage
   there, add moderation basics.
3. **Phase 2 — polish**: syllable hints, queue-add with version reference,
   drift detection, TV-side caching.
