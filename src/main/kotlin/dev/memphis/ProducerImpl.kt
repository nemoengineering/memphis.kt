package dev.memphis

import io.nats.client.Message
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.NatsMessage
import java.nio.charset.Charset
import kotlin.time.toJavaDuration
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import mu.KotlinLogging

class ProducerImpl(
    private val memphis: Memphis,
    override val name: String,
    override val stationName: String
) : Producer, Lifecycle {

    private val logger = KotlinLogging.logger {}

    override fun produceAsync(message: ByteArray, options: (Producer.ProduceOptions.() -> Unit)?) {
        memphis.scope.launch { callProduce(message, options) }
    }

    override suspend fun produce(message: ByteArray, options: (Producer.ProduceOptions.() -> Unit)?) {
        val res = callProduce(message, options)
        if (res.hasError()) throw MemphisError(res.error)
    }

    private suspend fun callProduce(
        message: ByteArray,
        opts: (Producer.ProduceOptions.() -> Unit)? = null
    ): PublishAck {

        val options = opts?.let { Producer.ProduceOptions().apply(it) } ?: Producer.ProduceOptions()

        options.headers.putUnchecked("${'$'}memphis_connectionId", memphis.connectionId)
        options.headers.putUnchecked("${'$'}memphis_producedBy", name)

        options.messageId?.also { options.headers.putUnchecked("msg-id", it) }

        logger.trace { "Publish Message: ${message.toString(Charset.defaultCharset())} Headers: ${options.headers.headers.toStringAll()}" }

        val data = validateMessage(message)

        val natsMsg = NatsMessage.builder()
            .subject(stationName.toInternalName() + ".final")
            .headers(options.headers.headers)
            .data(data)
            .build()

        val pubOpts = PublishOptions.builder().streamTimeout(options.ackWait.toJavaDuration()).build()
        return memphis.brokerPublish(natsMsg, pubOpts).await()
    }


    private fun validateMessage(message: ByteArray): ByteArray {
        val schema = try {
            getSchema()
        } catch (e: Exception) {
            sendNotification {
                title = "Schema validation has failed"
                this.message = "Station: $stationName\nProducer: $name\nError: ${e.message}"
                code = message.toString(Charset.defaultCharset())
                type = schemaVFailAlertType
            }
            throw MemphisError("Schema validation has failed", e)
        }

        return schema.validateMessage(message)
    }

    private fun sendNotification(block: Notification.() -> Unit) {
        val msg = Notification().apply(block).let { Json.encodeToString(it) }
        memphis.brokerConnection.publish("${'$'}memphis_notifications", msg.toByteArray())
    }

    @Serializable
    private class Notification {
        lateinit var title: String

        @SerialName("msg")
        lateinit var message: String
        lateinit var code: String
        lateinit var type: String
    }

    private fun getSchema(): Schema =
        memphis.getStationSchema(stationName)

    override suspend fun destroy() {
        memphis.destroyResource(this)
    }

    override fun getCreationSubject(): String =
        "${'$'}memphis_producer_creations"

    override fun getCreationRequest(): JsonObject = buildJsonObject {
        put("name", name)
        put("station_name", stationName)
        put("connection_id", memphis.connectionId)
        put("producer_type", "application")
        put("req_version", 1)
    }

    override suspend fun handleCreationResponse(msg: Message) {
        val res = try {
            Json.decodeFromString<CreateProducerResponse>(msg.data.toString(Charset.defaultCharset()))
        } catch (_: Exception) {
            return super.handleCreationResponse(msg)
        }

        if (res.error != "") throw MemphisError(res.error)
        memphis.stationUpdateManager.applySchema(stationName, res.schemaUpdate)
    }

    override fun getDestructionSubject(): String =
        "${'$'}memphis_producer_destructions"

    override fun getDestructionRequest(): JsonObject = buildJsonObject {
        put("name", name)
        put("station_name", stationName)
    }

    @Serializable
    private data class CreateProducerResponse(
        val error: String,
        @SerialName("schema_update") val schemaUpdate: SchemaUpdateInit
    )

    companion object {
        private const val schemaVFailAlertType = "schema_validation_fail_alert"
    }

}