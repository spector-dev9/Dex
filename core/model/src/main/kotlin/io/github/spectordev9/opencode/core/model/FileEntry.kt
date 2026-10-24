package io.github.spectordev9.opencode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val absolute: String,
    val type: FileEntryType,
    val ignored: Boolean,
)

@Serializable
enum class FileEntryType {
    @SerialName("file")
    FILE,

    @SerialName("directory")
    DIRECTORY,
}
