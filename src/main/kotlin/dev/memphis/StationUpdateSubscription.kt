package dev.memphis

import io.nats.client.MessageHandler
import io.nats.client.Subscription
import java.nio.charset.Charset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

internal class StationUpdateSubscription {
    private val logger = KotlinLogging.logger {}

    private var refCount: UInt = 1u
    private var subscription: Subscription? = null
    private val schemaUpdates = Channel<SchemaUpdate>()
    internal var schema: Schema = EmptySchema()

    fun increaseRefCount() = refCount++

    fun decreaseRefCount() = refCount--

    fun getRefCount() = refCount

    fun startSubscription(sub: Subscription) {
        subscription = sub
    }

    fun messageHandler() = MessageHandler {
        logger.debug { "Received schema update" }
        runBlocking {
            val update = Json.decodeFromString<SchemaUpdate>(it.data.toString(Charset.defaultCharset()))
            schemaUpdates.send(update)
        }

    }

    suspend fun updatesHandler(managerMutex: Mutex) {
        schemaUpdates.receiveAsFlow().collect {
            managerMutex.withLock {
                when (it.updateType) {
                    SchemaUpdateType.INIT -> applySchema(it.init!!)
                    SchemaUpdateType.DROP -> schema = EmptySchema()
                }
            }
        }
    }

    fun applySchema(init: SchemaUpdateInit) {
        schema = when (init.schemaType) {
            SchemaType.NO_SCHEMA -> EmptySchema()
            SchemaType.JSON -> JsonSchema(init.schemaName, init.schemaVersion)
            SchemaType.PROTOBUF -> ProtobufSchema(init.schemaName, init.schemaVersion)
        }
    }

    fun teardown() {
        schemaUpdates.close()
    }
}