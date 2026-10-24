package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A visual-only provider distinction; the playback engine remains untouched. */
class Lab162AdaptiveLogoTest {
    @Test fun adaptiveCinemaUsesPermanentLocalLogo() {
        assertEquals(
            "android.resource://${BuildConfig.APPLICATION_ID}/drawable/ic_adaptive_cinema_logo",
            AdaptiveCinemaProvider.logo,
        )
        assertNotEquals("TMDb", AdaptiveCinemaProvider.name)
        assertTrue("Embedded drawable must be compiled into the APK", R.drawable.ic_adaptive_cinema_logo != 0)
    }
}
