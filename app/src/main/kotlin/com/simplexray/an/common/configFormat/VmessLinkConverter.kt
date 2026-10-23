// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import java.net.MalformedURLException
import org.json.JSONObject

/**
 * Imports VMess share links in both forms in use:
 * - vmess://base64(JSON), the v2rayN format with fields add, port, id, scy, net, type, host, path,
 *   tls, sni, alpn and fp;
 * - vmess://uuid@host:port?params#name, the XTLS/Xray-core discussion #716 format, with the same
 *   parameters as VLESS links and encryption naming the VMess cipher.
 *
 * alterId is not carried over: Xray-core's VMess client always uses AEAD.
 */
class VmessLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.trimStart().startsWith("vmess://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> =
        convert(content, Preferences(context).socksPort)

    internal fun convert(content: String, socksPort: Int): Result<DetectedConfig> = runCatching {
        val link = content.trim()
        val body = link.removePrefix("vmess://").substringBefore('#')
        if ('@' in body) fromUri(link, socksPort) else fromJson(body, socksPort)
    }

    private fun fromUri(content: String, socksPort: Int): DetectedConfig {
        val link = ShareLink.parse(content, "vmess")
        val params = ShareLink.parseQuery(link.query)
        return build(
            link.user,
            link.host,
            link.port,
            params["encryption"],
            params,
            ShareLink.name(link.fragment, "vmess"),
            socksPort,
        )
    }

    private fun fromJson(body: String, socksPort: Int): DetectedConfig {
        val json = JSONObject(ShareLink.decodeBase64(body))
        fun field(key: String) = json.optString(key).trim().takeIf { it.isNotEmpty() }
        val address = field("add") ?: throw MalformedURLException("Missing host")
        val port = ShareLink.parsePort(field("port") ?: "443")
        val id = field("id") ?: throw MalformedURLException("Missing user ID")
        val network = field("net") ?: "tcp"
        val headerType = field("type")?.takeIf { it != "none" }

        // The JSON fields, spelled as the #716 parameters ShareLink understands.
        val params = buildMap {
            put("type", network)
            field("tls")?.takeIf { it != "none" }?.let { put("security", it) }
            field("host")?.let { put("host", it) }
            field("path")?.let { put("path", it) }
            field("sni")?.let { put("sni", it) }
            field("alpn")?.let { put("alpn", it) }
            field("fp")?.let { put("fp", it) }
            field("pbk")?.let { put("pbk", it) }
            field("sid")?.let { put("sid", it) }
            field("spx")?.let { put("spx", it) }
            // v2rayN keeps the TCP header type, and the gRPC and XHTTP mode, in "type".
            when (network) {
                "grpc",
                "xhttp",
                "splithttp" -> headerType?.let { put("mode", it) }
                else -> headerType?.let { put("headerType", it) }
            }
        }
        return build(
            id,
            address,
            port,
            field("scy"),
            params,
            ShareLink.name(field("ps"), "vmess"),
            socksPort,
        )
    }

    private fun build(
        id: String,
        address: String,
        port: Int,
        cipher: String?,
        params: Map<String, String>,
        name: String,
        socksPort: Int,
    ): DetectedConfig {
        val user = JSONObject().put("id", id).put("security", cipher?.lowercase() ?: "auto")
        val outbound =
            JSONObject()
                .put("protocol", "vmess")
                .put("settings", ShareLink.vnext(address, port, user))
                .put(
                    "streamSettings",
                    ShareLink.streamSettings(ShareLink.transport(params), params, address),
                )
        return DetectedConfig(name, ShareLink.config(outbound, socksPort).toString(2))
    }
}
