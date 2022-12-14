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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    fun consumer(stationName: String, consumerName: String) =
        consumer(stationName, consumerName, Consumer.Options())


    fun consumer(stationName: String, consumerName: String, options: Consumer.Options.() -> Unit) =
        consumer(stationName, consumerName, Consumer.Options().apply(options))

    private fun consumer(stationName: String, consumerName: String, options: Consumer.Options): Consumer {
        val cName = if (options.genUniqueSuffix) {
            extendNameWithRandSuffix(consumerName)
        } else {
            consumerName
        }.toInternalName()

        val groupName = (options.consumerGroup ?: consumerName).toInternalName()

        val pullOptions = PullSubscribeOptions.builder()
            .durable(groupName)
            .build()

        val subscription = jetStream.subscribe("${stationName.toInternalName()}.final", pullOptions)

        val consumerImpl = ConsumerImpl(
            this,
            cName,
            stationName.toInternalName(),
            groupName,
            options.pullInterval,
            options.batchSize,
            options.batchMaxTimeToWait,
            options.maxAckTime,
            options.maxMsgDeliveries,
            subscription
        )
        createResource(consumerImpl)

        return consumerImpl
    }

    fun producer(stationName: String, producerName: String) =
        producer(stationName, producerName, Producer.Options())


    fun producer(stationName: String, producerName: String, options: Producer.Options.() -> Unit) =
        producer(stationName, producerName, Producer.Options().apply(options))

    private fun producer(stationName: String, producerName: String, options: Producer.Options): Producer {
        val pName = if (options.genUniqueSuffix) {
            extendNameWithRandSuffix(producerName)
        } else {
            producerName
        }.toInternalName()

        val producer = ProducerImpl(
            this,
            pName,
            stationName
        )

        scope.launch { stationUpdateManager.listenToSchemaUpdates(stationName.toInternalName()) }
        try {
            createResource(producer)
        } catch (e: Exception) {
            runBlocking { stationUpdateManager.removeSchemaUpdateListener(stationName.toInternalName()) }  // TODO: use suspend fun?
        }

        return producer
    }

    internal fun brokerPublish(message: NatsMessage, options: PublishOptions) =
        jetStream.publishAsync(message, options)


    internal fun getStationSchema(stationName: String): Schema {
        return stationUpdateManager[stationName.toInternalName()].schema
    }

    fun createStation(name: String): Station {
        return createStation(name, Station.Options())
    }

    fun createStation(name: String, options: Station.Options.() -> Unit): Station {
        return createStation(name, Station.Options().apply(options))
    }

    private fun createStation(name: String, options: Station.Options): Station {
        val station = StationImpl(
            this,
            name,
            options.retentionType,
            options.retentionValue,
            options.storageType,
            options.replicas,
            options.idempotencyWindow,
            "" // options.schemaName # Available in next release
        )

        try {
            createResource(station)
        } catch (e: MemphisError) {
            if (e.message!!.contains("already exist")) return station
        }

        return station
    }

    fun attachSchema(schemaName: String, stationName: String) {
        TODO("Available in next release")
        createResource(SchemaLifecycle.Attach(schemaName, stationName))
    }

    fun detachSchema(stationName: String) {
        TODO("Available in next release")
        destroyResource(SchemaLifecycle.Detach(stationName))
    }

    private fun createResource(d: Create) {
        val subject = d.getCreationSubject()
        val req = d.getCreationRequest()
        logger.debug { "Creating: $subject" }

        val data = brokerConnection.request(subject, req.toString().toByteArray()).get()

        d.handleCreationResponse(data)
    }

    internal fun destroyResource(d: Destroy) {
        val subject = d.getDestructionSubject()
        val req = d.getDestructionRequest()

        logger.debug { "Destroying: $subject" }

        val data = brokerConnection.request(subject, req.toString().toByteArray()).get().data

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
