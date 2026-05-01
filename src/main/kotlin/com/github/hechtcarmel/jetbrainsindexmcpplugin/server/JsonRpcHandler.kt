package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.JsonRpcMethods
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class JsonRpcHandler @JvmOverloads constructor(
    private val toolRegistry: ToolRegistry,
    private val recordHistory: (Project, CommandEntry) -> Unit = { project, entry ->
        CommandHistoryService.getInstance(project).recordCommand(entry)
    },
    private val updateHistory: (Project, String, CommandStatus, String?, Long?) -> Unit = { project, id, status, result, duration ->
        CommandHistoryService.getInstance(project).updateCommandStatus(id, status, result, duration)
    }
) {
    private val projectResolver = ProjectResolver
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<JsonRpcHandler>()
        private const val RESOURCE_SCHEME = "ide-index"
        private const val RESOURCE_MIME_JSON = "application/json"
        private const val RESOURCE_MIME_TEXT = "text/plain"
        private const val MAX_RESOURCE_FILES = 500
        private const val MAX_COMPLETION_VALUES = 100
        private const val MAX_SYMBOL_COMPLETIONS = 50
        private const val MAX_DIAGNOSTIC_MESSAGES = 100

        private val KNOWN_NOTIFICATIONS = setOf(
            JsonRpcMethods.NOTIFICATIONS_INITIALIZED,
            JsonRpcMethods.NOTIFICATIONS_CANCELLED,
            JsonRpcMethods.NOTIFICATIONS_PROGRESS,
            JsonRpcMethods.NOTIFICATIONS_ROOTS_LIST_CHANGED
        )

        private val LOG_LEVELS = setOf(
            "debug",
            "info",
            "notice",
            "warning",
            "error",
            "critical",
            "alert",
            "emergency"
        )
    }

    private var loggingLevel: String = "info"

    suspend fun handleRequest(jsonString: String): String? =
        handleRequest(jsonString, McpConstants.MCP_PROTOCOL_VERSION)

    suspend fun handleRequest(
        jsonString: String,
        protocolVersion: String
    ): String? {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(jsonString)
        } catch (e: Exception) {
            LOG.warn("Failed to parse JSON-RPC request", e)
            return json.encodeToString(createErrorResponse(code = JsonRpcErrorCodes.PARSE_ERROR, message = ErrorMessages.PARSE_ERROR))
        }

        if (request.jsonrpc != "2.0") {
            return json.encodeToString(createErrorResponse(
                id = request.id,
                code = JsonRpcErrorCodes.INVALID_REQUEST,
                message = "Invalid JSON-RPC version: ${request.jsonrpc}. Expected \"2.0\"."
            ))
        }

        val response = try {
            routeRequest(request, protocolVersion)
        } catch (e: Exception) {
            LOG.error("Error processing request: ${request.method}", e)
            createErrorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Unknown error")
        }

        return response?.let { json.encodeToString(response) }
    }

    private suspend fun routeRequest(request: JsonRpcRequest, protocolVersion: String): JsonRpcResponse? {
        return when (request.method) {
            JsonRpcMethods.INITIALIZE -> processInitialize(request, protocolVersion)
            in KNOWN_NOTIFICATIONS -> null
            JsonRpcMethods.TOOLS_LIST -> processToolsList(request)
            JsonRpcMethods.TOOLS_CALL -> processToolCall(request)
            JsonRpcMethods.RESOURCES_LIST -> processResourcesList(request)
            JsonRpcMethods.RESOURCES_READ -> processResourcesRead(request)
            JsonRpcMethods.RESOURCE_TEMPLATES_LIST -> processResourceTemplatesList(request)
            JsonRpcMethods.RESOURCES_SUBSCRIBE -> processEmptyResult(request)
            JsonRpcMethods.RESOURCES_UNSUBSCRIBE -> processEmptyResult(request)
            JsonRpcMethods.PROMPTS_LIST -> processPromptsList(request)
            JsonRpcMethods.PROMPTS_GET -> processPromptsGet(request)
            JsonRpcMethods.COMPLETION_COMPLETE -> processCompletionComplete(request)
            JsonRpcMethods.LOGGING_SET_LEVEL -> processLoggingSetLevel(request)
            JsonRpcMethods.PING -> processPing(request)
            else -> if (request.id == null && request.method.startsWith("notifications/")) {
                null
            } else {
                createErrorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, ErrorMessages.methodNotFound(request.method))
            }
        }
    }

    private fun processInitialize(request: JsonRpcRequest, protocolVersion: String): JsonRpcResponse {
        val result = InitializeResult(
            protocolVersion = protocolVersion,
            serverInfo = ServerInfo(
                name = McpConstants.SERVER_NAME,
                version = McpConstants.SERVER_VERSION,
                description = McpConstants.SERVER_DESCRIPTION
            ),
            capabilities = ServerCapabilities(
                tools = ToolCapability(listChanged = false)
            )
        )

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private fun processToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = toolRegistry.getToolDefinitions()
        val result = ToolsListResult(tools = tools)

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private fun processResourcesList(request: JsonRpcRequest): JsonRpcResponse {
        val resources = mutableListOf(
            ResourceDefinition(
                uri = "$RESOURCE_SCHEME://projects",
                name = "Open projects",
                description = "JSON list of projects currently open in the IDE.",
                mimeType = RESOURCE_MIME_JSON
            )
        )

        openProjects().forEach { project ->
            val encodedProject = encodeUriPart(project.basePath ?: project.name)
            val projectName = project.name
            resources += ResourceDefinition(
                uri = "$RESOURCE_SCHEME://project/$encodedProject/files",
                name = "$projectName project files",
                description = "JSON list of indexed project files, capped at $MAX_RESOURCE_FILES entries.",
                mimeType = RESOURCE_MIME_JSON
            )
            resources += ResourceDefinition(
                uri = "$RESOURCE_SCHEME://project/$encodedProject/active-file",
                name = "$projectName active file",
                description = "JSON details for the selected editor file(s), including caret and selection metadata.",
                mimeType = RESOURCE_MIME_JSON
            )
            resources += ResourceDefinition(
                uri = "$RESOURCE_SCHEME://project/$encodedProject/diagnostics",
                name = "$projectName diagnostics snapshot",
                description = "JSON snapshot of the last build diagnostics and visible test results.",
                mimeType = RESOURCE_MIME_JSON
            )
            resources += ResourceDefinition(
                uri = "$RESOURCE_SCHEME://project/$encodedProject/command-history",
                name = "$projectName command history",
                description = "JSON export of recent MCP tool calls for this project.",
                mimeType = RESOURCE_MIME_JSON
            )
        }

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(ResourcesListResult(resources = resources))
        )
    }

    private fun processResourcesRead(request: JsonRpcRequest): JsonRpcResponse {
        val uriString = request.params?.get("uri")?.jsonPrimitive?.contentOrNull
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing required parameter: uri")

        val resourceContent = try {
            readResource(uriString)
        } catch (e: IllegalArgumentException) {
            return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, e.message ?: "Invalid resource URI")
        } catch (e: Exception) {
            LOG.warn("Failed to read MCP resource: $uriString", e)
            return createErrorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Unable to read resource")
        }

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(ResourceReadResult(contents = listOf(resourceContent)))
        )
    }

    private fun processResourceTemplatesList(request: JsonRpcRequest): JsonRpcResponse {
        val templates = listOf(
            ResourceTemplateDefinition(
                uriTemplate = "$RESOURCE_SCHEME://project/{project_path}/file/{file}",
                name = "Project file",
                description = "Read a project file. URI path variables must be URL encoded.",
                mimeType = RESOURCE_MIME_TEXT
            ),
            ResourceTemplateDefinition(
                uriTemplate = "$RESOURCE_SCHEME://project/{project_path}/symbol/{symbol}",
                name = "Project symbol search",
                description = "Search project symbols by name. URI path variables must be URL encoded.",
                mimeType = RESOURCE_MIME_JSON
            )
        )

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(ResourceTemplatesListResult(resourceTemplates = templates))
        )
    }

    private fun processPromptsList(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(PromptsListResult(prompts = promptDefinitions()))
        )
    }

    private fun processPromptsGet(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_PARAMS)
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing required parameter: name")
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        if (promptDefinitions().none { it.name == name }) {
            return createErrorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Prompt not found: $name")
        }
        val prompt = buildPrompt(name, arguments)
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing required prompt argument for: $name")

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(prompt)
        )
    }

    private fun processCompletionComplete(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_PARAMS)
        val argument = params["argument"]?.jsonObject
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing required parameter: argument")
        val argumentName = argument["name"]?.jsonPrimitive?.contentOrNull
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing required parameter: argument.name")
        val value = argument["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contextArguments = params["context"]?.jsonObject?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())

        val values = when (argumentName) {
            ParamNames.PROJECT_PATH, "project_path" -> completeProjectPaths(value)
            ParamNames.FILE, "file" -> completeProjectFiles(contextArguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull, value)
            "symbol", ParamNames.QUALIFIED_NAME -> completeSymbols(contextArguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull, value)
            else -> emptyList()
        }.distinct().sorted().take(MAX_COMPLETION_VALUES)

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(
                CompletionResult(
                    completion = CompletionValues(
                        values = values,
                        total = values.size,
                        hasMore = false
                    )
                )
            )
        )
    }

    private fun processLoggingSetLevel(request: JsonRpcRequest): JsonRpcResponse {
        val level = request.params?.get("level")?.jsonPrimitive?.contentOrNull
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing required parameter: level")
        if (level !in LOG_LEVELS) {
            return createErrorResponse(
                request.id,
                JsonRpcErrorCodes.INVALID_PARAMS,
                "Invalid logging level: $level. Expected one of: ${LOG_LEVELS.joinToString(", ")}"
            )
        }

        loggingLevel = level
        LOG.info("MCP logging level set to $loggingLevel")
        return processEmptyResult(request)
    }

    private suspend fun processToolCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_PARAMS)

        val toolName = params[ParamNames.NAME]?.jsonPrimitive?.contentOrNull
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_TOOL_NAME)

        val arguments = params[ParamNames.ARGUMENTS]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, ErrorMessages.toolNotFound(toolName))

        // Extract optional project_path from arguments
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull

        val projectResult = projectResolver.resolve(projectPath)
        if (projectResult.isError) {
            return JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(projectResult.errorResult!!)
            )
        }

        val project = projectResult.project!!

        // Record command in history
        val commandEntry = CommandEntry(
            toolName = toolName,
            parameters = arguments
        )

        recordHistorySafely(project, commandEntry)

        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(project, arguments)
            val duration = System.currentTimeMillis() - startTime

            // Update history
            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = result.content.firstOrNull()?.let {
                    when (it) {
                        is ContentBlock.Text -> it.text
                        is ContentBlock.Image -> "[Image]"
                    }
                },
                duration = duration
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            LOG.error("Tool execution failed: $toolName", e)

            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = CommandStatus.ERROR,
                result = e.message,
                duration = duration
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(
                    ToolCallResult(
                        content = listOf(ContentBlock.Text(text = e.message ?: ErrorMessages.UNKNOWN_ERROR)),
                        isError = true
                    )
                )
            )
        }
    }

    private fun recordHistorySafely(project: Project, commandEntry: CommandEntry) {
        try {
            recordHistory(project, commandEntry)
        } catch (e: Exception) {
            LOG.warn("Failed to record command history for ${commandEntry.toolName}", e)
        }
    }

    private fun updateHistorySafely(
        project: Project,
        commandEntry: CommandEntry,
        status: CommandStatus,
        result: String?,
        duration: Long
    ) {
        try {
            updateHistory(project, commandEntry.id, status, result, duration)
        } catch (e: Exception) {
            LOG.warn("Failed to update command history for ${commandEntry.toolName}", e)
        }
    }

    private fun processPing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private fun processEmptyResult(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private fun readResource(uriString: String): ResourceContent {
        val uri = URI(uriString)
        require(uri.scheme == RESOURCE_SCHEME) { "Unsupported resource URI scheme: ${uri.scheme}" }

        if (uri.host == "projects") {
            return ResourceContent(
                uri = uriString,
                mimeType = RESOURCE_MIME_JSON,
                text = json.encodeToString(buildProjectsPayload())
            )
        }

        require(uri.host == "project") { "Unsupported resource URI host: ${uri.host}" }
        val pathSegments = uri.rawPath.trim('/').split('/').filter { it.isNotBlank() }
        require(pathSegments.size >= 2) { "Project resource URI must include project path and resource kind" }

        val projectPath = decodeUriPart(pathSegments[0])
        val resourceKind = pathSegments[1]
        val projectResult = projectResolver.resolve(projectPath)
        require(!projectResult.isError && projectResult.project != null) { "Project not found: $projectPath" }
        val project = projectResult.project

        return when (resourceKind) {
            "files" -> ResourceContent(uriString, RESOURCE_MIME_JSON, json.encodeToString(buildProjectFilesPayload(project)))
            "active-file" -> ResourceContent(uriString, RESOURCE_MIME_JSON, json.encodeToString(buildActiveFilePayload(project)))
            "diagnostics" -> ResourceContent(uriString, RESOURCE_MIME_JSON, json.encodeToString(buildDiagnosticsPayload(project)))
            "command-history" -> ResourceContent(uriString, RESOURCE_MIME_JSON, CommandHistoryService.getInstance(project).exportToJson())
            "file" -> {
                require(pathSegments.size >= 3) { "File resource URI must include a file path" }
                val filePath = decodeUriPart(pathSegments.drop(2).joinToString("/"))
                ResourceContent(uriString, RESOURCE_MIME_TEXT, readProjectFile(project, filePath))
            }
            "symbol" -> {
                require(pathSegments.size >= 3) { "Symbol resource URI must include a symbol query" }
                val symbolQuery = decodeUriPart(pathSegments.drop(2).joinToString("/"))
                ResourceContent(uriString, RESOURCE_MIME_JSON, json.encodeToString(buildSymbolPayload(project, symbolQuery)))
            }
            else -> throw IllegalArgumentException("Unsupported project resource kind: $resourceKind")
        }
    }

    private fun buildProjectsPayload(): JsonObject = buildJsonObject {
        putJsonArray("projects") {
            openProjects().forEach { project ->
                add(buildJsonObject {
                    put("name", project.name)
                    put("path", project.basePath)
                    putJsonArray("contentRoots") {
                        ProjectUtils.getModuleContentRoots(project).forEach { add(it) }
                    }
                })
            }
        }
    }

    private fun buildProjectFilesPayload(project: Project): JsonObject = buildJsonObject {
        val files = collectProjectFiles(project, "", MAX_RESOURCE_FILES)
        put("project", project.name)
        put("path", project.basePath)
        put("truncated", files.size >= MAX_RESOURCE_FILES)
        putJsonArray("files") {
            files.forEach { file ->
                add(buildJsonObject {
                    put("name", file.name)
                    put("path", file.path)
                    put("directory", file.directory)
                })
            }
        }
    }

    private fun buildActiveFilePayload(project: Project): JsonObject {
        val activeFiles = runOnEdt {
            FileEditorManager.getInstance(project).selectedEditors.mapNotNull { fileEditor ->
                val virtualFile = fileEditor.file ?: return@mapNotNull null
                val textEditor = fileEditor as? TextEditor
                val caret = textEditor?.editor?.caretModel?.primaryCaret
                val selectionModel = textEditor?.editor?.selectionModel
                buildJsonObject {
                    put("file", ProjectUtils.getToolFilePath(project, virtualFile))
                    put("line", caret?.logicalPosition?.line?.plus(1))
                    put("column", caret?.logicalPosition?.column?.plus(1))
                    put("hasSelection", selectionModel?.hasSelection() ?: false)
                    put("selectedText", selectionModel?.selectedText)
                    put("language", virtualFile.fileType.name)
                }
            }
        }
        return buildJsonObject {
            putJsonArray("activeFiles") { activeFiles.forEach { add(it) } }
        }
    }

    private fun buildDiagnosticsPayload(project: Project): JsonObject {
        val cacheService = BuildDiagnosticsCacheService.getInstance(project)
        val buildMessages = cacheService.getLastBuildDiagnostics().take(MAX_DIAGNOSTIC_MESSAGES)
        val testResults = TestResultsCollector.collect(project, "failed", "all", MAX_DIAGNOSTIC_MESSAGES)

        return buildJsonObject {
            put("project", project.name)
            put("buildTimestamp", cacheService.getLastBuildTimestamp())
            put("buildMessageCount", buildMessages.size)
            putJsonArray("buildMessages") {
                buildMessages.forEach { message ->
                    add(json.encodeToJsonElement(message))
                }
            }
            put("testResultsTruncated", testResults?.truncated)
            testResults?.testSummary?.let { put("testSummary", json.encodeToJsonElement(it)) }
            putJsonArray("testResults") {
                testResults?.testResults.orEmpty().forEach { add(json.encodeToJsonElement(it)) }
            }
        }
    }

    private fun buildSymbolPayload(project: Project, symbolQuery: String): JsonObject = buildJsonObject {
        val symbols = completeSymbols(project.basePath, symbolQuery)
        put("query", symbolQuery)
        putJsonArray("symbols") { symbols.forEach { add(it) } }
    }

    private fun readProjectFile(project: Project, filePath: String): String {
        val virtualFile = resolveProjectVirtualFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")
        require(!virtualFile.isDirectory) { "Resource points to a directory, not a file: $filePath" }
        return String(virtualFile.contentsToByteArray(), virtualFile.charset)
    }

    private fun completeProjectPaths(prefix: String): List<String> =
        openProjects().mapNotNull { it.basePath }.filter { it.startsWith(prefix, ignoreCase = true) }

    private fun completeProjectFiles(projectPath: String?, prefix: String): List<String> {
        val project = projectResolver.resolve(projectPath).project ?: return emptyList()
        return collectProjectFiles(project, prefix, MAX_COMPLETION_VALUES).map { it.path }
    }

    private fun completeSymbols(projectPath: String?, prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        val project = projectResolver.resolve(projectPath).project ?: return emptyList()
        if (DumbService.isDumb(project)) return emptyList()

        return runCatching {
            ReadAction.compute<List<String>, RuntimeException> {
                OptimizedSymbolSearch.search(
                    project = project,
                    pattern = prefix,
                    scope = GlobalSearchScope.projectScope(project),
                    limit = MAX_SYMBOL_COMPLETIONS
                ).map { symbol ->
                    listOfNotNull(symbol.qualifiedName, symbol.name).first()
                }
            }
        }.getOrElse {
            LOG.debug("Symbol completion failed for '$prefix'", it)
            emptyList()
        }
    }

    private fun collectProjectFiles(project: Project, prefix: String, limit: Int): List<FileMatch> {
        return runCatching {
            ReadAction.compute<List<FileMatch>, RuntimeException> {
                val files = mutableListOf<FileMatch>()
                val fileIndex = ProjectFileIndex.getInstance(project)
                fileIndex.iterateContent { virtualFile ->
                    if (!virtualFile.isDirectory && files.size < limit) {
                        val path = ProjectUtils.getRelativePath(project, virtualFile)
                        if (prefix.isBlank() || path.startsWith(prefix, ignoreCase = true) || virtualFile.name.startsWith(prefix, ignoreCase = true)) {
                            files += FileMatch(
                                name = virtualFile.name,
                                path = path,
                                directory = virtualFile.parent?.let { ProjectUtils.getRelativePath(project, it) }.orEmpty()
                            )
                        }
                    }
                    files.size < limit
                }
                files
            }
        }.onFailure {
            LOG.debug("Failed to collect project files for ${project.name}", it)
        }.getOrDefault(emptyList())
    }

    private fun resolveProjectVirtualFile(project: Project, filePath: String) =
        com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils.resolveVirtualFileAnywhere(project, filePath)

    private fun promptDefinitions(): List<PromptDefinition> = listOf(
        PromptDefinition(
            name = "ide_analyze_file",
            description = "Analyze a file using IDE-backed MCP tools.",
            arguments = listOf(
                PromptArgumentDefinition(ParamNames.PROJECT_PATH, "Project path when multiple IDE projects are open."),
                PromptArgumentDefinition(ParamNames.FILE, "File path to analyze.", required = true)
            )
        ),
        PromptDefinition(
            name = "ide_refactor_plan",
            description = "Create a safe IDE-assisted refactoring plan.",
            arguments = listOf(
                PromptArgumentDefinition(ParamNames.PROJECT_PATH, "Project path when multiple IDE projects are open."),
                PromptArgumentDefinition(ParamNames.FILE, "File containing the symbol or code to refactor."),
                PromptArgumentDefinition("symbol", "Symbol to refactor.")
            )
        ),
        PromptDefinition(
            name = "ide_debug_failure",
            description = "Investigate build, test, or diagnostic failures with IDE context.",
            arguments = listOf(
                PromptArgumentDefinition(ParamNames.PROJECT_PATH, "Project path when multiple IDE projects are open.")
            )
        ),
        PromptDefinition(
            name = "ide_explain_symbol",
            description = "Explain a symbol using definition, references, and hierarchy tools.",
            arguments = listOf(
                PromptArgumentDefinition(ParamNames.PROJECT_PATH, "Project path when multiple IDE projects are open."),
                PromptArgumentDefinition("symbol", "Symbol name or qualified name to explain.", required = true)
            )
        )
    )

    private fun buildPrompt(name: String, arguments: JsonObject): PromptGetResult? {
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.contentOrNull
        val symbol = arguments["symbol"]?.jsonPrimitive?.contentOrNull
        val projectClause = projectPath?.let { " Use project_path `$it`." }.orEmpty()

        val text = when (name) {
            "ide_analyze_file" -> {
                if (file.isNullOrBlank()) return null
                "Analyze `$file` with IDE context.$projectClause Use diagnostics, file structure, definitions, references, and relevant build/test information. Summarize findings and recommend focused next steps."
            }
            "ide_refactor_plan" -> {
                val target = listOfNotNull(symbol?.let { "symbol `$it`" }, file?.let { "file `$it`" }).joinToString(" in ").ifBlank { "the requested code" }
                "Create a safe refactoring plan for $target.$projectClause Use IDE navigation and refactoring tools to identify references, implementations, and risks before suggesting changes."
            }
            "ide_debug_failure" ->
                "Investigate the current failure using IDE diagnostics, last build output, test results, active file context, and relevant source navigation.$projectClause Identify the root cause and propose the smallest safe fix."
            "ide_explain_symbol" -> {
                if (symbol.isNullOrBlank()) return null
                "Explain symbol `$symbol` using IDE-backed definition, reference, implementation, hierarchy, and file-structure tools.$projectClause Include where it is defined, how it is used, and important relationships."
            }
            else -> return null
        }

        return PromptGetResult(
            description = promptDefinitions().firstOrNull { it.name == name }?.description,
            messages = listOf(PromptMessage(role = "user", content = ContentBlock.Text(text = text)))
        )
    }

    private fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDefault }

    private fun <T> runOnEdt(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        val result = arrayOfNulls<Any>(1)
        var failure: Throwable? = null
        application.invokeAndWait {
            try {
                result[0] = action()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    private fun encodeUriPart(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeUriPart(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun createErrorResponse(
        id: JsonElement? = null,
        code: Int,
        message: String
    ) = JsonRpcResponse(
        id = id,
        error = JsonRpcError(code = code, message = message)
    )
}
