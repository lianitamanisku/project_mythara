package com.mythara.minimax

enum class Region(val label: String, val baseUrl: String) {
    Global("Global (minimax.io)", "https://api.minimax.io/v1/"),
    China ("China (minimaxi.com)", "https://api.minimaxi.com/v1/"),
    Groq("Groq (free tier)", "https://api.groq.com/openai/v1/"),
    OpenRouter("OpenRouter (free tier)", "https://openrouter.ai/api/v1/");

    companion object {
        val Default: Region = Global
        fun fromId(id: String?): Region = entries.firstOrNull { it.name == id } ?: Default
    }
}

