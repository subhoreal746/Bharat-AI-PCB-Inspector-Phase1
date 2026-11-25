package com.example.inference.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmolLm2TokenizerTest {

    companion object {
        private lateinit var tokenizer: SmolLm2Tokenizer

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val candidates = listOf(
                File("../android_handoff_bundle/models/smollm2_360m/tokenizer.json"),
                File("android_handoff_bundle/models/smollm2_360m/tokenizer.json"),
                File("../../android_handoff_bundle/models/smollm2_360m/tokenizer.json"),
                File("/home/celric/Documents/Bharat AI SoC/android_handoff_bundle/models/smollm2_360m/tokenizer.json")
            )
            val tokFile = candidates.firstOrNull { it.exists() && it.length() > 0L }
                ?: error("Cannot find tokenizer.json in candidates: ${candidates.map { it.absolutePath }}")
            tokenizer = SmolLm2Tokenizer.load(tokFile)
        }
    }

    @Test
    fun `critical token IDs match SmolLM2 tokenizer vocabulary specification`() {
        // Special tokens
        assertEquals(0, tokenizer.endOfTextTokenId)
        assertEquals(1, tokenizer.bosTokenId)
        assertEquals(2, tokenizer.eosTokenId)

        // Single punctuation & structural tokens
        assertEquals(18, tokenizer.lookupTokenId("\""))
        assertEquals(198, tokenizer.lookupTokenId("\n"))
        assertEquals(216, tokenizer.lookupTokenId(" "))
        assertEquals(107, tokenizer.lookupTokenId("{"))
        assertEquals(109, tokenizer.lookupTokenId("}"))
        assertEquals(42, tokenizer.lookupTokenId(":"))
        assertEquals(28, tokenizer.lookupTokenId(","))

        // Multi-character combined tokens
        assertEquals(39428, tokenizer.lookupTokenId("{\""))
        assertEquals(1093, tokenizer.lookupTokenId("\"\""))
        assertEquals(1116, tokenizer.lookupTokenId("\n\n"))
        assertEquals(256, tokenizer.lookupTokenId("  "))
        assertEquals(289, tokenizer.lookupTokenId("    "))
    }

    @Test
    fun `decodeToken reconstructs exact string fragments`() {
        assertEquals("<|endoftext|>", tokenizer.decodeToken(0))
        assertEquals("<|im_start|>", tokenizer.decodeToken(1))
        assertEquals("<|im_end|>", tokenizer.decodeToken(2))
        assertEquals("\"", tokenizer.decodeToken(18))
        assertEquals("\n", tokenizer.decodeToken(198))
        assertEquals(" ", tokenizer.decodeToken(216))
        assertEquals("{", tokenizer.decodeToken(107))
        assertEquals("}", tokenizer.decodeToken(109))
        assertEquals(":", tokenizer.decodeToken(42))
        assertEquals(",", tokenizer.decodeToken(28))
        assertEquals("{\"", tokenizer.decodeToken(39428))
        assertEquals("\"\"", tokenizer.decodeToken(1093))
        assertEquals("\n\n", tokenizer.decodeToken(1116))
        assertEquals("  ", tokenizer.decodeToken(256))
        assertEquals("    ", tokenizer.decodeToken(289))
    }

    @Test
    fun `repetitive token sequence of 18 and 198 decodes to alternating quote and newline`() {
        val loopTokens = listOf(18, 198, 18, 198, 18, 198)
        val decoded = tokenizer.decode(loopTokens)
        assertEquals("\"\n\"\n\"\n", decoded)
        assertEquals(6, decoded.length)
        assertTrue(decoded.all { it == '\"' || it == '\n' })
    }

    @Test
    fun `lookupTokenId maps special ChatML and control tokens`() {
        assertEquals(0, tokenizer.lookupTokenId("<|endoftext|>"))
        assertEquals(1, tokenizer.lookupTokenId("<|im_start|>"))
        assertEquals(2, tokenizer.lookupTokenId("<|im_end|>"))
    }

    @Test
    fun `tokenizer vocabulary size and special tokens count match HuggingFace artifact`() {
        assertEquals("SmolLM2 base vocabulary size must be 49152", 49152, tokenizer.vocabSize)
        assertTrue("Added tokens count must be at least 3", tokenizer.addedTokensCount >= 3)
    }

    @Test
    fun `encode correctly tokenizes ChatML formatting and text`() {
        val prompt = "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n"
        val tokens = tokenizer.encode(prompt)

        assertTrue("First token must be <|im_start|> (1)", tokens.isNotEmpty() && tokens.first() == 1)
        assertTrue("Tokens must contain <|im_end|> (2)", tokens.contains(2))

        val decoded = tokenizer.decode(tokens)
        assertEquals(prompt, decoded)
    }

    @Test
    fun `one-shot JSON prefix tokenizes into expected initial JSON token`() {
        val jsonStart = "{\""
        val tokens = tokenizer.encode(jsonStart)
        assertEquals(listOf(39428), tokens)
    }

    @Test
    fun `lookupTokenId exhibits expected BPE boundary limitations on multi-token phrases and whitespace`() {
        // Multi-token phrase returns null (cannot be represented by a single token)
        org.junit.Assert.assertNull(tokenizer.lookupTokenId("root_cause"))

        // Leading whitespace creates distinct tokens from bare words
        val bareWordId = tokenizer.lookupTokenId("hello")
        val leadingSpaceId = tokenizer.lookupTokenId(" hello")
        assertNotNull(bareWordId)
        assertNotNull(leadingSpaceId)
        org.junit.Assert.assertNotEquals(bareWordId, leadingSpaceId)
    }
}
