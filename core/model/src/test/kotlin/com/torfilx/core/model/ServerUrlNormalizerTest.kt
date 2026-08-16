package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerUrlNormalizerTest {

    @Test
    fun `bare ip gets a scheme and a trailing slash`() {
        assertThat(ServerUrlNormalizer.normalize("192.168.1.10")).isEqualTo("http://192.168.1.10/")
    }

    @Test
    fun `default port is applied only when the user did not type one`() {
        assertThat(ServerUrlNormalizer.normalize("192.168.1.10", defaultPort = 8096))
            .isEqualTo("http://192.168.1.10:8096/")
        assertThat(ServerUrlNormalizer.normalize("192.168.1.10:9000", defaultPort = 8096))
            .isEqualTo("http://192.168.1.10:9000/")
    }

    @Test
    fun `existing scheme host and path are preserved with exactly one trailing slash`() {
        assertThat(ServerUrlNormalizer.normalize("https://media.home/api/")).isEqualTo("https://media.home/api/")
        assertThat(ServerUrlNormalizer.normalize("https://media.home/api")).isEqualTo("https://media.home/api/")
        assertThat(ServerUrlNormalizer.normalize("http://nas.local///")).isEqualTo("http://nas.local/")
    }

    @Test
    fun `case of the scheme is normalised`() {
        assertThat(ServerUrlNormalizer.normalize("HTTP://Nas.local")).isEqualTo("http://Nas.local/")
    }

    @Test
    fun `garbage input is rejected instead of producing a broken base url`() {
        assertThat(ServerUrlNormalizer.normalize("")).isNull()
        assertThat(ServerUrlNormalizer.normalize("   ")).isNull()
        assertThat(ServerUrlNormalizer.normalize("my server")).isNull()
        assertThat(ServerUrlNormalizer.normalize("ftp://nas.local")).isNull()
        assertThat(ServerUrlNormalizer.normalize("http://")).isNull()
        assertThat(ServerUrlNormalizer.normalize("http:///path")).isNull()
        assertThat(ServerUrlNormalizer.normalize("nas.local:70000")).isNull()
        assertThat(ServerUrlNormalizer.normalize("nas.local:abc")).isNull()
    }
}
