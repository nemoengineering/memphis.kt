package dev.memphis

internal class EmptySchema : Schema("empty") {
    override fun validateMessage(msg: ByteArray): ByteArray {
        return msg
    }
}