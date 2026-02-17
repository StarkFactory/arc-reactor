package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private val structuredOutputValidatorObjectMapper = jacksonObjectMapper()

class StructuredOutputValidator {

    fun isValidFormat(content: String, format: ResponseFormat): Boolean {
        return when (format) {
            ResponseFormat.JSON -> validateJson(content)
            ResponseFormat.YAML -> validateYaml(content)
            ResponseFormat.TEXT -> true
        }
    }

    fun stripMarkdownCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        val startIdx = 1 // skip first ``` line
        val endIdx = if (lines.last().trim() == "```") lines.size - 1 else lines.size
        return lines.subList(startIdx, endIdx).joinToString("\n").trim()
    }

    private fun validateJson(content: String): Boolean {
        return try {
            structuredOutputValidatorObjectMapper.readTree(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun validateYaml(content: String): Boolean {
        return try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val result = yaml.load<Any>(content)
            result != null
        } catch (e: Exception) {
            false
        }
    }
}
