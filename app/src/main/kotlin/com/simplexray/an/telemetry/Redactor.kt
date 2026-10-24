// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

/**
 * Takes what identifies servers, sites and accounts out of free text (log lines, failure reasons,
 * config names), keeping enough to tell problems apart:
 * - UUIDs become `<uuid>`;
 * - IPv4 addresses keep their first number (`203.x.x.x`), loopback is kept;
 * - IPv6 addresses become `<ipv6>`;
 * - domains keep only their top-level domain (`<domain>.com`). File names Xray logs, such as
 *   `handler.go` or `geosite.dat`, are left alone.
 */
object Redactor {
    private val UUID =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val IPV6_CANDIDATE =
        Regex("""(?<![\w.:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![\w:])""")
    private val IPV4 = Regex("""(?<![\w.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\w.])""")
    private val DOMAIN = Regex("""(?<![\w.-])((?:[A-Za-z0-9-]+\.)+)([A-Za-z]{2,})(?![\w-])""")
    private val FILE_EXTENSIONS =
        setOf(
            "go",
            "kt",
            "java",
            "json",
            "jsonl",
            "dat",
            "txt",
            "so",
            "log",
            "conf",
            "yaml",
            "zip",
            "csv",
        )

    fun redact(text: String): String {
        var out = UUID.replace(text, "<uuid>")
        out =
            IPV6_CANDIDATE.replace(out) { m ->
                val groups = m.value.split(':')
                val isIpv6 = m.value.contains("::") || groups.size >= 5
                if (isIpv6 && m.value != "::1") "<ipv6>" else m.value
            }
        out =
            IPV4.replace(out) { m ->
                val first = m.groupValues[1]
                if (first == "127" || m.value == "0.0.0.0") m.value else "$first.x.x.x"
            }
        out =
            DOMAIN.replace(out) { m ->
                val tld = m.groupValues[2]
                if (tld.lowercase() in FILE_EXTENSIONS) m.value else "<domain>.$tld"
            }
        return out
    }
}
