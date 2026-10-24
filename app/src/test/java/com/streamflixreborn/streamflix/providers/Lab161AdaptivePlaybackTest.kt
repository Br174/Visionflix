package com.streamflixreborn.streamflix.providers

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class Lab161AdaptivePlaybackTest {
    @Test fun recognizesDirectHtml5MediaWithoutExtraSelection() {
        val doc = Jsoup.parse("<video><source src='/free-film.mp4' type='video/mp4'></video>", "https://example.org/film/1")
        val candidates = AdaptiveMediaLinks.fromDocument(doc)
        assertEquals(1, candidates.size)
        assertEquals(AdaptiveMediaLinks.Kind.DIRECT, candidates.single().kind)
        assertEquals("https://example.org/free-film.mp4", candidates.single().url)
    }

    @Test fun embeddedPlayerIsNotMistakenForDirectVideo() {
        val doc = Jsoup.parse("<iframe src='https://video.example.org/embed/123'></iframe>", "https://example.org/film/1")
        val candidates = AdaptiveMediaLinks.fromDocument(doc)
        assertEquals(1, candidates.size)
        assertEquals(AdaptiveMediaLinks.Kind.EMBED, candidates.single().kind)
    }

    @Test fun rejectsNonWebPrivateAndCredentialedMediaLinks() {
        val html = "<video src='http://127.0.0.1/video.mp4'></video>" +
            "<iframe src='https://name:pass@example.org/embed'></iframe>" +
            "<iframe src='file:///etc/passwd'></iframe>"
        val candidates = AdaptiveMediaLinks.fromDocument(Jsoup.parse(html, "https://example.org/"))
        assertTrue(candidates.isEmpty())
    }

    @Test fun keepsDirectMediaAheadOfEmbedsAndDeduplicates() {
        val html = "<iframe src='https://video.example.org/e/1'></iframe>" +
            "<video src='https://media.example.org/demo.m3u8'></video>" +
            "<a href='https://media.example.org/demo.m3u8'>Watch</a>"
        val candidates = AdaptiveMediaLinks.fromDocument(Jsoup.parse(html, "https://example.org/"))
        assertEquals(2, candidates.size)
        assertEquals(AdaptiveMediaLinks.Kind.DIRECT, candidates[0].kind)
        assertEquals(AdaptiveMediaLinks.Kind.EMBED, candidates[1].kind)
    }
}
