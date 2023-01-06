package dev.memphis.sdk

import java.nio.charset.Charset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val memphis = dev.memphis.sdk.Memphis.Companion.connect("localhost", "adrian", "memphis")

        val consumer = memphis.consumer("test", "consumer_name_2") {
            genUniqueSuffix = true
            maxMsgDeliveries = 1
            batchSize = 1
        }

        println("collecting")
        launch {
            consumer.fetch().collect {
                println("Received message:")
                println(it.data.toString(Charset.defaultCharset()))
                println(it.headers)
                println()
                delay(5.seconds)
                it.ack()
                //this.cancel()
            }
            println("done")
        }

        /*delay(30.seconds)

        consumer.stopConsuming()
        memphis.close()*/
    }
}