// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import java.net.MalformedURLException
import org.json.JSONObject

/**
 * Imports Shadowsocks share links in both forms in use:
 * - SIP002, ss://userinfo@host:port/?plugin#name, where userinfo is base64(method:password) or, for
 *   the 2022 ciphers, percent-encoded method:password;
 * - the legacy ss://base64(method:password@host:port)#name.
 *
 * Links with a SIP003 plugin are refused, because Xray-core can't run plugins.
 */
class ShadowsocksLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.trimStart().startsWith("ss://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> =
        convert(content, Preferences(context).socksPort)

    internal fun convert(content: String, socksPort: Int): Result<DetectedConfig> = runCatching {
        var rest = content.trim().removePrefix("ss://")
        val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }?.let(ShareLink::decode)
        rest = rest.substringBefore('#')
        val params = ShareLink.parseQuery(rest.substringAfter('?', "").takeIf { '?' in rest })
        rest = rest.substringBefore('?').trimEnd('/')

        params["plugin"]
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                throw MalformedURLException(
                    "Shadowsocks plugins are not supported: ${it.substringBefore(';')}"
                )
            }

        // Legacy links have no '@' until the whole body is decoded.
        val body = if ('@' in rest) rest else ShareLink.decodeBase64(rest)
        val at = body.lastIndexOf('@')
        if (at <= 0) throw MalformedURLException("Missing method and password")
        val rawUser = body.take(at)
        val (host, port) = ShareLink.parseHostPort(body.drop(at + 1).substringBefore('/'))
        val user =
            ShareLink.decode(rawUser).let { if (':' in it) it else ShareLink.decodeBase64(it) }
        val method = user.substringBefore(':').lowercase()
        val password = user.substringAfter(':', "")
        if (method.isEmpty() || password.isEmpty()) {
            throw MalformedURLException("Missing method or password")
        }

        val server =
            JSONObject()
                .put("address", host)
                .put("port", port)
                .put("method", method)
                .put("password", password)
        val outbound =
            JSONObject().put("protocol", "shadowsocks").put("settings", ShareLink.servers(server))
        DetectedConfig(
            ShareLink.name(fragment, "ss"),
            ShareLink.config(outbound, socksPort).toString(2),
        )
    }
}
