// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleXrayFormatConverterTest {
    private val converter = SimpleXrayFormatConverter()

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArray(bytes.size + 1024)
        val n = deflater.deflate(out)
        deflater.end()
        return out.copyOf(n)
    }

    private fun link(name: String, payload: ByteArray) =
        "simplexray://config/" +
            URLEncoder.encode(name, "UTF-8") +
            "/" +
            Base64.getUrlEncoder().encodeToString(payload)

    @Test
    fun roundTrip() {
        val config = """{"outbounds":[{"protocol":"freedom"}]}"""

        val (name, decoded) =
            converter.decode(link("My config", deflate(config.toByteArray()))).getOrThrow()

        assertEquals("My config", name)
        assertEquals(config, decoded)
    }

    @Test(timeout = 5000)
    fun truncatedDataFailsInsteadOfHanging() {
        val compressed = deflate(ByteArray(10_000) { (it % 251).toByte() })

        val result = converter.decode(link("x", compressed.copyOf(compressed.size / 2)))

        assertTrue(result.isFailure)
    }

    @Test
    fun oversizedConfigIsRejected() {
        val bomb = deflate(ByteArray(8 * 1024 * 1024))

        assertTrue(converter.decode(link("x", bomb)).isFailure)
    }

    @Test
    fun badBase64AndBadShapeAreFailures() {
        assertTrue(converter.decode("simplexray://config/name/!!!notbase64").isFailure)
        assertTrue(converter.decode("simplexray://config/only-one-part").isFailure)
    }
}
