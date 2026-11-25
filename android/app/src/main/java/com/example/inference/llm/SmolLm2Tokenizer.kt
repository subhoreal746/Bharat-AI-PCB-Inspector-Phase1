package com.example.inference.llm

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import org.json.JSONObject

/**
 * Native-accurate BPE Tokenizer for SmolLM2 (GPT-2 ByteLevel BPE with ChatML added tokens).
 * Reads the actual `tokenizer.json` artifact to compute exact token counts without approximation.
 */
class SmolLm2Tokenizer(
    private val vocab: Map<String, Int>,
    private val bpeRanks: Map<String, Int>,
    private val addedTokens: Map<String, Int>
) {
    val vocabSize: Int get() = vocab.size
    val addedTokensCount: Int get() = addedTokens.size
    val bosTokenId: Int get() = addedTokens["<|im_start|>"] ?: 1
    val eosTokenId: Int get() = addedTokens["<|im_end|>"] ?: 2
    val endOfTextTokenId: Int get() = addedTokens["<|endoftext|>"] ?: 0

    private val idToPiece: Map<Int, String> = vocab.entries.associate { (k, v) -> v to k }
    private val idToAdded: Map<Int, String> = addedTokens.entries.associate { (k, v) -> v to k }

    private val specialTokensRegex: Pattern? = if (addedTokens.isNotEmpty()) {
        val patternStr = addedTokens.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Pattern.quote(it) }
        Pattern.compile("($patternStr)")
    } else {
        null
    }

    private val wordPattern: Pattern = Pattern.compile(
        """'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+"""
    )

    fun countTokens(text: String): Int {
        return encode(text).size
    }

    /**
     * Looks up an inferred token ID for a string fragment (e.g. emitted by LlmCallback).
     *
     * IMPORTANT AUDIT & DIAGNOSTIC CAVEATS:
     * 1. Reconstructed IDs are heuristic/inferred, NOT authentic native token IDs. ExecuTorch 1.6.0
     *    JNI delivers decoded UTF-8 string fragments via `make_jstring(cstr)`; native integer token IDs
     *    are not passed over the JNI boundary.
     * 2. ByteLevel BPE mapping is NOT uniquely reversible at individual fragment boundaries:
     *    - Raw byte tokens for multi-byte UTF-8 continuations decode to identical non-printable characters.
     *    - Leading space characters (e.g. `Ġ` / U+0120) produce different token IDs than bare words.
     *    - Compound/merged tokens (e.g. `{"` vs `{` and `"`) cannot be uniquely decomposed in isolation.
     * 3. This function returns null if the fragment represents multiple BPE subwords or cannot be matched
     *    in the single-token vocabulary. Callers must treat non-null returns as inferred heuristics only.
     */
    fun lookupTokenId(fragment: String): Int? {
        val added = addedTokens[fragment]
        if (added != null) return added
        val utf8Bytes = fragment.toByteArray(StandardCharsets.UTF_8)
        val byteEncoded = buildString(utf8Bytes.size) {
            for (b in utf8Bytes) {
                append(BYTE_TO_CHAR[b.toInt() and 0xFF])
            }
        }
        return vocab[byteEncoded] ?: vocab[fragment]
    }

    /**
     * Decodes a single token ID back to its original UTF-8 string representation.
     */
    fun decodeToken(id: Int): String? {
        val added = idToAdded[id]
        if (added != null) return added
        val piece = idToPiece[id] ?: return null
        val bytes = ByteArray(piece.length) { i ->
            (CHAR_TO_BYTE[piece[i]] ?: piece[i].code).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Decodes a list of token IDs back into full text.
     */
    fun decode(tokens: List<Int>): String {
        return tokens.joinToString("") { decodeToken(it).orEmpty() }
    }

    fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<Int>()
        val specialRegex = specialTokensRegex

        if (specialRegex == null) {
            encodeNormalText(text, tokens)
        } else {
            val matcher = specialRegex.matcher(text)
            var lastEnd = 0
            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                if (start > lastEnd) {
                    encodeNormalText(text.substring(lastEnd, start), tokens)
                }
                val specialText = matcher.group(1) ?: text.substring(start, end)
                val id = addedTokens[specialText]
                if (id != null) {
                    tokens.add(id)
                }
                lastEnd = end
            }
            if (lastEnd < text.length) {
                encodeNormalText(text.substring(lastEnd), tokens)
            }
        }
        return tokens
    }

    private fun encodeNormalText(text: String, tokens: MutableList<Int>) {
        val matcher = wordPattern.matcher(text)
        while (matcher.find()) {
            val word = matcher.group()
            val utf8Bytes = word.toByteArray(StandardCharsets.UTF_8)
            val byteEncoded = StringBuilder(utf8Bytes.size)
            for (b in utf8Bytes) {
                val unsigned = b.toInt() and 0xFF
                byteEncoded.append(BYTE_TO_CHAR[unsigned])
            }
            val bpePieces = bpe(byteEncoded.toString())
            for (piece in bpePieces) {
                val id = vocab[piece]
                if (id != null) {
                    tokens.add(id)
                }
            }
        }
    }

    private fun bpe(token: String): List<String> {
        if (token.length <= 1) return listOf(token)
        var word = token.map { it.toString() }
        while (word.size > 1) {
            var minRank = Int.MAX_VALUE
            var bestPair: Pair<String, String>? = null

            for (i in 0 until word.size - 1) {
                val pairKey = "${word[i]} ${word[i + 1]}"
                val rank = bpeRanks[pairKey] ?: Int.MAX_VALUE
                if (rank < minRank) {
                    minRank = rank
                    bestPair = Pair(word[i], word[i + 1])
                }
            }

            if (bestPair == null || minRank == Int.MAX_VALUE) break

            val first = bestPair.first
            val second = bestPair.second
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i++
                }
            }
            word = newWord
        }
        return word
    }

    companion object {
        private val BYTE_TO_CHAR: Array<Char> = run {
            val bs = mutableListOf<Int>()
            for (i in '!'.code..'~'.code) bs.add(i)
            for (i in '¡'.code..'¬'.code) bs.add(i)
            for (i in '®'.code..'ÿ'.code) bs.add(i)
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (!bs.contains(b)) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val arr = Array(256) { ' ' }
            for (i in bs.indices) {
                arr[bs[i]] = cs[i].toChar()
            }
            arr
        }

        private val CHAR_TO_BYTE: Map<Char, Int> = BYTE_TO_CHAR.mapIndexed { idx, ch -> ch to idx }.toMap()

        fun load(tokenizerFile: File): SmolLm2Tokenizer {
            val jsonText = tokenizerFile.readText(StandardCharsets.UTF_8)
            val root = JSONObject(jsonText)

            val addedTokens = mutableMapOf<String, Int>()
            if (root.has("added_tokens")) {
                val arr = root.getJSONArray("added_tokens")
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    addedTokens[item.getString("content")] = item.getInt("id")
                }
            }

            val modelObj = root.getJSONObject("model")
            val vocabObj = modelObj.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabObj.length())
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                vocab[k] = vocabObj.getInt(k)
            }

            val mergesArr = modelObj.getJSONArray("merges")
            val bpeRanks = HashMap<String, Int>(mergesArr.length())
            for (i in 0 until mergesArr.length()) {
                bpeRanks[mergesArr.getString(i)] = i
            }

            return SmolLm2Tokenizer(vocab, bpeRanks, addedTokens)
        }
    }
}
