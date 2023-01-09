package dev.memphis

import dev.memphis.sdk.Memphis
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val memphis = Memphis.connect("localhost", "root", "memphis")

        val producer = memphis.producer("test", "producer_name_2")

        repeat(10) {
            producer.produce("""query myQuery {greeting} mutation msg { updateUserEmail( email:"http://github.com/" id:1){id name}}""".toByteArray()) {
                headers.put("key", "value")
            }
        }


        producer.destroy()
        memphis.close()
    }
}
