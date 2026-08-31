package dev.tsdroid.ui.component

private val SPACER_REGEX = Regex(
    """^\[([clr*]?)spacer(\d*)\](.*)""",
    RegexOption.IGNORE_CASE,
)

enum class SpacerType { LEFT, CENTER, RIGHT, REPEAT }

data class SpacerInfo(
    val type: SpacerType,
    val displayText: String,
)

fun parseSpacer(name: String): SpacerInfo? {
    val match = SPACER_REGEX.find(name) ?: return null
    val type = when (match.groupValues[1].lowercase()) {
        "c" -> SpacerType.CENTER
        "l" -> SpacerType.LEFT
        "r" -> SpacerType.RIGHT
        "*" -> SpacerType.REPEAT
        "" -> SpacerType.LEFT
        else -> return null
    }
    return SpacerInfo(type, match.groupValues[3])
}
