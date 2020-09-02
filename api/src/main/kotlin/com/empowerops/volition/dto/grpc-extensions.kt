package com.empowerops.volition.dto

import com.google.protobuf.Message
import io.grpc.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*

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

        val outboundInterceptor = object: ForwardingServerCall.SimpleForwardingServerCall<T, R>(call){

            private val direction: String = when(type){
                MethodDescriptor.MethodType.UNARY -> "OUTBOUND"
                MethodDescriptor.MethodType.CLIENT_STREAMING -> "OUTBOUND"
                MethodDescriptor.MethodType.SERVER_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.BIDI_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.UNKNOWN -> "OUTBOUND-ITEM"
                null -> TODO()
            }

            override fun sendMessage(message: R) {
                val messageType = (message ?: Any())::class.simpleName
                val messageString = message.toString().let { if(it.isBlank()) "[empty $messageType]" else "\n$it"}

                logger("API $direction > $fullMethodName $messageString".trim())
                super.sendMessage(message)
            }
        }

        val actual = next.startCall(outboundInterceptor, headers)

        val inboundInterceptor = object: ForwardingServerCallListener.SimpleForwardingServerCallListener<T>(actual) {
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

        return inboundInterceptor
    }
}
