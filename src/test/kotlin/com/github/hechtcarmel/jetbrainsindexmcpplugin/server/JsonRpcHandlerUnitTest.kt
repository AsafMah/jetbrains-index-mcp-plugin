package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.JsonRpcMethods
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JsonRpcHandlerUnitTest : TestCase() {

    private lateinit var handler: JsonRpcHandler
    private lateinit var toolRegistry: ToolRegistry

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        handler = JsonRpcHandler(toolRegistry)
    }

    fun testInitializeRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION)
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Initialize should not return error", response.error)
        assertNotNull("Initialize should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should contain serverInfo", result["serverInfo"])
        assertNotNull("Result should contain capabilities", result["capabilities"])
        val capabilities = result["capabilities"]!!.jsonObject
        assertNotNull("Capabilities should contain tools", capabilities["tools"])
        assertNotNull("Capabilities should contain resources", capabilities["resources"])
        assertNotNull("Capabilities should contain prompts", capabilities["prompts"])
        assertNotNull("Capabilities should contain completions", capabilities["completions"])
        assertNotNull("Capabilities should contain logging", capabilities["logging"])

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals(McpConstants.SERVER_NAME, serverInfo["name"]?.jsonPrimitive?.content)
        assertNotNull("serverInfo should contain description", serverInfo["description"])
        assertTrue(
            "description should mention code intelligence",
            serverInfo["description"]?.jsonPrimitive?.content?.contains("code intelligence", ignoreCase = true) == true
        )
    }

    fun testInitializeRequestCanOverrideProtocolVersion() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = "2024-11-05"
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Initialize should not return error", response.error)
        assertEquals(
            "2024-11-05",
            response.result!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testPingRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(4),
            method = JsonRpcMethods.PING
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.PING} should not return error", response.error)
        assertNotNull("${JsonRpcMethods.PING} should return result", response.result)
    }

    fun testMethodNotFound() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(5),
            method = "unknown/method"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Unknown method should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testToolCallMissingParams() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(6),
            method = JsonRpcMethods.TOOLS_CALL
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} without params should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallMissingToolName() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(7),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.ARGUMENTS, buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} without tool name should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallUnknownTool() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(8),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "unknown_tool")
                put(ParamNames.ARGUMENTS, buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} with unknown tool should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testParseError() = runBlocking {
        val responseJson = handler.handleRequest("not valid json")
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Invalid JSON should return error", response.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, response.error?.code)
    }

    fun testInvalidJsonRpcVersion() = runBlocking {
        val requestJson = """{"jsonrpc":"1.0","id":1,"method":"ping"}"""

        val responseJson = handler.handleRequest(requestJson)
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Invalid jsonrpc version should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, response.error?.code)
        assertTrue(
            "Error message should mention version",
            response.error?.message?.contains("2.0") == true
        )
    }

    fun testNotificationReturnsNull() = runBlocking {
        val requestJson = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val responseJson = handler.handleRequest(requestJson)

        assertNull("Notification should return null (no response)", responseJson)
    }

    fun testKnownCancellationNotificationReturnsNull() = runBlocking {
        val requestJson = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}"""

        val responseJson = handler.handleRequest(requestJson)

        assertNull("Cancellation notification should return null (no response)", responseJson)
    }

    fun testResourceTemplatesListRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(10),
            method = JsonRpcMethods.RESOURCE_TEMPLATES_LIST
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.RESOURCE_TEMPLATES_LIST} should not return error", response.error)
        val templates = response.result!!.jsonObject["resourceTemplates"]!!.jsonArray
        assertTrue("Should expose resource templates", templates.isNotEmpty())
    }

    fun testPromptsListRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(11),
            method = JsonRpcMethods.PROMPTS_LIST
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.PROMPTS_LIST} should not return error", response.error)
        val prompts = response.result!!.jsonObject["prompts"]!!.jsonArray
        assertTrue("Should expose prompts", prompts.isNotEmpty())
    }

    fun testPromptsGetRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(12),
            method = JsonRpcMethods.PROMPTS_GET,
            params = buildJsonObject {
                put("name", "ide_analyze_file")
                put("arguments", buildJsonObject {
                    put("file", "src/main/kotlin/App.kt")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.PROMPTS_GET} should not return error", response.error)
        val messages = response.result!!.jsonObject["messages"]!!.jsonArray
        assertEquals("Should return one prompt message", 1, messages.size)
    }

    fun testLoggingSetLevelRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(13),
            method = JsonRpcMethods.LOGGING_SET_LEVEL,
            params = buildJsonObject {
                put("level", "debug")
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.LOGGING_SET_LEVEL} should not return error", response.error)
        assertNotNull("${JsonRpcMethods.LOGGING_SET_LEVEL} should return result", response.result)
    }
}
