package io.nekohasekai.sagernet.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    private fun v(value: String) = ReleaseVersion.parse(value)!!
    @Test fun detectsArcaenBoxRevisions() {
        assertTrue(v("v1.4.2-arcaenbox.6") > v("1.4.2-arcaenbox.5"))
        assertTrue(v("v1.4.2-arcaenbox.5") > v("1.4.2"))
        assertTrue(v("1.4.3") > v("1.4.2-arcaenbox.100"))
        assertEquals(v("v1.4.2-arcaenbox.6"),v("1.4.2-arcaenbox.6"))
    }
    @Test fun comparesPreviewVersionsNumerically() {
        assertTrue(v("1.15.0-alpha.10") > v("1.15.0-alpha.2"))
        assertTrue(v("1.15.0-beta.1") > v("1.15.0-alpha.100"))
        assertTrue(v("1.15.0") > v("1.15.0-rc.9"))
        assertTrue(v("1.15.0-alpha.2") > v("1.14.0"))
        assertEquals(v("1.14.0+build.1"),v("1.14.0+build.2"))
    }
    @Test fun rejectsDisplayTitlesAndMissingTags() {
        for (s in listOf("ArcaenBox v1.4.2","preview","core-stable-1.14.0-1","v1.4.2garbage","99999999999999999999.1.0")) assertNull(ReleaseVersion.parse(s))
    }
    @Test fun channelsIgnoreCoreReleasesAndDisplayOrder() {
        fun r(tag: String, preview: Boolean, apk: Boolean=true) = GithubRelease(tag,preview,"https://github.com/Gavin-LHX/ArcaenBox/releases/tag/$tag",if(apk) listOf(ReleaseAsset("app.apk","")) else emptyList())
        val releases=listOf(r("v1.4.2-arcaenbox.2",false),r("core-preview-1.15.0-1",true,false),r("v1.5.0-beta.1",true),r("v1.4.2-arcaenbox.6",false),r("v1.4.3",false,false))
        assertEquals("v1.4.2-arcaenbox.6",ReleaseService.applicationRelease(releases,false)?.tag)
        assertEquals("v1.5.0-beta.1",ReleaseService.applicationRelease(releases,true)?.tag)
        assertNull(ReleaseService.applicationRelease(releases.filter { !it.preview },true))
    }
}
