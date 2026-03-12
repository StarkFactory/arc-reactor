package com.arc.reactor.mcp

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SsrfProtectionTest {

    @ParameterizedTest(name = "should block private address: {0}")
    @ValueSource(strings = [
        "127.0.0.1",
        "localhost",
        "10.0.0.1",
        "10.255.255.255",
        "172.16.0.1",
        "172.31.255.255",
        "192.168.0.1",
        "192.168.1.100",
        "169.254.1.1",
        "0:0:0:0:0:0:0:1", // IPv6 loopback
        "::1"              // IPv6 loopback shorthand
    ])
    fun `should block private and reserved addresses`(host: String) {
        isPrivateOrReservedAddress(host) shouldBe true
    }

    @Test
    fun `should block null host`() {
        isPrivateOrReservedAddress(null) shouldBe true
    }

    @Test
    fun `should block blank host`() {
        isPrivateOrReservedAddress("") shouldBe true
        isPrivateOrReservedAddress("  ") shouldBe true
    }

    @Test
    fun `should block unresolvable host`() {
        isPrivateOrReservedAddress("this-host-definitely-does-not-exist.invalid") shouldBe true
    }

    @ParameterizedTest(name = "should block cloud metadata address: {0}")
    @ValueSource(strings = [
        "169.254.169.254" // AWS/GCP/Azure metadata endpoint
    ])
    fun `should block cloud metadata addresses`(host: String) {
        isPrivateOrReservedAddress(host) shouldBe true
    }

    @ParameterizedTest(name = "should block multicast address: {0}")
    @ValueSource(strings = [
        "224.0.0.1",
        "239.255.255.255"
    ])
    fun `should block multicast addresses`(host: String) {
        isPrivateOrReservedAddress(host) shouldBe true
    }

    @ParameterizedTest(name = "should allow public address: {0}")
    @ValueSource(strings = [
        "8.8.8.8",
        "1.1.1.1"
    ])
    fun `should allow public addresses`(host: String) {
        isPrivateOrReservedAddress(host) shouldBe false
    }
}
