package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

class MaxstreamExtractor : Extractor() {
    override val name = "Maxstream"
    override val mainUrl = "https://maxstream.video"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val document = service.get(link)
        val html = document.toString()

        val source = sourceFromHtml(html)
            ?: throw Exception("Maxstream source not found in page")
        return Video(source = source, extraBuffering = true)
    }

    companion object {
        /** Accept already exposed video URLs from common Maxstream player source shapes. */
        internal fun sourceFromHtml(html: String): String? {
            val patterns = listOf(
                Regex("""(?is)\bsources\s*:\s*\[\s*\{.{0,320}?["']?(?:src|file)["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""(?is)\bfile\s*:\s*["'](https?:\\?/\\?/[^"']+)["']""")
            )
            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    val raw = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                    val result = when {
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("https://") || raw.startsWith("http://") -> raw
                        else -> continue
                    }
                    return result
                }
            }
            return null
        }
    }

    private interface Service {
        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        suspend fun get(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): Service {
                val client = okhttp3.OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
