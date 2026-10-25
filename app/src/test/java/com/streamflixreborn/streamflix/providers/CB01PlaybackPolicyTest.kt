package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.extractors.MaxstreamExtractor
import org.junit.Assert.*
import org.junit.Test

class CB01PlaybackPolicyTest {
    @Test fun normalizedLinksUseValidHttpOrigins() {
        assertEquals("https://cb01official.uno/watch/123",
            CB01PlaybackPolicy.normalizeLink("/watch/123", "https://cb01official.uno/movie/a"))
        assertEquals("https://mixdrop.co/e/test",
            CB01PlaybackPolicy.normalizeLink("//mixdrop.co/e/test", "https://cb01official.uno/movie/a"))
        assertNull(CB01PlaybackPolicy.normalizeLink("javascript:alert(1)", "https://cb01official.uno/movie/a"))
        assertNull(CB01PlaybackPolicy.normalizeLink("", "https://cb01official.uno/movie/a"))
    }

    @Test fun intermediaryRoutingUsesRealHostname() {
        assertTrue(CB01PlaybackPolicy.isStayOnline("https://stayonline.pro/l/abcd"))
        assertTrue(CB01PlaybackPolicy.isUprot("https://uprot.net/msfi/abcd"))
        assertFalse(CB01PlaybackPolicy.isStayOnline("https://example.com/stayonline.pro/l/abcd"))
        assertFalse(CB01PlaybackPolicy.isUprot("https://uprot.net.evil.example/msfi/abcd"))
    }

    @Test fun maxstreamSourceParsingSupportsQuotedAndUnquotedKeys() {
        assertEquals("https://cdn.example/video.m3u8",
            MaxstreamExtractor.sourceFromHtml("sources: [{src: 'https://cdn.example/video.m3u8'}]"))
        assertEquals("https://cdn.example/video.mp4",
            MaxstreamExtractor.sourceFromHtml("sources: [{\"file\":\"https://cdn.example/video.mp4\"}]"))
        assertNull(MaxstreamExtractor.sourceFromHtml("<html><h1>Not found</h1></html>"))
    }
}
