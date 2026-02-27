package com.assistant.tools.knowledge

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.MemoryPort
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

class KnowledgeIngestTool(
    private val memory: MemoryPort,
    private val userId: String = "knowledge"
) : ToolPort {
    override val name = "knowledge"
    override val description = "Ingest documents into memory for later retrieval. Supports .txt, .md, .pdf"

    override fun commands() = listOf(
        CommandSpec(
            name = "knowledge_ingest",
            description = "Read a file and store its content as searchable memory chunks",
            params = listOf(
                ParamSpec("path", "string", "Absolute path to the file (.txt, .md, or .pdf)")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "knowledge_ingest") return Observation.Error("Unknown command: ${call.name}")
        val path = call.arguments["path"] as? String
            ?: return Observation.Error("Missing required parameter: path")
        val file = File(path)
        if (!file.exists()) return Observation.Error("File not found: $path")
        return runCatching {
            val text = withContext(Dispatchers.IO) {
                when (file.extension.lowercase()) {
                    "pdf" -> extractPdf(file)
                    else -> file.readText()
                }
            }
            val chunks = chunk(text)
            chunks.forEach { memory.saveFact(userId, it) }
            Observation.Success("Ingested ${chunks.size} chunks from ${file.name}")
        }.getOrElse { Observation.Error(it.message ?: "Ingestion failed") }
    }

    private fun extractPdf(file: File): String =
        Loader.loadPDF(file).use { doc -> PDFTextStripper().getText(doc) }

    private fun chunk(text: String, size: Int = 512, overlap: Int = 80): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            chunks.add(text.substring(start, minOf(start + size, text.length)))
            start += size - overlap
        }
        return chunks
    }
}
