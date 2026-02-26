package com.assistant.llm

data class ModelConfig(val provider: String, val model: String, val apiKey: String?, val baseUrl: String?, val fastModel: String? = null)
