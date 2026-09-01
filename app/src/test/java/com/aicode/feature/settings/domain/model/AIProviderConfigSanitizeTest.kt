package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AIProviderConfigSanitizeTest {

    private fun config(
        name: String = "OpenAI",
        apiKey: String = "sk-abc",
        baseUrl: String = "https://api.openai.com/",
        models: List<String> = listOf("gpt-4o"),
        selectedModel: String = "gpt-4o",
        defaultModel: String = "gpt-4o",
        userAgent: String = "",
        balanceScriptPath: String = "",
        proxyHost: String = "",
        proxyUsername: String = "",
        proxyPassword: String = ""
    ) = AIProviderConfig(
        id = "p1",
        name = name,
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        userAgent = userAgent,
        balanceScriptPath = balanceScriptPath,
        proxyHost = proxyHost,
        proxyUsername = proxyUsername,
        proxyPassword = proxyPassword
    )

    @Test
    fun apiKey_stripsLineBreaksAndSpaces() {
        val sanitized = config(apiKey = " sk-abc\ndef \tghi\r\n").sanitized()

        assertEquals("sk-abcdefghi", sanitized.apiKey)
    }

    @Test
    fun baseUrl_stripsAllWhitespace() {
        val sanitized = config(baseUrl = " https://api.example.com/v1 \n").sanitized()

        assertEquals("https://api.example.com/v1", sanitized.baseUrl)
    }

    @Test
    fun proxyHost_stripsAllWhitespace() {
        val sanitized = config(proxyHost = " 127.0.0.1\n").sanitized()

        assertEquals("127.0.0.1", sanitized.proxyHost)
    }

    @Test
    fun textFields_keepInnerSpacesButDropLineBreaks() {
        val sanitized = config(
            name = " My\nProvider ",
            userAgent = " AiCode/1.0 (Android)\n",
            balanceScriptPath = " ~/.aicode/scripts/my panel.py \n",
            proxyUsername = " user name\n",
            proxyPassword = " pa ss\r\n"
        ).sanitized()

        assertEquals("MyProvider", sanitized.name)
        assertEquals("AiCode/1.0 (Android)", sanitized.userAgent)
        assertEquals("~/.aicode/scripts/my panel.py", sanitized.balanceScriptPath)
        assertEquals("user name", sanitized.proxyUsername)
        assertEquals("pa ss", sanitized.proxyPassword)
    }

    @Test
    fun models_trimmedDeduplicatedAndEmptiesDropped() {
        val sanitized = config(
            models = listOf(" gpt-4o ", "gpt-4o\n", "", "  ", "o3-mini\r"),
            selectedModel = " gpt-4o\n",
            defaultModel = "gpt-4o\r"
        ).sanitized()

        assertEquals(listOf("gpt-4o", "o3-mini"), sanitized.models)
        assertEquals("gpt-4o", sanitized.selectedModel)
        assertEquals("gpt-4o", sanitized.defaultModel)
    }

    @Test
    fun cleanConfig_unchanged() {
        val clean = config()

        assertEquals(clean, clean.sanitized())
    }
}
