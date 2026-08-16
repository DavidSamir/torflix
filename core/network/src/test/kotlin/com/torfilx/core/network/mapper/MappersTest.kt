package com.torfilx.core.network.mapper

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.MediaType
import com.torfilx.core.model.SourceKind
import com.torfilx.core.model.SubtitleFormat
import com.torfilx.core.network.dto.HomeRowDto
import com.torfilx.core.network.dto.ImagesDto
import com.torfilx.core.network.dto.MarkersDto
import com.torfilx.core.network.dto.MediaItemDto
import com.torfilx.core.network.dto.MediaSourceDto
import com.torfilx.core.network.dto.SubtitleDto
import com.torfilx.core.network.dto.TimeRangeDto
import org.junit.Test

class MappersTest {

    @Test
    fun `item mapping fills sensible defaults for a sparse server response`() {
        val dto = MediaItemDto(id = "m1")
        val item = dto.toDomain()

        assertThat(item.title).isEqualTo("Untitled")
        assertThat(item.sortTitle).isEqualTo("Untitled")
        assertThat(item.type).isEqualTo(MediaType.MOVIE)
        assertThat(item.genres).isEmpty()
        assertThat(item.updatedAtMs).isEqualTo(0L)
    }

    @Test
    fun `series is recognised under either spelling`() {
        assertThat(MediaItemDto(id = "s", type = "show").toDomain().type).isEqualTo(MediaType.SHOW)
        assertThat(MediaItemDto(id = "s", type = "SERIES").toDomain().type).isEqualTo(MediaType.SHOW)
        assertThat(MediaItemDto(id = "m", type = "movie").toDomain().type).isEqualTo(MediaType.MOVIE)
    }

    @Test
    fun `blank genres and images are dropped rather than rendered as empty chips`() {
        val dto = MediaItemDto(
            id = "m1",
            title = "Film",
            genres = listOf("Action", "", "  "),
            images = ImagesDto(poster = "http://p"),
        )
        val item = dto.toDomain()
        // Empty *and* whitespace-only genres are dropped: both render as a blank chip.
        assertThat(item.genres).containsExactly("Action")
        assertThat(item.images.poster).isEqualTo("http://p")
        assertThat(item.images.backdrop).isNull()
    }

    @Test
    fun `timestamps parse the common iso forms and reject junk`() {
        assertThat("2024-05-01T12:00:00Z".toEpochMillisOrNull()).isEqualTo(1_714_564_800_000L)
        assertThat("2024-05-01T12:00:00.500Z".toEpochMillisOrNull()).isEqualTo(1_714_564_800_500L)
        assertThat("2024-05-01T14:00:00+02:00".toEpochMillisOrNull()).isEqualTo(1_714_564_800_000L)
        assertThat("2024-05-01T12:00:00".toEpochMillisOrNull()).isEqualTo(1_714_564_800_000L)
        assertThat("not a date".toEpochMillisOrNull()).isNull()
        assertThat("".toEpochMillisOrNull()).isNull()
        assertThat(null.toEpochMillisOrNull()).isNull()
    }

    @Test
    fun `iso round trip is stable`() {
        val ms = 1_714_564_800_000L
        assertThat(ms.toIsoUtc().toEpochMillisOrNull()).isEqualTo(ms)
    }

    @Test
    fun `home row types map to the kinds the app treats specially`() {
        assertThat(HomeRowDto("r", type = "continue-watching").toDomain().kind)
            .isEqualTo(HomeRowKind.CONTINUE_WATCHING)
        assertThat(HomeRowDto("r", type = "my-list").toDomain().kind).isEqualTo(HomeRowKind.MY_LIST)
        assertThat(HomeRowDto("r", type = "something-new").toDomain().kind).isEqualTo(HomeRowKind.GENERIC)
    }

    @Test
    fun `source mapping understands hls and hdr flavours`() {
        val hls = MediaSourceDto(id = "s", kind = "HLS", url = "http://x.m3u8", hdr = "hdr10+")
        val mapped = hls.toDomain()
        assertThat(mapped.kind).isEqualTo(SourceKind.HLS)
        assertThat(mapped.hdr).isEqualTo(HdrType.HDR10_PLUS)

        val direct = MediaSourceDto(id = "d", url = "http://x.mkv", hdr = "dolbyvision", dolbyVisionProfile = 7)
        assertThat(direct.toDomain().kind).isEqualTo(SourceKind.DIRECT)
        assertThat(direct.toDomain().hdr).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(direct.toDomain().dolbyVisionProfile).isEqualTo(7)
    }

    @Test
    fun `subtitles without a url are treated as embedded and bitmap formats are flagged`() {
        val embedded = SubtitleDto(id = "s1", lang = "en").toDomain()
        assertThat(embedded.isEmbedded).isTrue()

        val pgs = SubtitleDto(id = "s2", format = "pgs", embedded = true).toDomain()
        assertThat(pgs.isBitmap).isTrue()

        val sidecar = SubtitleDto(id = "s3", url = "http://x.vtt").toDomain()
        assertThat(sidecar.format).isEqualTo(SubtitleFormat.VTT)
        assertThat(sidecar.isEmbedded).isFalse()
        assertThat(sidecar.isBitmap).isFalse()
    }

    @Test
    fun `markers accept both the credits range and the bare start form`() {
        val withRange = MarkersDto(credits = TimeRangeDto(5_000, 6_000)).toDomain()
        assertThat(withRange.creditsStartMs).isEqualTo(5_000)

        val withStart = MarkersDto(creditsStart = 7_000).toDomain()
        assertThat(withStart.creditsStartMs).isEqualTo(7_000)

        assertThat(null.toDomain().creditsStartMs).isNull()
    }

    @Test
    fun `a single malformed entry is skipped instead of failing the whole list`() {
        val items = listOf("good", "bad", "good2")
        val mapped = mapItemsSkippingBad(items) { value ->
            require(value != "bad") { "boom" }
            value.uppercase()
        }
        assertThat(mapped).containsExactly("GOOD", "GOOD2").inOrder()
    }
}
