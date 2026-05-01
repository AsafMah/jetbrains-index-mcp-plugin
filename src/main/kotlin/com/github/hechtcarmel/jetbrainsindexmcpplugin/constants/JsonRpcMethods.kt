package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

object JsonRpcMethods {
    const val INITIALIZE = "initialize"
    const val NOTIFICATIONS_INITIALIZED = "notifications/initialized"
    const val NOTIFICATIONS_CANCELLED = "notifications/cancelled"
    const val NOTIFICATIONS_PROGRESS = "notifications/progress"
    const val NOTIFICATIONS_ROOTS_LIST_CHANGED = "notifications/roots/list_changed"
    const val PING = "ping"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val RESOURCE_TEMPLATES_LIST = "resources/templates/list"
    const val RESOURCES_SUBSCRIBE = "resources/subscribe"
    const val RESOURCES_UNSUBSCRIBE = "resources/unsubscribe"
    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"
    const val COMPLETION_COMPLETE = "completion/complete"
    const val LOGGING_SET_LEVEL = "logging/setLevel"
}
