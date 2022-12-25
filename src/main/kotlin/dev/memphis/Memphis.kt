package dev.memphis

import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
import io.nats.client.impl.NatsMessage
import java.nio.charset.Charset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import mu.KotlinLogging

class Memphis private constructor(
    val host: String,
    val username: String,
    connectionToken: String,
    val port: Int,
    val autoReconnect: Boolean,
    val maxReconnects: Int,
    val reconnectWait: Duration,
    val connectionTimeout: Duration
) {
    private val logger = KotlinLogging.logger {}

    internal val scope = CoroutineScope(Job())

    val connectionId = generateRandomHex(12)

    internal val brokerConnection: Connection =
        io.nats.client.Options.Builder()
            .server("nats://${host}:${port}")
            .connectionName("$connectionId::${username}")
            .token(connectionToken.toCharArray())
            .connectionTimeout(connectionTimeout.toJavaDuration())
            .reconnectWait(reconnectWait.toJavaDuration())
            .maxReconnects(maxReconnects)
            .let { if (autoReconnect) it else it.noReconnect() }
            .build()
            .let { Nats.connect(it) }

    internal val brokerDispatch: Dispatcher = brokerConnection.createDispatcher()
    private val jetStream: JetStream = brokerConnection.jetStream()

    internal val stationUpdateManager: StationUpdateManager = StationUpdateManager(brokerDispatch, scope)

    fun isConnected() = brokerConnection.status == Connection.Status.CONNECTED

    fun close() {
        scope.cancel()
        brokerConnection.close()
    }

    suspend fun consumer(
        stationName: String,
        consumerName: String,
        options: (Consumer.Options.() -> Unit)? = null
    ): Consumer {
        val opts = options?.let { Consumer.Options().apply(it) } ?: Consumer.Options()

        val cName = if (opts.genUniqueSuffix) {
            extendNameWithRandSuffix(consumerName)
        } else {
            consumerName
        }.toInternalName()

        val groupName = (opts.consumerGroup ?: consumerName).toInternalName()

        val pullOptions = PullSubscribeOptions.builder()
            .durable(groupName)
            .build()

        val subscription = jetStream.subscribe("${stationName.toInternalName()}.final", pullOptions)

        val consumerImpl = ConsumerImpl(
            this,
            cName,
            stationName.toInternalName(),
            groupName,
            opts.pullInterval,
            opts.batchSize,
            opts.batchMaxTimeToWait,
            opts.maxAckTime,
            opts.maxMsgDeliveries,
            subscription
        )
        createResource(consumerImpl)
        consumerImpl.pingConsumer()

        return consumerImpl
    }

    suspend fun producer(
        stationName: String,
        producerName: String,
        options: (Producer.Options.() -> Unit)? = null
    ): Producer {
        val opts = options?.let { Producer.Options().apply(it) } ?: Producer.Options()

        val pName = if (opts.genUniqueSuffix) {
            extendNameWithRandSuffix(producerName)
        } else {
            producerName
        }.toInternalName()

        val producer = ProducerImpl(
            this,
            pName,
            stationName.toInternalName()
        )

        stationUpdateManager.listenToSchemaUpdates(stationName.toInternalName())
        try {
            createResource(producer)
        } catch (e: Exception) {
            stationUpdateManager.removeSchemaUpdateListener(stationName.toInternalName())
            e.printStackTrace()
        }

        return producer
    }

    internal fun brokerPublish(message: NatsMessage, options: PublishOptions) =
        jetStream.publishAsync(message, options)


    internal fun getStationSchema(stationName: String): Schema {
        return stationUpdateManager[stationName.toInternalName()].schema
    }

    suspend fun createStation(name: String, options: (Station.Options.() -> Unit)? = null): Station {
        val opts = options?.let { Station.Options().apply(it) } ?: Station.Options()

        val station = StationImpl(
            this,
            name.toInternalName(),
            opts.retentionType,
            opts.retentionValue,
            opts.storageType,
            opts.replicas,
            opts.idempotencyWindow,
            "" // options.schemaName # Available in next release
        )

        try {
            createResource(station)
        } catch (e: MemphisError) {
            if (e.message!!.contains("already exist")) return station
        }

        return station
    }

    suspend fun attachSchema(schemaName: String, stationName: String) {
        TODO("Available in next release")
        createResource(SchemaLifecycle.Attach(schemaName, stationName))
    }

    suspend fun detachSchema(stationName: String) {
        TODO("Available in next release")
        destroyResource(SchemaLifecycle.Detach(stationName))
    }

    private suspend fun createResource(d: Create) {
        val subject = d.getCreationSubject()
        val req = d.getCreationRequest()
        logger.debug { "Creating: $subject" }

        val data = brokerConnection.request(subject, req.toString().toByteArray()).await()

        d.handleCreationResponse(data)
    }

    internal suspend fun destroyResource(d: Destroy) {
        val subject = d.getDestructionSubject()
        val req = d.getDestructionRequest()

        logger.debug { "Destroying: $subject" }

        val data = brokerConnection.request(subject, req.toString().toByteArray()).await().data

        if (data.isNotEmpty() && !data.toString(Charset.defaultCharset()).contains("not exist")) {
            throw MemphisError(data)
        }
    }

    class Options(
        private val host: String,
        private val username: String,
        private val connectionToken: String,
    ) {
        var port = 6666;
        var autoReconnect = true;
        var maxReconnects = 3;
        var reconnectWait = 5.seconds;
        var connectionTimeout = 15.seconds;

        internal fun build() = Memphis(
            host, username, connectionToken, port, autoReconnect, maxReconnects, reconnectWait, connectionTimeout
        )
    }

    companion object {
        fun connect(host: String, username: String, connectionToken: String): Memphis =
            Options(host, username, connectionToken).build()

        fun connect(host: String, username: String, connectionToken: String, options: Options.() -> Unit): Memphis =
            Options(host, username, connectionToken).apply(options).build()
    }
}
