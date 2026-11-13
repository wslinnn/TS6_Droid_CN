package dev.tsdroid.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Sha256ChecksumTest {

    private val digest = "337c4df97afc360234529f3a353631e09d881f15a1a1bd1baf00081e10f32a00"

    @Test
    fun `sha256sum output with file name parses`() {
        assertEquals(digest, Sha256Checksum.parse("$digest  TS6-Mobile-v2.10.0-Han-release.apk\n"))
    }

    @Test
    fun `binary marker and upper case are accepted`() {
        assertEquals(digest, Sha256Checksum.parse("${digest.uppercase()} *app.apk"))
    }

    @Test
    fun `bare digest parses`() {
        assertEquals(digest, Sha256Checksum.parse("$digest"))
    }

    @Test
    fun `first valid line wins and blank lines are skipped`() {
        val text = "\n\n  \n$digest  a.apk\n0000000000000000000000000000000000000000000000000000000000000000  b.apk\n"
        assertEquals(digest, Sha256Checksum.parse(text))
    }

    @Test
    fun `malformed content is rejected`() {
        assertNull(Sha256Checksum.parse(""))
        assertNull(Sha256Checksum.parse("not a checksum"))
        // 63 / 65 hex digits
        assertNull(Sha256Checksum.parse(digest.dropLast(1)))
        assertNull(Sha256Checksum.parse(digest + "0"))
        // non-hex character inside the digest
        assertNull(Sha256Checksum.parse("z" + digest.drop(1)))
        // openssl-style output is not something we publish
        assertNull(Sha256Checksum.parse("SHA256(app.apk)= $digest"))
    }
}
