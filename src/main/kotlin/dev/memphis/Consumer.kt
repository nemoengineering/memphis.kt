package dev.memphis

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

interface Consumer {
    val name: String
    val group: String
    val pullInterval: Duration
    val batchSize: Int
    val batchMaxTimeToWait: Duration
    val maxAckTime: Duration
    val maxMsgDeliveries: Int

    suspend fun consume(): Flow<Message>

    fun stopConsuming()

    fun destroy()

    class Options {
        var consumerGroup: String? = null
        var pullInterval: Duration = 1.seconds
        var batchSize: Int = 10
        var batchMaxTimeToWait: Duration = 5.seconds
        var maxAckTime: Duration = 30.seconds
        var maxMsgDeliveries: Int = 10
        var genUniqueSuffix: Boolean = false
    }
}