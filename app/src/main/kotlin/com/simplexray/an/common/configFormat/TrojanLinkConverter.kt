// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject

/**
 * Imports Trojan share links (trojan://password@host:port?params#name). The parameters are those of
 * VLESS links (XTLS/Xray-core discussion #716); without one, security is TLS, as Trojan needs.
 */
class TrojanLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.trimStart().startsWith("trojan://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> =
        convert(content, Preferences(context).socksPort)

    internal fun convert(content: String, socksPort: Int): Result<DetectedConfig> = runCatching {
        val link = ShareLink.parse(content.trim(), "trojan")
        val params = ShareLink.parseQuery(link.query)
        val transport = ShareLink.transport(params, defaultSecurity = "tls")

        val server =
            JSONObject().put("address", link.host).put("port", link.port).put("password", link.user)
        val outbound =
            JSONObject()
                .put("protocol", "trojan")
                .put("settings", ShareLink.servers(server))
                .put("streamSettings", ShareLink.streamSettings(transport, params, link.host))
        DetectedConfig(
            ShareLink.name(link.fragment, "trojan"),
            ShareLink.config(outbound, socksPort).toString(2),
        )
    }
}
