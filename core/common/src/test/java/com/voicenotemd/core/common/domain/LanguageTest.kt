package com.voicenotemd.core.common.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguageTest {
    @Test
    fun `should map plain primary tag when given lowercase code`() {
        assertThat(Language.fromBcp47("en")).isEqualTo(Language.English)
        assertThat(Language.fromBcp47("it")).isEqualTo(Language.Italian)
        assertThat(Language.fromBcp47("es")).isEqualTo(Language.Spanish)
    }

    @Test
    fun `should be case-insensitive when given uppercase code`() {
        assertThat(Language.fromBcp47("EN")).isEqualTo(Language.English)
        assertThat(Language.fromBcp47("It")).isEqualTo(Language.Italian)
    }

    @Test
    fun `should strip country suffix when given full BCP-47 tag`() {
        assertThat(Language.fromBcp47("en-US")).isEqualTo(Language.English)
        assertThat(Language.fromBcp47("pt-BR")).isEqualTo(Language.Portuguese)
        assertThat(Language.fromBcp47("de_AT")).isEqualTo(Language.German)
    }

    @Test
    fun `should strip script and region when given long tag`() {
        assertThat(Language.fromBcp47("en-Latn-GB")).isEqualTo(Language.English)
    }

    @Test
    fun `should return Unknown when given unsupported language`() {
        assertThat(Language.fromBcp47("ja")).isEqualTo(Language.Unknown)
        assertThat(Language.fromBcp47("zh-CN")).isEqualTo(Language.Unknown)
    }

    @Test
    fun `should return Unknown when given null or blank`() {
        assertThat(Language.fromBcp47(null)).isEqualTo(Language.Unknown)
        assertThat(Language.fromBcp47("")).isEqualTo(Language.Unknown)
        assertThat(Language.fromBcp47("   ")).isEqualTo(Language.Unknown)
    }
}
