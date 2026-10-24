// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.service

import java.math.BigInteger
import java.net.InetAddress

/**
 * The routes the VPN claims. VpnService.Builder.excludeRoute needs API 33, so bypassing the LAN is
 * done the portable way: by routing everything except the LAN ranges.
 */
object VpnRoutes {
    data class Route(val address: String, val prefixLength: Int)

    /** Private, link-local and multicast/broadcast IPv4 ranges. */
    val LAN_IPV4 =
        listOf(
            Route("10.0.0.0", 8),
            Route("169.254.0.0", 16),
            Route("172.16.0.0", 12),
            Route("192.168.0.0", 16),
            Route("224.0.0.0", 3),
        )

    /** Unique-local, link-local and multicast IPv6 ranges. */
    val LAN_IPV6 = listOf(Route("fc00::", 7), Route("fe80::", 10), Route("ff00::", 8))

    fun ipv4(bypassLan: Boolean): List<Route> =
        if (bypassLan) complement(Route("0.0.0.0", 0), LAN_IPV4) else listOf(Route("0.0.0.0", 0))

    fun ipv6(bypassLan: Boolean): List<Route> =
        if (bypassLan) complement(Route("::", 0), LAN_IPV6) else listOf(Route("::", 0))

    /** The smallest set of prefixes covering [whole] but none of [excluded]. */
    fun complement(whole: Route, excluded: List<Route>): List<Route> {
        val bits = InetAddress.getByName(whole.address).address.size * 8
        val holes = excluded.map { Prefix.of(it, bits) }
        val out = mutableListOf<Route>()
        fun visit(p: Prefix) {
            when {
                holes.any { it.contains(p) } -> Unit
                holes.none { p.contains(it) } -> out += p.toRoute()
                else -> p.halves().forEach(::visit)
            }
        }
        visit(Prefix.of(whole, bits))
        return out
    }

    private data class Prefix(val base: BigInteger, val length: Int, val bits: Int) {
        fun contains(other: Prefix): Boolean =
            other.length >= length &&
                other.base.shiftRight(bits - length) == base.shiftRight(bits - length)

        fun halves(): List<Prefix> =
            listOf(
                copy(length = length + 1),
                copy(base = base.setBit(bits - length - 1), length = length + 1),
            )

        fun toRoute(): Route {
            val bytes = base.toByteArray().takeLast(bits / 8).toByteArray()
            val padded = ByteArray(bits / 8 - bytes.size) + bytes
            return Route(InetAddress.getByAddress(padded).hostAddress!!, length)
        }

        companion object {
            fun of(route: Route, bits: Int): Prefix {
                val base = BigInteger(1, InetAddress.getByName(route.address).address)
                require(route.prefixLength in 0..bits) { "Bad prefix length: $route" }
                // Clear the host bits, so a route like 10.1.2.3/8 means 10.0.0.0/8.
                val mask =
                    BigInteger.ONE.shiftLeft(bits)
                        .subtract(BigInteger.ONE.shiftLeft(bits - route.prefixLength))
                return Prefix(base.and(mask), route.prefixLength, bits)
            }
        }
    }
}
