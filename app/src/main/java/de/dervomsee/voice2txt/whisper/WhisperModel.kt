package de.dervomsee.voice2txt.whisper

data class WhisperModel(
    val name: String,
    val fileName: String
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun fromFileName(fileName: String): WhisperModel {
            // Convert ggml-base-q8_0.bin to "Base Q8_0"
            val name = fileName
                .removePrefix("ggml-")
                .removeSuffix(".bin")
                .split("-", ".")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            return WhisperModel(name, fileName)
        }
    }
}

val availableModels = listOf(
    WhisperModel("Tiny (Fastest)", "ggml-tiny.bin"),
    WhisperModel("Tiny Q8_0 (Recommended)", "ggml-tiny-q8_0.bin"),
    WhisperModel("Base", "ggml-base.bin"),
    WhisperModel("Base Q8_0", "ggml-base-q8_0.bin"),
    WhisperModel("Small", "ggml-small.bin"),
    WhisperModel("Small Q8_0", "ggml-small-q8_0.bin"),
    WhisperModel("Medium Q8_0", "ggml-medium-q8_0.bin"),
    WhisperModel("Large v3 Turbo", "ggml-large-v3-turbo.bin"),
    WhisperModel("Large v3 Turbo Q8_0", "ggml-large-v3-turbo-q8_0.bin")
)
