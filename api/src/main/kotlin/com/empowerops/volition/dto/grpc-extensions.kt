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

fun <T> CoroutineScope.consumeSingleAsync(streamObserver: StreamObserver<T>, message: Message, block: suspend () -> T) {
    val sourceEx = Exception("server error while processing request=${message.toString().trim()}")
    launch {
        try {
            val result = block()
            streamObserver.onNext(result)
            streamObserver.onCompleted()
        }
        catch(ex: Throwable){
            sourceEx.initCause(ex)
            streamObserver.onError(sourceEx)
            throw sourceEx
        }
    }
}

class LoggingInterceptor(val logger: (String) -> Unit): ServerInterceptor {

    override fun <T : Any?, R : Any?> interceptCall(
            call: ServerCall<T, R>,
            headers: Metadata,
            next: ServerCallHandler<T, R>
    ): ServerCall.Listener<T> {

        val type = call.methodDescriptor.type
        val fullMethodName = call.methodDescriptor.fullMethodName

        val outboundInterceptor = object: ServerCall<T, R>(){

            private val direction: String = when(type){
                MethodDescriptor.MethodType.UNARY -> "OUTBOUND"
                MethodDescriptor.MethodType.CLIENT_STREAMING -> "OUTBOUND"
                MethodDescriptor.MethodType.SERVER_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.BIDI_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.UNKNOWN -> "OUTBOUND-?"
                null -> TODO()
            }

            override fun request(numMessages: Int) { call.request(numMessages) }
            override fun sendHeaders(headers: Metadata?) { call.sendHeaders(headers) }
            override fun isReady(): Boolean = call.isReady
            override fun close(status: Status, trailers: Metadata?) {
                if( ! status.isOk){
                    logger("SERVER SIDE ERROR > " + status.asException().stackTraceToString())
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
                val messageType = (message ?: Any())::class.simpleName
                val messageString = message.toString().let { if(it.isBlank()) "[empty $messageType]" else "\n$it"}

                logger("API $direction > $fullMethodName $messageString".trim())
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
                val messageType = (message ?: Any())::class.simpleName
                val messageString = message.toString().let { if(it.isBlank()) "[empty $messageType]" else "\n$it" }
                logger("API INBOUND > $fullMethodName $messageString".trim())

                actual.onMessage(message)
            }
        }
    }
}
