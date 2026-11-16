package dev.mark.lineover.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun digestOfKnownVector() {
        assertEquals(ABC_SHA256, Sha256.hex("abc".byteInputStream()))
    }

    @Test
    fun matchesAcceptsCorrectFileAndRejectsOthers() {
        val file = temp.newFile("model.bin").apply { writeText("abc") }

        assertTrue(Sha256.matches(file, ABC_SHA256))
        assertTrue(Sha256.matches(file, ABC_SHA256.uppercase()))
        assertFalse(Sha256.matches(file, ABC_SHA256.replace('b', 'c')))
        assertFalse(Sha256.matches(temp.root.resolve("missing.bin"), ABC_SHA256))
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelSpecRejectsMalformedDigest() {
        ModelSpec("id", "Name", "https://example.invalid/m.tflite", 1, "Apache-2.0", "abc")
    }

    private companion object {
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
