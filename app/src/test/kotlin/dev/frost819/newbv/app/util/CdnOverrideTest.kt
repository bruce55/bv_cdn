package dev.frost819.newbv.app.util

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.entity.CdnOverrideCatalog
import org.junit.jupiter.api.Test

/** Regression cases for signed media URLs and the fork's CDN boundary fixes. */
class CdnOverrideTest {
    @Test
    fun `normalizes hosts ports and pasted URLs`() {
        for (input in listOf(
            "CDN.EXAMPLE.COM:443",
            " HTTPS://CDN.EXAMPLE.COM:443/video?x=1 ",
            "//CDN.EXAMPLE.COM:443/",
        )) {
            assertThat(CdnOverride.normalizeHost(input)).isEqualTo("cdn.example.com:443")
        }
    }

    @Test
    fun `rejects malformed or credential bearing destinations`() {
        for (input in listOf(
            "",
            "https://",
            "a b",
            "a\nb",
            "ftp://cdn.com",
            "https://user:pass@cdn.com",
            "cdn.com:0",
            "cdn.com:65536",
        )) {
            assertThat(CdnOverride.normalizeHost(input)).isEmpty()
        }
    }

    @Test
    fun `preserves signed paths query order percent escapes and fragments`() {
        val suffix = "/upgcxcode/a%2Fb.m4s?deadline=123&sign=A%2FB%2bC&x=1&x=2#part"
        for (source in listOf(
            "cn-hk-eq-01-01.bilivideo.com",
            "upos.bilivideo.cn",
            "v.acgvideo.com",
            "v.acgvideo.cn",
            "edge.mountaintoys.cn",
            "upos.akamaized.net",
        )) {
            assertThat(CdnOverride.apply("https://$source:443$suffix", "cdn.example.com:8443"))
                .isEqualTo("https://cdn.example.com:8443$suffix")
        }
    }

    @Test
    fun `does not rewrite API P2P unrelated or deceptive hosts`() {
        for (source in listOf(
            "api.bilivideo.com",
            "api2.bilivideo.com",
            "bvc.bilivideo.com",
            "data.bilivideo.com",
            "pbp.bilivideo.com",
            "a.mcdn.bilivideo.cn",
            "mcdn.bilivideo.com",
            "a.szbdyd.com",
            "127.0.0.1",
            "bilivideo.com.attacker.test",
            "fakebilivideo.com",
            "example.com",
        )) {
            val url = "https://$source/video?sign=123"
            assertThat(CdnOverride.apply(url, "cdn.example.com")).isEqualTo(url)
        }
    }

    @Test
    fun `default invalid destinations and invalid sources leave URL unchanged`() {
        val url = "http://a.bilivideo.com/video?sign=123"
        for (choice in listOf("", " ", "https://", "user@cdn.com")) {
            assertThat(CdnOverride.apply(url, choice)).isEqualTo(url)
        }
        for (source in listOf("broken url", "file://a.bilivideo.com/video", "https://user@a.bilivideo.com/video")) {
            assertThat(CdnOverride.apply(source, "cdn.example.com")).isEqualTo(source)
        }
        assertThat(CdnOverride.apply(url, "cdn.example.com")).isEqualTo("http://cdn.example.com/video?sign=123")
    }

    @Test
    fun `catalog resolves all presets and unknown choices`() {
        for ((region, nodes) in CdnOverrideCatalog.regionNodes) {
            assertThat(CdnOverrideCatalog.nodesForRegion(region)).isEqualTo(nodes)
            for (node in nodes) {
                assertThat(CdnOverrideCatalog.normalizeHost(node)).isEqualTo(node)
                assertThat(CdnOverrideCatalog.regionForHost(node)).isEqualTo(region)
            }
        }
        assertThat(CdnOverrideCatalog.regionForHost("")).isEqualTo(CdnOverrideCatalog.defaultRegion)
        assertThat(CdnOverrideCatalog.regionForHost("custom.example.com")).isEqualTo(CdnOverrideCatalog.customRegion)
        assertThat(CdnOverrideCatalog.nodesForRegion("unknown")).isEmpty()
        assertThat(CdnOverrideCatalog.displayNameForHost("")).isEqualTo(CdnOverrideCatalog.defaultRegion)
        assertThat(CdnOverrideCatalog.displayNameForHost(" HTTPS://CDN.COM/a ")).isEqualTo("cdn.com")
    }
}
