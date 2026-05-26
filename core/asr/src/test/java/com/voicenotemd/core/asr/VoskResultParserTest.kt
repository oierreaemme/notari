package com.voicenotemd.core.asr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoskResultParserTest {
    @Test
    fun `finalText extracts the text field`() {
        assertThat(VoskResultParser.finalText("""{"text" : "ciao come stai"}"""))
            .isEqualTo("ciao come stai")
    }

    @Test
    fun `partialText extracts the partial field`() {
        assertThat(VoskResultParser.partialText("""{"partial" : "ciao come"}"""))
            .isEqualTo("ciao come")
    }

    @Test
    fun `finalText ignores the per-word result array`() {
        val json = """{"text" : "hello world", "result" : [{"word":"hello"},{"word":"world"}]}"""
        assertThat(VoskResultParser.finalText(json)).isEqualTo("hello world")
    }

    @Test
    fun `finalText returns empty when text field absent`() {
        assertThat(VoskResultParser.finalText("""{"partial" : "x"}""")).isEmpty()
    }

    @Test
    fun `partialText returns empty when partial field absent`() {
        assertThat(VoskResultParser.partialText("""{"text" : "x"}""")).isEmpty()
    }

    @Test
    fun `finalText returns empty for an empty value`() {
        assertThat(VoskResultParser.finalText("""{"text" : ""}""")).isEmpty()
    }

    @Test
    fun `finalText unescapes quotes inside the value`() {
        assertThat(VoskResultParser.finalText("""{"text" : "say \"hi\" now"}"""))
            .isEqualTo("say \"hi\" now")
    }
}
