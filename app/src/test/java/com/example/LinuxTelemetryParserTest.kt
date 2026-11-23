package com.example

import com.example.domain.model.LogSeverity
import com.example.ssh.AnsiTerminalBuffer
import com.example.ssh.LinuxTelemetryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxTelemetryParserTest {

    @Test
    fun parseTelemetry_computesCpuDeltaMemoryDiskAndNetRates() {
        val sample1 = """
            ===HOST===
            prod-eu-1
            ===OS===
            Ubuntu 24.04.1 LTS
            ===KERNEL===
            6.8.0-45-generic
            ===UPTIME===
            up 12 days, 4 hours
            ===LOAD===
            0.42 0.38 0.31 1/180 1234
            ===STAT===
            cpu  1000 0 200 8800 0 0 0 0 0 0
            cpu0 500 0 100 4400 0 0 0 0 0 0
            cpu1 500 0 100 4400 0 0 0 0 0 0
            ===MEM===
            MemTotal:       4096000 kB
            MemFree:         512000 kB
            MemAvailable:   2048000 kB
            Buffers:         128000 kB
            Cached:          896000 kB
            SwapTotal:      1024000 kB
            SwapFree:        512000 kB
            ===DF===
            tank/ROOT/ubuntu 41943040 10485760 31457280 25% /
            ===NET===
            Inter-|   Receive                                                |  Transmit
              lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
            eth0: 100000 100 0 0 0 0 0 0 50000 50 0 0 0 0 0 0
            ===PS===
             1201  14.5  3.2 nginx
              840   6.0  8.1 postgres
        """.trimIndent()

        val first = LinuxTelemetryParser.parseTelemetry(
            rawOutput = sample1,
            previousCpuSamples = emptyList(),
            previousNetSample = null,
            nowMs = 10_000L
        )

        assertEquals("prod-eu-1", first.snapshot.hostname)
        assertEquals("Ubuntu 24.04.1 LTS", first.snapshot.osDistro)
        assertEquals(2, first.snapshot.perCoreUsage.size)
        assertEquals(2000L, first.snapshot.memUsedMb)
        assertEquals(4000L, first.snapshot.memTotalMb)
        assertEquals(1, first.snapshot.disks.size)
        assertEquals("/", first.snapshot.disks.first().mountPoint)
        assertEquals(2, first.snapshot.topProcesses.size)

        val sample2 = sample1
            .replace("cpu  1000 0 200 8800", "cpu  1250 0 250 9500")
            .replace("eth0: 100000 100 0 0 0 0 0 0 50000", "eth0: 120000 110 0 0 0 0 0 0 60000")

        val second = LinuxTelemetryParser.parseTelemetry(
            rawOutput = sample2,
            previousCpuSamples = first.cpuSamples,
            previousNetSample = first.netSample,
            nowMs = 12_000L
        )

        assertEquals(30.0f, second.snapshot.cpuTotalPercent, 0.2f)
        assertEquals(10_000L, second.snapshot.netRxBytesPerSec)
        assertEquals(5_000L, second.snapshot.netTxBytesPerSec)
    }

    @Test
    fun parseSystemdUnits_andValidateUnitNames() {
        val raw = """
            nginx.service loaded active running A high performance web server
            postgresql@16-main.service loaded failed failed PostgreSQL Cluster 16-main
            docker.service loaded inactive dead Docker Application Container Engine
        """.trimIndent()

        val units = LinuxTelemetryParser.parseSystemdUnits(raw)
        assertEquals(3, units.size)
        assertEquals("postgresql@16-main.service", units[0].name)
        assertTrue(units[0].isFailed)
        assertEquals("nginx.service", units[1].name)
        assertTrue(units[1].isActive)

        assertTrue(LinuxTelemetryParser.isValidSystemdUnitName("nginx.service"))
        assertTrue(LinuxTelemetryParser.isValidSystemdUnitName("postgresql@16-main.service"))
        assertFalse(LinuxTelemetryParser.isValidSystemdUnitName("nginx.service; rm -rf /"))
        assertFalse(LinuxTelemetryParser.isValidSystemdUnitName("$(whoami)"))
    }

    @Test
    fun parseJournalLine_parsesShortIsoAndSyslogFormats() {
        val isoLine = "2026-11-22T07:55:01+0000 prod-eu-1 nginx[1201]: upstream connection failed"
        val isoEntry = LinuxTelemetryParser.parseJournalLine(isoLine)
        assertNotNull(isoEntry)
        assertEquals("07:55:01", isoEntry!!.timestamp)
        assertEquals("nginx", isoEntry.unit)
        assertEquals(LogSeverity.ERR, isoEntry.severity)
        assertEquals("upstream connection failed", isoEntry.message)

        val syslogLine = "Nov 22 07:56:10 alpine-node sshd[412]: Warning: timeout waiting for auth"
        val sysEntry = LinuxTelemetryParser.parseJournalLine(syslogLine)
        assertNotNull(sysEntry)
        assertEquals("07:56:10", sysEntry!!.timestamp)
        assertEquals("sshd", sysEntry.unit)
        assertEquals(LogSeverity.WARN, sysEntry.severity)
        assertEquals("Warning: timeout waiting for auth", sysEntry.message)
    }

    @Test
    fun validatePrivateKeyFormat_andDetectSudoPrompt() {
        assertNotNull(LinuxTelemetryParser.validatePrivateKeyFormat("ssh-ed25519 AAAAC3NzaC1 user@host"))
        assertNotNull(LinuxTelemetryParser.validatePrivateKeyFormat("PuTTY-User-Key-File-2: ssh-rsa"))
        assertNull(
            LinuxTelemetryParser.validatePrivateKeyFormat(
                "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAA=\n-----END OPENSSH PRIVATE KEY-----"
            )
        )

        assertTrue(LinuxTelemetryParser.isSudoPasswordRequired("sudo: a password is required"))
        assertFalse(LinuxTelemetryParser.isSudoPasswordRequired("Failed to restart foo.service: Unit not found."))
        assertTrue(LinuxTelemetryParser.isIncorrectSudoPassword("Sorry, try again."))
        assertTrue(LinuxTelemetryParser.isIncorrectSudoPassword("sudo: 1 incorrect password attempt"))
        assertFalse(LinuxTelemetryParser.isIncorrectSudoPassword("Active: active (running)"))
    }

    @Test
    fun ansiTerminalBuffer_stripsOscAndSplitsLines() {
        val buffer = AnsiTerminalBuffer()
        val lines = buffer.appendChunkLines("\u001B]0;root@vps: ~\u0007\u001B[32mroot@vps\u001B[0m:~# ls\r\nfile1\r\n")
        assertEquals(3, lines.size)
        assertEquals("root@vps:~# ls", lines[0].text)
        assertEquals("file1", lines[1].text)
    }

    @Test
    fun ansiTerminalBuffer_handlesSplitEscapeSequencesAndCrLfAcrossChunks() {
        val buffer = AnsiTerminalBuffer()
        buffer.appendChunkLines("\u001B]0;title")
        buffer.appendChunkLines("\u0007\u001B")
        buffer.appendChunkLines("[32mhello\u001B[")
        val afterSgr = buffer.appendChunkLines("0m\r")
        assertEquals("hello", afterSgr[0].text)

        val afterLf = buffer.appendChunkLines("\nworld")
        assertEquals(2, afterLf.size)
        assertEquals("hello", afterLf[0].text)
        assertEquals("world", afterLf[1].text)
    }

    @Test
    fun formatHelpers_useDotDecimalRegardlessOfDefaultLocale() {
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5 KB/s", LinuxTelemetryParser.formatBytesPerSec(1536L))
            assertEquals("1.5 GB", LinuxTelemetryParser.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }
}
