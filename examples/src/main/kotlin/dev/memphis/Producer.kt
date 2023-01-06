package dev.memphis.sdk

import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val memphis = Memphis.connect("localhost", "adrian", "memphis")

        val producer = memphis.producer("my-station", "producer_name_2")

        repeat(10) {
            producer.produce("Message $it".toByteArray()) {
                headers.put("key", "value")
            }
        }


        producer.destroy()
        memphis.close()
    }
}
