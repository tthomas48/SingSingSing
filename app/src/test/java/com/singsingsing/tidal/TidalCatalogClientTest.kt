package com.singsingsing.tidal

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class TidalCatalogClientTest {
    @Test
    fun parseSearchMapsOrderedTracksAndIgnoresJunkIncludedTracks() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = SearchApiResponse(
            data = SearchData(
                id = "wolf",
                type = "searchResults",
                relationships = ResourceRelationships(
                    tracks = RelationshipList(
                        listOf(
                            RelationshipRef("95574931", "tracks"),
                            RelationshipRef("2", "tracks"),
                        ),
                    ),
                ),
            ),
            included = listOf(
                IncludedResource(
                    id = "95574931",
                    type = "tracks",
                    attributes = ResourceAttributes(
                        title = "Wolf Like Me",
                        duration = JsonPrimitive(201.0),
                    ),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                        albums = RelationshipList(listOf(RelationshipRef("9", "albums"))),
                    ),
                ),
                IncludedResource(
                    id = "2",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Other Song"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                // Album-related junk that must not appear unless listed in relationships.tracks
                IncludedResource(
                    id = "999",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Album Filler"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "1",
                    type = "artists",
                    attributes = ResourceAttributes(name = "TV On The Radio"),
                ),
                IncludedResource(
                    id = "9",
                    type = "albums",
                    attributes = ResourceAttributes(
                        title = "Return To Cookie Mountain",
                        imageLinks = listOf(ImageLink(href = "https://example.com/art.jpg")),
                    ),
                ),
            ),
        )

        val tracks = client.parseSearch(response)
        assertThat(tracks.map { it.tidalTrackId }).containsExactly("95574931", "2").inOrder()
        assertThat(tracks[0].title).isEqualTo("Wolf Like Me")
        assertThat(tracks[0].artist).isEqualTo("TV On The Radio")
        assertThat(tracks[0].artistId).isEqualTo("1")
        assertThat(tracks[0].album).isEqualTo("Return To Cookie Mountain")
        assertThat(tracks[0].artworkUrl).isEqualTo("https://example.com/art.jpg")
        assertThat(tracks[0].durationSeconds).isEqualTo(201)
        assertThat(tracks.map { it.title }).doesNotContain("Album Filler")
    }

    @Test
    fun parsePlaylistItemsPreservesRelationshipOrder() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = RelationshipItemsResponse(
            data = listOf(
                RelationshipRef("2", "tracks"),
                RelationshipRef("1", "tracks"),
            ),
            included = listOf(
                IncludedResource(
                    id = "1",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "First"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "2",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Second"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "a",
                    type = "artists",
                    attributes = ResourceAttributes(name = "Band"),
                ),
            ),
        )

        val tracks = client.parsePlaylistItems(response)
        assertThat(tracks.map { it.tidalTrackId }).containsExactly("2", "1").inOrder()
        assertThat(tracks.map { it.title }).containsExactly("Second", "First").inOrder()
    }

    @Test
    fun parseSearchHitsPairsVideosAndLeavesUnmatchedVideos() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = SearchApiResponse(
            data = SearchData(
                id = "wolf",
                type = "searchResults",
                relationships = ResourceRelationships(
                    tracks = RelationshipList(
                        listOf(RelationshipRef("95574931", "tracks")),
                    ),
                    videos = RelationshipList(
                        listOf(
                            RelationshipRef("v1", "videos"),
                            RelationshipRef("v2", "videos"),
                        ),
                    ),
                ),
            ),
            included = listOf(
                IncludedResource(
                    id = "95574931",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Wolf Like Me"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "v1",
                    type = "videos",
                    attributes = ResourceAttributes(title = "Wolf Like Me (Official Video)"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                        thumbnailArt = RelationshipList(listOf(RelationshipRef("art1", "artworks"))),
                    ),
                ),
                IncludedResource(
                    id = "v2",
                    type = "videos",
                    attributes = ResourceAttributes(title = "Other Clip"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "1",
                    type = "artists",
                    attributes = ResourceAttributes(name = "TV On The Radio"),
                ),
                IncludedResource(
                    id = "art1",
                    type = "artworks",
                    attributes = ResourceAttributes(
                        imageLinks = listOf(ImageLink(href = "https://example.com/video.jpg")),
                    ),
                ),
            ),
        )

        val hits = client.parseSearchHits(response)
        assertThat(hits).hasSize(2)
        assertThat(hits[0].song?.tidalTrackId).isEqualTo("95574931")
        assertThat(hits[0].video?.tidalTrackId).isEqualTo("v1")
        assertThat(hits[0].video?.artworkUrl).isEqualTo("https://example.com/video.jpg")
        assertThat(hits[0].video?.mediaType).isEqualTo("video")
        assertThat(hits[1].song).isNull()
        assertThat(hits[1].video?.tidalTrackId).isEqualTo("v2")
    }

    @Test
    fun parsePlaylistItemsIncludesVideosInOrder() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = RelationshipItemsResponse(
            data = listOf(
                RelationshipRef("v9", "videos"),
                RelationshipRef("1", "tracks"),
            ),
            included = listOf(
                IncludedResource(
                    id = "1",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Audio"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "v9",
                    type = "videos",
                    attributes = ResourceAttributes(title = "Clip"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "a",
                    type = "artists",
                    attributes = ResourceAttributes(name = "Band"),
                ),
            ),
        )

        val items = client.parsePlaylistItems(response)
        assertThat(items.map { it.tidalTrackId }).containsExactly("v9", "1").inOrder()
        assertThat(items[0].mediaType).isEqualTo("video")
        assertThat(items[1].mediaType).isEqualTo("track")
    }

    @Test
    fun playlistIsoDurationDoesNotBreakAttributeParsing() {
        val attrs = ResourceAttributes(
            name = "sing-along time",
            duration = JsonPrimitive("PT22H24M41S"),
            numberOfItems = 326,
        )
        assertThat(attrs.durationSecondsOrZero()).isEqualTo(22 * 3600 + 24 * 60 + 41)
        assertThat(parseIso8601DurationSeconds("PT3M20S")).isEqualTo(200)
        assertThat(parseIso8601DurationSeconds("PT45S")).isEqualTo(45)
    }
}
