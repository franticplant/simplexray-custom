// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject

/**
 * Imports VLESS share links (vless://uuid@host:port?params#name), following the de facto standard
 * of XTLS/Xray-core discussion #716. Where a link leaves something out, it keeps this app's earlier
 * behaviour for REALITY links: a link with a public key (pbk) is REALITY even without
 * security=reality, and REALITY over TCP gets the xtls-rprx-vision flow.
 */
class VlessLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.trimStart().startsWith("vless://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> =
        convert(content, Preferences(context).socksPort)

    internal fun convert(content: String, socksPort: Int): Result<DetectedConfig> = runCatching {
        val link = ShareLink.parse(content.trim(), "vless")
        val params = ShareLink.parseQuery(link.query)
        val transport = ShareLink.transport(params)
        val flow =
            params["flow"]
                ?: if (transport.security == "reality" && transport.network == "tcp")
                    "xtls-rprx-vision"
                else ""

        val user =
            JSONObject()
                .put("id", link.user)
                .put("encryption", params["encryption"]?.takeIf { it.isNotEmpty() } ?: "none")
        if (flow.isNotEmpty()) user.put("flow", flow)

        val outbound =
            JSONObject()
                .put("protocol", "vless")
                .put("settings", ShareLink.vnext(link.host, link.port, user))
                .put("streamSettings", ShareLink.streamSettings(transport, params, link.host))
        DetectedConfig(
            ShareLink.name(link.fragment, "vless"),
            ShareLink.config(outbound, socksPort).toString(2),
        )
    }
}
