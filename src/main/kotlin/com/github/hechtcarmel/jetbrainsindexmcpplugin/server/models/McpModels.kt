package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false
)

@Serializable
@JsonClassDiscriminator("type")
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String
    ) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String
    ) : ContentBlock()
}

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val description: String? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolCapability? = ToolCapability(),
    val resources: ResourceCapability? = ResourceCapability(),
    val prompts: PromptCapability? = PromptCapability(),
    val completions: JsonObject = JsonObject(emptyMap()),
    val logging: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ToolCapability(
    val listChanged: Boolean = false
)

@Serializable
data class ResourceCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = false
)

@Serializable
data class PromptCapability(
    val listChanged: Boolean = false
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = "2025-03-26",
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: ServerInfo
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class ResourcesListResult(
    val resources: List<ResourceDefinition>
)

@Serializable
data class ResourceTemplateDefinition(
    val uriTemplate: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class ResourceTemplatesListResult(
    val resourceTemplates: List<ResourceTemplateDefinition>
)

@Serializable
data class ResourceReadResult(
    val contents: List<ResourceContent>
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String,
    val text: String
)

@Serializable
data class PromptDefinition(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgumentDefinition> = emptyList()
)

@Serializable
data class PromptArgumentDefinition(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)

@Serializable
data class PromptsListResult(
    val prompts: List<PromptDefinition>
)

@Serializable
data class PromptGetResult(
    val description: String? = null,
    val messages: List<PromptMessage>
)

@Serializable
data class PromptMessage(
    val role: String,
    val content: ContentBlock
)

@Serializable
data class CompletionResult(
    val completion: CompletionValues
)

@Serializable
data class CompletionValues(
    val values: List<String>,
    val total: Int? = null,
    val hasMore: Boolean = false
)
