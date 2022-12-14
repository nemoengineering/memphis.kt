package dev.memphis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SchemaUpdate(
    @SerialName("UpdateType") val updateType: SchemaUpdateType,
    val init: SchemaUpdateInit?
)

private object SchemaUpdateTypeSerilizer : EnumOrdinalSerilizer<SchemaUpdateType>(SchemaUpdateType::class, 1)

@Serializable(with = SchemaUpdateTypeSerilizer::class)
internal enum class SchemaUpdateType {
    INIT,
    DROP
}

@Serializable
internal data class SchemaUpdateInit(
    @SerialName("schema_name") val schemaName: String,
    @SerialName("active_version") val schemaVersion: SchemaVersion,
    @SerialName("type") val schemaType: SchemaType
)

internal enum class SchemaType {
    @SerialName("")
    NO_SCHEMA,

    @SerialName("json")
    JSON,

    @SerialName("protobuf")
    PROTOBUF
}

@Serializable
internal data class SchemaVersion(
    @SerialName("version_number") val number: Int,
    val descriptor: String,
    @SerialName("schema_content") val content: String,
    @SerialName("message_struct_name") val messageStructName: String
)