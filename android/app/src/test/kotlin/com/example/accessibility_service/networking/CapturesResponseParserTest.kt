package com.example.accessibility_service.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CapturesResponseParserTest {
    @Test
    fun parsesValidSuccessConfigAndEmptyCommands() {
        val response = CapturesResponseParser.parse(validResponse(), sentEventCount = 1)

        assertEquals(1, response.accepted)
        assertEquals(0, response.skipped)
        assertEquals(30, response.config.batchSize)
        assertEquals(20, response.config.flushSeconds)
        assertEquals(emptyList<RawCaptureCommand>(), response.commands)
    }

    @Test
    fun acceptsOnlyBatchesWhoseEventsAreExactlyAccountedFor() {
        assertEquals(30, CapturesResponseParser.parse(validResponse(30, 0), 30).accepted)
        assertEquals(2, CapturesResponseParser.parse(validResponse(28, 2), 30).skipped)
        assertEquals(30, CapturesResponseParser.parse(validResponse(0, 30), 30).skipped)
        assertEquals(1, CapturesResponseParser.parse(validResponse(1, 0), 1).accepted)
    }

    @Test
    fun preservesCommandsAsRawJsonWithoutExecutingThem() {
        val response = CapturesResponseParser.parse(
            validResponse(commands = "[{\"type\":\"noop\"}, \"later\"]"),
            sentEventCount = 1,
        )

        assertEquals(listOf("{\"type\":\"noop\"}", "\"later\""), response.commands.map { it.json })
    }

    @Test
    fun rejectsMissingOrNonIntegerCounts() {
        assertInvalid("""{"skipped":0,"config":{"batch_size":30,"flush_seconds":20},"commands":[]}""")
        assertInvalid("""{"accepted":0,"config":{"batch_size":30,"flush_seconds":20},"commands":[]}""")
        assertInvalid("""{"accepted":1.5,"skipped":0,"config":{"batch_size":30,"flush_seconds":20},"commands":[]}""")
    }

    @Test
    fun rejectsNegativeCounts() {
        assertInvalid(validResponse(accepted = -1))
    }

    @Test
    fun rejectsPartiallyAccountedBatches() {
        assertInvalid(validResponse(accepted = 20, skipped = 2), sentCount = 30)
        assertInvalid(validResponse(accepted = 29, skipped = 0), sentCount = 30)
        assertInvalid(validResponse(accepted = 0, skipped = 0), sentCount = 1)
    }

    @Test
    fun rejectsOverAccountedBatches() {
        assertInvalid(validResponse(accepted = 31, skipped = 0), sentCount = 30)
        assertInvalid(validResponse(accepted = 29, skipped = 2), sentCount = 30)
    }

    @Test
    fun countAdditionCannotOverflowIntoAFalseMatch() {
        assertInvalid(
            validResponse(accepted = Int.MAX_VALUE, skipped = Int.MAX_VALUE),
            sentCount = -2,
        )
    }

    @Test
    fun rejectsMissingOrMalformedRequiredConfigAndCommands() {
        assertInvalid("""{"accepted":0,"skipped":0,"commands":[]}""")
        assertInvalid("""{"accepted":0,"skipped":0,"config":{"batch_size":"30","flush_seconds":20},"commands":[]}""")
        assertInvalid("""{"accepted":0,"skipped":0,"config":{"batch_size":30,"flush_seconds":20}}""")
    }

    @Test
    fun rejectsMalformedJsonAndHtml() {
        assertInvalid("{not-json")
        assertInvalid("<html>ok</html>")
    }

    private fun assertInvalid(body: String, sentCount: Int = 1) {
        assertThrows(InvalidCapturesResponseException::class.java) {
            CapturesResponseParser.parse(body, sentCount)
        }
    }
}
