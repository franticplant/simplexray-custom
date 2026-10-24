// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.service

import com.simplexray.an.service.VpnRoutes.Route
import java.math.BigInteger
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutesTest {
    private fun ip(s: String) = BigInteger(1, InetAddress.getByName(s).address)

    private fun covers(routes: List<Route>, address: String): Boolean {
        val bits = InetAddress.getByName(address).address.size * 8
        return routes.any {
            val shift = bits - it.prefixLength
            ip(it.address).shiftRight(shift) == ip(address).shiftRight(shift)
        }
    }

    private fun size(routes: List<Route>, bits: Int): BigInteger =
        routes.fold(BigInteger.ZERO) { sum, r ->
            sum + BigInteger.ONE.shiftLeft(bits - r.prefixLength)
        }

    @Test
    fun withoutBypass_everythingIsRouted() {
        assertEquals(listOf(Route("0.0.0.0", 0)), VpnRoutes.ipv4(bypassLan = false))
        assertEquals(listOf(Route("::", 0)), VpnRoutes.ipv6(bypassLan = false))
    }

    @Test
    fun ipv4Bypass_routesThePublicInternetButNotTheLan() {
        val routes = VpnRoutes.ipv4(bypassLan = true)

        listOf("8.8.8.8", "1.1.1.1", "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0")
            .forEach { assertTrue(it, covers(routes, it)) }
        listOf("10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.1", "169.254.1.1").forEach {
            assertFalse(it, covers(routes, it))
        }
        listOf("224.0.0.251", "239.255.255.250", "255.255.255.255").forEach {
            assertFalse(it, covers(routes, it))
        }
    }

    @Test
    fun ipv4Bypass_coversExactlyTheAddressesOutsideTheLan() {
        val routes = VpnRoutes.ipv4(bypassLan = true)
        val lan = size(VpnRoutes.LAN_IPV4, 32)

        assertEquals(BigInteger.ONE.shiftLeft(32) - lan, size(routes, 32))
    }

    @Test
    fun ipv6Bypass_routesGlobalUnicastButNotTheLan() {
        val routes = VpnRoutes.ipv6(bypassLan = true)

        assertTrue(covers(routes, "2001:4860:4860::8888"))
        assertTrue(covers(routes, "fbff::1"))
        listOf("fd12::1", "fc00::1", "fe80::1", "ff02::fb").forEach {
            assertFalse(it, covers(routes, it))
        }
        assertEquals(
            BigInteger.ONE.shiftLeft(128) - size(VpnRoutes.LAN_IPV6, 128),
            size(routes, 128),
        )
    }

    @Test
    fun complement_clearsHostBitsOfTheExcludedRange() {
        assertEquals(
            listOf(Route("128.0.0.0", 1)),
            VpnRoutes.complement(Route("0.0.0.0", 0), listOf(Route("1.2.3.4", 1))),
        )
    }
}
