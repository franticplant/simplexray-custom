// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import android.content.Context
import android.util.Log
import com.simplexray.an.common.FilenameValidator
import com.simplexray.an.data.source.FileManager.Companion.TAG
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.Inflater

class SimpleXrayFormatConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith(PREFIX)
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> =
        decode(content).mapCatching { (name, config) ->
            FilenameValidator.validateFilename(context, name)?.let { error ->
                Log.e(TAG, "Invalid filename in simplexray URI: $error")
                throw RuntimeException("Invalid filename in simplexray URI: $error")
            }
            name to config
        }

    /** Decodes simplexray://config/<url-encoded name>/<base64url of deflated config>. */
    internal fun decode(content: String): Result<DetectedConfig> = runCatching {
        val parts = content.substring(PREFIX.length).split("/")
        require(parts.size == 2) { "Invalid simplexray URI format" }

        val name = URLDecoder.decode(parts[0], "UTF-8")
        val compressed = Base64.getUrlDecoder().decode(parts[1])

        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                // Truncated or corrupt input: inflate makes no progress and never finishes.
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw IllegalArgumentException("Truncated or corrupt config data")
                }
                output.write(buffer, 0, count)
                require(output.size() <= MAX_CONFIG_BYTES) { "Config is too large" }
            }
            name to output.toByteArray().toString(Charsets.UTF_8)
        } finally {
            inflater.end()
        }
    }

    private companion object {
        const val PREFIX = "simplexray://config/"
        // Far above any real config; stops a small link from inflating without bound.
        const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
    }
}
