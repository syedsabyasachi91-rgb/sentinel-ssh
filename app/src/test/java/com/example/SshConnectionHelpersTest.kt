package com.example

import com.example.data.local.AuthType
import com.example.ssh.HostKeyRejectedException
import com.example.ssh.LinuxTelemetryParser
import com.example.ssh.SshAuthenticationException
import com.example.ssh.SshConnectionException
import com.example.ssh.SshCredentialUserInfo
import com.example.ssh.SshCryptoProvider
import com.example.ssh.classifySshConnectionException
import com.jcraft.jsch.JSchException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.Security
import javax.crypto.Cipher

class SshConnectionHelpersTest {

    @Test
    fun normalizeHostInput_cleansSchemesUsersAndBrackets() {
        assertEquals("203.0.113.10", LinuxTelemetryParser.normalizeHostInput("  ssh://root@203.0.113.10/  "))
        assertEquals("vps.example.com", LinuxTelemetryParser.normalizeHostInput("https://vps.example.com/status"))
        assertEquals("2001:db8::1", LinuxTelemetryParser.normalizeHostInput("[2001:db8::1]"))
        assertEquals("198.51.100.42", LinuxTelemetryParser.normalizeHostInput("admin@198.51.100.42"))
    }

    @Test
    fun credentialUserInfo_handlesKeyboardInteractivePasswordOnceAndBannerPrompts() {
        val ui = SshCredentialUserInfo(
            authType = AuthType.PASSWORD,
            secret = "s3cr3t!",
            passphrase = ""
        )

        // RFC 4256 empty prompt packet (e.g., PAM banner) must return emptyArray, not null.
        val bannerResponse = ui.promptKeyboardInteractive(
            destination = "root@vps.example.com",
            name = "",
            instruction = "Authorized users only",
            prompt = emptyArray(),
            echo = BooleanArray(0)
        )
        assertNotNull(bannerResponse)
        assertEquals(0, bannerResponse?.size)

        // Visible prompt (echo = true) should not be answered with password.
        assertNull(
            ui.promptKeyboardInteractive(
                destination = "root@vps.example.com",
                name = "",
                instruction = "",
                prompt = arrayOf("Verification code: "),
                echo = booleanArrayOf(true)
            )
        )

        // Single masked password prompt receives the password once.
        val firstPasswordAttempt = ui.promptKeyboardInteractive(
            destination = "root@vps.example.com",
            name = "",
            instruction = "",
            prompt = arrayOf("Password: "),
            echo = booleanArrayOf(false)
        )
        assertArrayEquals(arrayOf("s3cr3t!"), firstPasswordAttempt)

        // Subsequent prompt in same session must return null to avoid lockout loops.
        assertNull(
            ui.promptKeyboardInteractive(
                destination = "root@vps.example.com",
                name = "",
                instruction = "",
                prompt = arrayOf("Password: "),
                echo = booleanArrayOf(false)
            )
        )
    }

    @Test
    fun credentialUserInfo_privateKeyMode_doesNotExposePasswordOrKeyboardInteractive() {
        val ui = SshCredentialUserInfo(
            authType = AuthType.PRIVATE_KEY,
            secret = "-----BEGIN OPENSSH PRIVATE KEY-----\n...",
            passphrase = "key-passphrase"
        )
        assertNull(ui.password)
        assertEquals("key-passphrase", ui.passphrase)
        assertNull(
            ui.promptKeyboardInteractive(
                destination = null,
                name = null,
                instruction = null,
                prompt = arrayOf("Password: "),
                echo = booleanArrayOf(false)
            )
        )
    }

    @Test
    fun classifySshConnectionException_surfacesActionableStageAndDetails() {
        val dns = classifySshConnectionException(
            error = JSchException("java.net.UnknownHostException: bad.host", UnknownHostException("bad.host")),
            host = "bad.host",
            port = 22,
            username = "root",
            authType = AuthType.PASSWORD,
            hostKeyRejectedByUser = false,
            hostKeyCheckFailed = false,
            handshakeLogs = emptyList()
        )
        assertTrue(dns is SshConnectionException)
        assertTrue(dns.message.orEmpty().contains("Could not resolve hostname"))

        val refused = classifySshConnectionException(
            error = JSchException("connect failed", ConnectException("Connection refused")),
            host = "203.0.113.10",
            port = 2222,
            username = "ubuntu",
            authType = AuthType.PASSWORD,
            hostKeyRejectedByUser = false,
            hostKeyCheckFailed = false,
            handshakeLogs = emptyList()
        )
        assertTrue(refused is SshConnectionException)
        assertTrue(refused.message.orEmpty().contains("Connection refused by 203.0.113.10:2222"))

        val timedOut = classifySshConnectionException(
            error = JSchException("timeout", SocketTimeoutException("connect timed out")),
            host = "203.0.113.10",
            port = 22,
            username = "ubuntu",
            authType = AuthType.PASSWORD,
            hostKeyRejectedByUser = false,
            hostKeyCheckFailed = false,
            handshakeLogs = emptyList()
        )
        assertTrue(timedOut is SshConnectionException)
        assertTrue(timedOut.message.orEmpty().contains("Timed out connecting"))

        val pubkeyOnly = classifySshConnectionException(
            error = JSchException("Auth fail for methods 'publickey'"),
            host = "203.0.113.10",
            port = 22,
            username = "ubuntu",
            authType = AuthType.PASSWORD,
            hostKeyRejectedByUser = false,
            hostKeyCheckFailed = false,
            handshakeLogs = listOf("Authentications that can continue: publickey")
        )
        assertTrue(pubkeyOnly is SshAuthenticationException)
        assertTrue(pubkeyOnly.message.orEmpty().contains("only accepts public key"))
        assertTrue((pubkeyOnly as SshAuthenticationException).technicalDetails.orEmpty().contains("publickey"))

        val declined = classifySshConnectionException(
            error = JSchException("UnknownHostKey"),
            host = "203.0.113.10",
            port = 22,
            username = "ubuntu",
            authType = AuthType.PASSWORD,
            hostKeyRejectedByUser = true,
            hostKeyCheckFailed = false,
            handshakeLogs = emptyList()
        )
        assertTrue(declined is HostKeyRejectedException)
    }

    @Test
    fun bouncyCastleProvider_enablesEd25519WithoutBreakingAesGcm() {
        SshCryptoProvider.ensureBouncyCastleProvider()
        val provider = Security.getProvider("BC")
        assertNotNull(provider)
        assertNotNull(KeyFactory.getInstance("Ed25519", "BC"))
        assertNotNull(Cipher.getInstance("AES/GCM/NoPadding"))
    }
}
