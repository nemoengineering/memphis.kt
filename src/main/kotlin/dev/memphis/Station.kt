package dev.memphis

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class StorageType(internal val value: String) {
    DISK("file"),
    MEMORY("memory")
}

enum class RetentionType(internal val value: String) {
    MAX_AGE_SECONDS("message_age_sec"),
    MESSAGES("messages"),
    BYTES("bytes")
}

interface Station {
    val name: String
    val retentionType: RetentionType
    val retentionValue: Int
    val storageType: StorageType
    val replicas: Int
    val idempotencyWindow: Duration
    val schemaName: String?

    fun attachSchema(schemaName: String)

    fun detachSchema()

    suspend fun destroy()

    class Options {
        var retentionType = RetentionType.MAX_AGE_SECONDS
        var retentionValue = 604800
        var storageType = StorageType.DISK
        var replicas = 1
        var idempotencyWindow = 2.minutes
        //var schemaName: String? = null # Available in next release
    }
}