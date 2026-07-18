package com.mythara.minimax

enum class Region(val label: String, val baseUrl: String) {
    Global("Groq (Free)", "https://api.groq.com/openai/v1/"),
    China("OpenRouter (Free)", "https://openrouter.ai/api/v1/");

    companion object {
        val Default: Region = Global
        fun fromId(id: String?): Region = entries.firstOrNull { it.name == id } ?: Default
    }
}
