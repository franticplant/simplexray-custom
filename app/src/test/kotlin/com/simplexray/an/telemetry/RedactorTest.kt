// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactorTest {
    private fun r(s: String) = Redactor.redact(s)

    @Test
    fun uuids() {
        assertEquals("id <uuid> ok", r("id b831381d-6324-4d53-ad4f-8cda48b30811 ok"))
    }

    @Test
    fun ipv4_keepsTheFirstNumber_andLoopback() {
        assertEquals("dial 203.x.x.x:443", r("dial 203.0.113.7:443"))
        assertEquals("listen 127.0.0.1:10808 and 0.0.0.0", r("listen 127.0.0.1:10808 and 0.0.0.0"))
    }

    @Test
    fun ipv6() {
        assertEquals("to [<ipv6>]:443", r("to [2001:db8::1]:443"))
        assertEquals("<ipv6> here", r("2001:db8:85a3:0:0:8a2e:370:7334 here"))
        assertEquals("::1 stays", r("::1 stays"))
    }

    @Test
    fun domains_keepTheirTopLevelDomain() {
        assertEquals("tcp:<domain>.com:443", r("tcp:www.google.com:443"))
        assertEquals("server <domain>.net", r("server de1.example.net"))
    }

    @Test
    fun leavesTimesFileNamesAndVersionsAlone() {
        val line =
            "2026/10/24 16:39:08.699431 [Info] app/proxyman/outbound/handler.go:123: geosite.dat loaded, Xray 26.2.6"
        assertEquals(line, r(line))
    }
}
