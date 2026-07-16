package com.singtidaltome.tidal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TidalCatalogClientTest {
    @Test
    fun parseSearchMapsIncludedTracksArtistsAndAlbums() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = SearchApiResponse(
            included = listOf(
                IncludedResource(
                    id = "95574931",
                    type = "tracks",
                    attributes = ResourceAttributes(
                        title = "Wolf Like Me",
                        duration = 201.0,
                    ),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                        albums = RelationshipList(listOf(RelationshipRef("9", "albums"))),
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
        assertThat(tracks).hasSize(1)
        assertThat(tracks[0].tidalTrackId).isEqualTo("95574931")
        assertThat(tracks[0].title).isEqualTo("Wolf Like Me")
        assertThat(tracks[0].artist).isEqualTo("TV On The Radio")
        assertThat(tracks[0].album).isEqualTo("Return To Cookie Mountain")
        assertThat(tracks[0].artworkUrl).isEqualTo("https://example.com/art.jpg")
        assertThat(tracks[0].durationSeconds).isEqualTo(201)
    }
}
