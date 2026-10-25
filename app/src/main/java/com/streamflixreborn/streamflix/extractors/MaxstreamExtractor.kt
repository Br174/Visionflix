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

        val source = MaxstreamSourcePolicy.sourceFromHtml(html)
            ?: throw Exception("Maxstream source not found in page")
        return Video(source = source, extraBuffering = true)
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
