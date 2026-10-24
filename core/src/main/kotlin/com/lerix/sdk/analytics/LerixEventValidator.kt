package com.lerix.sdk.analytics

/**
 * Local event validation (contract §3.1). Never throws: an invalid event is
 * dropped, invalid props are removed. The server re-validates.
 */
internal object LerixEventValidator {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9 _.:-]{1,63}$")
    private val PROP_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")
    const val MAX_PROPS = 25
    const val MAX_STRING = 1024

    val RESERVED = setOf(
        "\$first_open", "\$session_start", "\$session_end", "\$screen_view", "\$app_update",
        // Release Control (contract-release-control §2.4).
        "\$update_prompt_shown", "\$update_prompt_action", "\$maintenance_shown", "\$config_applied",
    )

    /** [allowReserved] is only true for the SDK's own automatic events. */
    fun isValidName(name: String, allowReserved: Boolean = false): Boolean {
        if (name.startsWith("$")) return allowReserved && name in RESERVED
        return NAME.matches(name)
    }

    fun isValidPropKey(key: String): Boolean = PROP_KEY.matches(key)

    /**
     * Returns the props to send: invalid keys and values are dropped, strings
     * longer than 1024 chars are truncated, and at most 25 keys are kept (in
     * iteration order). Values are string, finite number, bool or null.
     */
    fun sanitizeProps(props: Map<String, Any?>?, onDrop: (String) -> Unit = {}): Map<String, Any?> {
        if (props.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((key, raw) in props) {
            if (out.size >= MAX_PROPS) {
                onDrop("too many props, dropped \"$key\"")
                continue
            }
            if (!isValidPropKey(key)) {
                onDrop("invalid prop key \"$key\"")
                continue
            }
            val value = sanitizeValue(raw)
            if (value === INVALID) {
                onDrop("invalid value for prop \"$key\"")
                continue
            }
            out[key] = value
        }
        return out
    }

    private val INVALID = Any()

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> value.take(MAX_STRING)
        is Boolean -> value
        is Int, is Long, is Short, is Byte -> (value as Number).toLong()
        is Double -> if (value.isFinite()) value else INVALID
        is Float -> if (value.isFinite()) value.toDouble() else INVALID
        is Number -> value.toDouble().takeIf { it.isFinite() } ?: INVALID
        is Char -> value.toString()
        is Enum<*> -> value.name
        else -> INVALID
    }
}
