// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.common.configFormat

import java.net.MalformedURLException
import java.net.URLDecoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the share-link importers have in common: splitting scheme://user@host:port?query#name, and
 * turning the query parameters of the XTLS/Xray-core discussion #716 standard into streamSettings.
 */
internal object ShareLink {
    data class Link(
        val user: String,
        val host: String,
        val port: Int,
        val query: String?,
        val fragment: String?,
    )

    /** The transport and security layer a link asks for, after aliases and defaults. */
    data class Transport(val network: String, val security: String)

    /**
     * Splits scheme://user@host:port/?query#fragment by hand: real links often carry characters
     * java.net.URI rejects (spaces and emoji in the name, '|' in paths). The user part is returned
     * decoded.
     */
    fun parse(link: String, scheme: String): Link {
        val prefix = "$scheme://"
        if (!link.startsWith(prefix)) throw MalformedURLException("Not a $prefix link")
        var rest = link.removePrefix(prefix)
        val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }?.let(::decode)
        rest = rest.substringBefore('#')
        val query = rest.substringAfter('?', "").takeIf { '?' in rest }
        rest = rest.substringBefore('?').trimEnd('/')
        val at = rest.lastIndexOf('@')
        if (at <= 0) throw MalformedURLException("Missing user")
        val user = decode(rest.take(at))
        val (host, port) = parseHostPort(rest.drop(at + 1).substringBefore('/'))
        return Link(user, host, port, query, fragment)
    }

    /** host:port, [v6]:port or a bare host (port 443). */
    fun parseHostPort(hostPort: String): Pair<String, Int> {
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
        return host to parsePort(portText.ifEmpty { "443" })
    }

    fun parsePort(text: String): Int =
        text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw MalformedURLException("Invalid port: $text")

    // '+' is literal in URIs (only form encoding means space), and a stray '%' must not throw.
    fun decode(s: String): String = runCatching {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
        .getOrDefault(s)

    /** Base64 as share links use it: standard or URL-safe alphabet, padded or not. */
    fun decodeBase64(s: String): String {
        val text = s.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        val padded = text.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw MalformedURLException("Invalid base64").apply { initCause(e) }
        }
    }

    fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery
            .split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val i = pair.indexOf('=')
                if (i < 0) decode(pair) to "" else decode(pair.take(i)) to decode(pair.drop(i + 1))
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

    /** The link's name, or a generated one if it has none. */
    fun name(fragment: String?, protocol: String): String =
        sanitizeName(fragment) ?: ("imported_${protocol}_" + System.currentTimeMillis())

    /**
     * The link's transport and security. [defaultSecurity] applies when the link names none; a
     * public key (pbk) still means REALITY.
     */
    fun transport(params: Map<String, String>, defaultSecurity: String = "none"): Transport {
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
                ?: if (params.containsKey("pbk")) "reality" else defaultSecurity
        return Transport(network, security)
    }

    fun streamSettings(
        transport: Transport,
        params: Map<String, String>,
        address: String,
    ): JSONObject {
        val sni = params["sni"]?.takeIf { it.isNotEmpty() } ?: params["peer"]
        val fingerprint = params["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome"
        val stream =
            JSONObject().put("network", transport.network).put("security", transport.security)
        when (transport.security) {
            "tls" ->
                stream.put(
                    "tlsSettings",
                    JSONObject()
                        .put("serverName", sni ?: address)
                        .put("fingerprint", fingerprint)
                        .apply {
                            params["alpn"]?.let { alpn ->
                                val list = alpn.split(",").filter { it.isNotEmpty() }
                                if (list.isNotEmpty()) put("alpn", JSONArray(list))
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
        transportSettings(transport.network, params)?.let { (key, settings) ->
            stream.put(key, settings)
        }
        return stream
    }

    /** The per-transport settings object, keyed by its streamSettings field, if it needs one. */
    private fun transportSettings(
        network: String,
        params: Map<String, String>,
    ): Pair<String, JSONObject>? {
        val path = params["path"]?.takeIf { it.isNotEmpty() }
        val host = params["host"]?.takeIf { it.isNotEmpty() }
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

    /** A complete config: a SOCKS inbound on [socksPort] in front of [outbound]. */
    fun config(outbound: JSONObject, socksPort: Int): JSONObject {
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

    /** settings.vnext for VLESS and VMess: one server with one user. */
    fun vnext(address: String, port: Int, user: JSONObject): JSONObject =
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
            )

    /** settings.servers for Trojan and Shadowsocks: one server. */
    fun servers(server: JSONObject): JSONObject =
        JSONObject().put("servers", JSONArray().put(server))
}
