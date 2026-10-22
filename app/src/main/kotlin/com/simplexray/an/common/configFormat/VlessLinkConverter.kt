// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import java.net.MalformedURLException
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports VLESS share links (vless://uuid@host:port?params#name), following the de facto standard
 * of XTLS/Xray-core discussion #716. Where a link leaves something out, it keeps this app's earlier
 * behaviour for REALITY links: a link with a public key (pbk) is REALITY even without
 * security=reality, and REALITY over TCP gets the xtls-rprx-vision flow.
 */
class VlessLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("vless://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> =
        convert(content, Preferences(context).socksPort)

    internal fun convert(content: String, socksPort: Int): Result<DetectedConfig> {
        return try {
            val link = parseLink(content.trim())
            val params = parseQuery(link.query)
            val name =
                sanitizeName(link.fragment) ?: ("imported_vless_" + System.currentTimeMillis())
            val config = buildConfig(link.id, link.host, link.port, params, socksPort)
            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildConfig(
        id: String,
        address: String,
        port: Int,
        params: Map<String, String>,
        socksPort: Int,
    ): JSONObject {
        val network =
            when (val type = params["type"]?.lowercase()) {
                null,
                "",
                "raw" -> "tcp"
                "h2" -> "http"
                "splithttp" -> "xhttp"
                else -> type
            }
        val security =
            params["security"]?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: if (params.containsKey("pbk")) "reality" else "none"
        val flow =
            params["flow"]
                ?: if (security == "reality" && network == "tcp") "xtls-rprx-vision" else ""
        val sni = params["sni"] ?: params["peer"]
        val fingerprint = params["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome"

        val user =
            JSONObject()
                .put("id", id)
                .put("encryption", params["encryption"]?.takeIf { it.isNotEmpty() } ?: "none")
        if (flow.isNotEmpty()) user.put("flow", flow)

        val stream = JSONObject().put("network", network).put("security", security)
        when (security) {
            "tls" ->
                stream.put(
                    "tlsSettings",
                    JSONObject()
                        .put("serverName", sni ?: address)
                        .put("fingerprint", fingerprint)
                        .apply {
                            params["alpn"]?.let { alpn ->
                                put("alpn", JSONArray(alpn.split(",").filter { it.isNotEmpty() }))
                            }
                        },
                )
            "reality" ->
                stream.put(
                    "realitySettings",
                    JSONObject()
                        .put("show", false)
                        .put("fingerprint", fingerprint)
                        .put("serverName", sni ?: address)
                        .put("publicKey", params["pbk"] ?: "")
                        .put("shortId", params["sid"] ?: "")
                        .put("spiderX", params["spx"] ?: "/"),
                )
        }
        transportSettings(network, params)?.let { (key, settings) -> stream.put(key, settings) }

        val outbound =
            JSONObject()
                .put("protocol", "vless")
                .put(
                    "settings",
                    JSONObject()
                        .put(
                            "vnext",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("address", address)
                                        .put("port", port)
                                        .put("users", JSONArray().put(user))
                                ),
                        ),
                )
                .put("streamSettings", stream)

        val inbound =
            JSONObject()
                .put("port", socksPort)
                .put("listen", "127.0.0.1")
                .put("protocol", "socks")
                .put("settings", JSONObject().put("udp", true))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
    }

    /** The per-transport settings object, keyed by its streamSettings field, if it needs one. */
    private fun transportSettings(
        network: String,
        params: Map<String, String>,
    ): Pair<String, JSONObject>? {
        val path = params["path"]
        val host = params["host"]
        fun pathAndHost() =
            JSONObject().apply {
                path?.let { put("path", it) }
                host?.let { put("host", it) }
            }
        return when (network) {
            "ws" -> "wsSettings" to pathAndHost()
            "httpupgrade" -> "httpupgradeSettings" to pathAndHost()
            "xhttp" ->
                "xhttpSettings" to pathAndHost().apply { params["mode"]?.let { put("mode", it) } }
            "grpc" ->
                "grpcSettings" to
                    JSONObject().apply {
                        (params["serviceName"] ?: path)?.let { put("serviceName", it) }
                        if (params["mode"] == "multi") put("multiMode", true)
                        params["authority"]?.let { put("authority", it) }
                    }
            "http" ->
                "httpSettings" to
                    JSONObject().apply {
                        path?.let { put("path", it) }
                        host?.let {
                            put("host", JSONArray(it.split(",").filter(String::isNotEmpty)))
                        }
                    }
            "tcp" ->
                if (params["headerType"] == "http") {
                    "tcpSettings" to
                        JSONObject()
                            .put(
                                "header",
                                JSONObject()
                                    .put("type", "http")
                                    .put(
                                        "request",
                                        JSONObject().apply {
                                            path?.let { put("path", JSONArray().put(it)) }
                                            host?.let {
                                                put(
                                                    "headers",
                                                    JSONObject().put("Host", JSONArray().put(it)),
                                                )
                                            }
                                        },
                                    ),
                            )
                } else null
            else -> null
        }
    }

    internal data class Link(
        val id: String,
        val host: String,
        val port: Int,
        val query: String?,
        val fragment: String?,
    )

    internal companion object {
        /**
         * Splits vless://id@host:port/?query#fragment by hand: real links often carry characters
         * java.net.URI rejects (spaces and emoji in the name, '|' in paths).
         */
        fun parseLink(link: String): Link {
            if (!link.startsWith("vless://")) throw MalformedURLException("Not a vless:// link")
            var rest = link.removePrefix("vless://")
            val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }?.let(::decode)
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "").takeIf { '?' in rest }
            rest = rest.substringBefore('?').trimEnd('/')
            val at = rest.lastIndexOf('@')
            if (at <= 0) throw MalformedURLException("Missing user ID")
            val id = decode(rest.take(at))
            val hostPort = rest.drop(at + 1).substringBefore('/')
            val (host, portText) =
                if (hostPort.startsWith("[")) {
                    val end = hostPort.indexOf(']')
                    if (end < 0) throw MalformedURLException("Unterminated IPv6 address")
                    hostPort.substring(1, end) to hostPort.drop(end + 1).removePrefix(":")
                } else if (hostPort.count { it == ':' } == 1) {
                    hostPort.substringBefore(':') to hostPort.substringAfter(':')
                } else {
                    hostPort to ""
                }
            if (host.isEmpty()) throw MalformedURLException("Missing host")
            val port =
                if (portText.isEmpty()) 443
                else
                    portText.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw MalformedURLException("Invalid port: $portText")
            return Link(id, host, port, query, fragment)
        }

        // '+' is literal in URIs (only form encoding means space), and a stray '%' must not throw.
        private fun decode(s: String): String = runCatching {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        }
            .getOrDefault(s)

        fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            return rawQuery
                .split("&")
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val i = pair.indexOf('=')
                    if (i < 0) decode(pair) to ""
                    else decode(pair.take(i)) to decode(pair.drop(i + 1))
                }
        }

        /** A file-name-safe version of the link's #name, or null if nothing usable is left. */
        fun sanitizeName(fragment: String?): String? =
            fragment
                ?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                ?.trim()
                ?.trim('.')
                ?.take(100)
                ?.takeIf { it.isNotEmpty() }
    }
}
