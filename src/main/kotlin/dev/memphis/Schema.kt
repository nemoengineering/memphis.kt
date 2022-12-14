package dev.memphis

internal abstract class Schema(
    val name: String
) {
    abstract fun validateMessage(msg: ByteArray): ByteArray
}
