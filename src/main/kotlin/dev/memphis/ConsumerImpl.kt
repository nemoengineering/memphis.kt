package dev.memphis

import io.nats.client.JetStreamSubscription
import java.nio.charset.Charset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

internal class ConsumerImpl constructor(
    private val memphis: Memphis,
    override val name: String,
    private val stationName: String,
    override val group: String,
    override val pullInterval: Duration,
    override val batchSize: Int,
    override val batchMaxTimeToWait: Duration,
    override val maxAckTime: Duration,
    override val maxMsgDeliveries: Int,
    private val subscription: JetStreamSubscription
) : Lifecycle, Consumer {
    private val logger = KotlinLogging.logger {}

    private val pingConsumerInterval = 30.seconds
    private var subscriptionStatus = SubscriptionStatus.ACTIVE
    private var consumingStatus = ConsumingStatus.INACTIVE
    private var tickerJob: Job? = null
    private var pingJob: Job? = null

    override suspend fun consume(): Flow<Message> {
        if (consumingStatus == ConsumingStatus.ACTIVE) throw MemphisError("Already consuming")

        return startConsumeLoop().also {
            consumingStatus = ConsumingStatus.ACTIVE
        }
    }

    private suspend fun startConsumeLoop() = channelFlow {
        subscribeDlq {
            logger.debug { "Received DLQ Message: ${it.data} Headers: ${it.headers}" }
            send(it)
        }
        tickerJob = launch {
            while (true) {
                subscription.fetch(batchSize, batchMaxTimeToWait.toJavaDuration())
                    .forEach {
                        println("Received Message")
                        logger.trace { "Received Message: ${it.data.toString(Charset.defaultCharset())} Headers: ${it.headers.toStringAll()}" }
                        send(Message(it, memphis, group))
                    }

                delay(pullInterval)
            }
        }
    }

    override suspend fun fetch(): Flow<Message> = flow {
        consumingStatus = ConsumingStatus.ACTIVE
        while (currentCoroutineContext().isActive) {
            subscription.pull(1)
            val msg = subscription.nextMessage(0)
            emit(Message(msg, memphis, group))
        }
    }

    internal fun pingConsumer() = memphis.scope.launch {
        if (subscriptionStatus != SubscriptionStatus.ACTIVE) {
            throw MemphisError("started ping for inactive subscription")
        }

        pingJob = launch {
            while (true) {
                logger.debug { "Ping Consumer" }
                try {
                    subscription.consumerInfo

                } catch (_: Exception) {
                    subscriptionStatus = SubscriptionStatus.INACTIVE
                    kotlin.runCatching { stopConsuming() }
                    throw MemphisError("Station unreachable")
                }
                delay(pingConsumerInterval)
            }
        }
    }

    private suspend fun subscribeDlq(callback: suspend (msg: Message) -> Unit) {
        memphis.brokerDispatch.subscribe(
            "${'$'}memphis_dlq_${stationName}_${group}",
            "${'$'}memphis_dlq_${stationName}_${group}"
        )
        {
            runBlocking {
                callback(Message(it, memphis, group))
            }
        }
    }

    override fun stopConsuming() {
        if (consumingStatus == ConsumingStatus.INACTIVE) throw MemphisError("Consumer is inactive")
        tickerJob?.cancel()
        consumingStatus = ConsumingStatus.INACTIVE
    }

    override suspend fun destroy() {
        if (consumingStatus == ConsumingStatus.ACTIVE) {
            stopConsuming()
        }
        if (subscriptionStatus == SubscriptionStatus.ACTIVE) {
            pingJob!!.cancel()
        }

        memphis.destroyResource(this)
    }

    override fun getCreationSubject(): String = "${'$'}memphis_consumer_creations"

    override fun getCreationRequest(): JsonObject = buildJsonObject {
        put("name", name)
        put("station_name", stationName)
        put("connection_id", memphis.connectionId)
        put("consumer_type", "application")
        put("consumers_group", group)
        put("max_ack_time_ms", maxAckTime.inWholeMilliseconds)
        put("max_msg_deliveries", maxMsgDeliveries)
    }

    override fun getDestructionSubject(): String = "${'$'}memphis_consumer_destructions"

    override fun getDestructionRequest(): JsonObject = buildJsonObject {
        put("name", name)
        put("station_name", stationName)
    }

    enum class SubscriptionStatus {
        INACTIVE,
        ACTIVE
    }

    enum class ConsumingStatus {
        INACTIVE,
        ACTIVE
    }
}
