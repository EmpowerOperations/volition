package com.empowerops.volition.dto

import com.google.protobuf.Message
import com.google.protobuf.duration
import io.grpc.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import java.time.Duration
import java.util.*
import com.google.protobuf.Duration as DurationDTO

fun DurationDTO.toDuration(): Duration = Duration.ofSeconds(seconds, nanos.toLong())
fun Duration.toDTO(): DurationDTO = duration { seconds = getSeconds(); nanos = getNano() }

fun UUIDDTO.toUUIDOrNull(): UUID? = if(value.isNullOrBlank()) null else UUID.fromString(value)
fun UUIDDTO.toUUID(): UUID = UUID.fromString(value)
fun UUID.toDTO(): UUIDDTO = com.empowerops.volition.dto.uUIDDTO { value = this@toDTO.toString() }

class LoggingInterceptor(val logger: (String) -> Unit): ServerInterceptor {

    override fun <T : Any?, R : Any?> interceptCall(
            call: ServerCall<T, R>,
            headers: Metadata,
            next: ServerCallHandler<T, R>
    ): ServerCall.Listener<T> {

        val type = call.methodDescriptor.type
        val fullMethodName = call.methodDescriptor.fullMethodName

        val outboundInterceptor = object: ServerCall<T, R>(){

            private val direction: String get() = when(type){
                MethodDescriptor.MethodType.UNARY -> "OUTBOUND"
                MethodDescriptor.MethodType.CLIENT_STREAMING -> TODO("outbound interceptor got an input message?")
                MethodDescriptor.MethodType.SERVER_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.BIDI_STREAMING -> "OUTBOUND-ITEM"
                null, MethodDescriptor.MethodType.UNKNOWN -> "???"
            }
            private val verb: String get() = when(type) {
                MethodDescriptor.MethodType.UNARY -> "returned"
                MethodDescriptor.MethodType.CLIENT_STREAMING -> TODO("outbound interceptor got an input message?")
                MethodDescriptor.MethodType.SERVER_STREAMING -> "yielded"
                MethodDescriptor.MethodType.BIDI_STREAMING -> "yielded"
                null, MethodDescriptor.MethodType.UNKNOWN -> "???"
            }

            override fun request(numMessages: Int) { call.request(numMessages) }
            override fun sendHeaders(headers: Metadata?) { call.sendHeaders(headers) }
            override fun isReady(): Boolean = call.isReady
            override fun close(status: Status, trailers: Metadata?) {
                if( ! status.isOk){
                    val message = buildString {
                        append("API BAD CLOSE > $fullMethodName closed (${status.code}) '${status.description}'")
                        status.cause?.let { appendLine(); append(it.stackTraceToString())}
                    }
                    logger(message)
                }

                call.close(status, trailers)
            }
            override fun isCancelled(): Boolean = call.isCancelled
            override fun setMessageCompression(enabled: Boolean) { call.setMessageCompression(enabled) }
            override fun setCompression(compressor: String?) { call.setCompression(compressor) }
            override fun getAttributes(): Attributes = call.attributes
            override fun getAuthority(): String? = call.authority
            override fun getMethodDescriptor(): MethodDescriptor<T, R> = call.methodDescriptor

            override fun sendMessage(message: R) {
                val loggingMessage = formatMessage("API $direction >", fullMethodName, verb, message)
                logger(loggingMessage)
                call.sendMessage(message)
            }
        }

        return object: ServerCall.Listener<T>() {
            val actual = next.startCall(outboundInterceptor, headers)

            override fun onHalfClose() {
                actual.onHalfClose()
            }

            override fun onReady() {
                actual.onReady()
            }

            override fun onCancel() {
                actual.onCancel()
            }

            override fun onComplete() {
                if( ! type.serverSendsOneMessage()){
                    logger("API CLOSED > $fullMethodName".trim())
                }
                actual.onComplete()
            }
            override fun onMessage(message: T) {

                val loggingMessage = formatMessage("API INBOUND >", fullMethodName, "received", message)

                logger(loggingMessage)
                actual.onMessage(message)
            }
        }
    }

    private fun <T : Any?> formatMessage(
        direction: String,
        fullMethodName: String?,
        verb: String,
        message: T
    ): String {

        val messageType = (message ?: Any())::class.simpleName

        val loggingMessage = buildString {
            append("$direction $fullMethodName $verb")
            append(" ")

            val messageStringRaw = message.toString()
            if (messageStringRaw.isNullOrBlank()) {
                append("[empty $messageType]")
                appendLine()
            } else {
                append(messageType).append(" {").appendLine()
                for (messageLine in messageStringRaw.trim().lines()) {
                    append("  ").append(messageLine).appendLine()
                }
                append("}").appendLine()
            }
        }
        return loggingMessage
    }
}
