package com.singsingsing.party

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class LibraryRandomTest {
    private fun track(id: String, artist: String, title: String = "Song $id") =
        TrackRef(tidalTrackId = id, title = title, artist = artist)

    @Test
    fun excludesActiveIds() {
        val tracks = listOf(track("1", "A"), track("2", "B"), track("3", "C"))
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = setOf("1", "3"),
            count = 5,
            random = Random(1),
        )
        assertThat(picked.map { it.tidalTrackId }).containsExactly("2")
    }

    @Test
    fun capsCountAtTwenty() {
        val tracks = (1..25).map { track(it.toString(), "Artist $it") }
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = emptySet(),
            count = 100,
            random = Random(2),
        )
        assertThat(picked).hasSize(20)
    }

    @Test
    fun returnsFewerWhenLibraryIsSmall() {
        val tracks = listOf(track("1", "A"), track("2", "B"))
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = emptySet(),
            count = 5,
            random = Random(3),
        )
        assertThat(picked).hasSize(2)
    }

    @Test
    fun returnsEmptyWhenNothingEligible() {
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = listOf(track("1", "A")),
            excludeIds = setOf("1"),
            count = 5,
            random = Random(4),
        )
        assertThat(picked).isEmpty()
    }

    @Test
    fun shuffleIsDeterministicWithSeed() {
        val tracks = (1..10).map { track(it.toString(), "Artist $it") }
        val first = LibraryRandom.selectRandomLibraryTracks(tracks, emptySet(), 5, Random(42))
        val second = LibraryRandom.selectRandomLibraryTracks(tracks, emptySet(), 5, Random(42))
        assertThat(first.map { it.tidalTrackId }).isEqualTo(second.map { it.tidalTrackId })
    }

    @Test
    fun prefersOneTrackPerArtistWhenEnoughArtistsExist() {
        val tracks = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon").flatMap { artist ->
            (1..3).map { n -> track("$artist-$n", artist, "$artist $n") }
        }
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = emptySet(),
            count = 5,
            random = Random(7),
        )
        assertThat(picked).hasSize(5)
        assertThat(picked.map { LibraryRandom.artistKey(it.artist) }.toSet()).hasSize(5)
    }

    @Test
    fun oneArtistLibraryCanStillFillFive() {
        val tracks = (1..8).map { track(it.toString(), "Only") }
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = emptySet(),
            count = 5,
            random = Random(8),
        )
        assertThat(picked).hasSize(5)
        assertThat(picked.map { it.artist }.toSet()).containsExactly("Only")
    }

    @Test
    fun twoArtistsFillRemainingAfterOneEach() {
        val tracks = (1..10).map { track("a$it", "Alpha") } +
            (1..10).map { track("b$it", "Beta") }
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = emptySet(),
            count = 5,
            random = Random(9),
        )
        assertThat(picked).hasSize(5)
        val artists = picked.map { LibraryRandom.artistKey(it.artist) }.toSet()
        assertThat(artists).containsExactly("alpha", "beta")
        val counts = picked.groupingBy { LibraryRandom.artistKey(it.artist) }.eachCount()
        assertThat(counts.values.max()).isLessThan(5)
    }

    @Test
    fun artistMatchIsCaseInsensitive() {
        val tracks = listOf(
            track("1", "The Band"),
            track("2", "the band"),
            track("3", "Other"),
        )
        val picked = LibraryRandom.selectRandomLibraryTracks(
            tracks = tracks,
            excludeIds = emptySet(),
            count = 2,
            random = Random(0),
        )
        assertThat(picked).hasSize(2)
        assertThat(picked.map { LibraryRandom.artistKey(it.artist) }.toSet()).containsExactly("the band", "other")
    }
}
