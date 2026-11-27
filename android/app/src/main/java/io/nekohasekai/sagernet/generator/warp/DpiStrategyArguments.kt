package io.nekohasekai.sagernet.generator.warp

/** Splits text into argv without invoking a shell or interpreting escapes for ByeDPI. */
internal object DpiStrategyArguments {
    fun tokenize(raw: String): List<String> {
        require(raw.length <= 16384) { "The strategy exceeds the 16 KiB input limit." }
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        for (char in raw) {
            require(char in ' '..'~' || char == '\t' || char == '\n' || char == '\r') {
                "Use ASCII characters in strategy arguments."
            }
            when {
                quote == char -> quote = null
                quote != null -> token.append(char)
                char == '"' || char == '\'' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> if (started) {
                    tokens.add(token.toString())
                    token.clear()
                    started = false
                }
                else -> {
                    started = true
                    token.append(char)
                }
            }
        }
        require(quote == null) { "Close the quoted argument." }
        if (started) tokens.add(token.toString())
        return tokens
    }
}
