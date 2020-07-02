package com.empowerops.volition.dto

import com.google.protobuf.Message
import io.grpc.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

fun <R, T> wrapToServerSideChannel(call: (T, StreamObserver<R>) -> Unit, outboundMessage: T): ReceiveChannel<R> {

    val result = Channel<R>(UNLIMITED)

    call(outboundMessage, object: StreamObserver<R>{
        override fun onNext(value: R) { result.offer(value) }
        override fun onError(t: Throwable) { result.close(t) }
        override fun onCompleted() { result.close() }
    })

    //TODO: what about cancellation?
    return result
}

suspend fun <T, R> wrapToSuspend(call: (T, StreamObserver<R>) -> Unit, outboundMessage: T): R {
    return suspendCoroutine { continuation ->
        val resultHandler = object : StreamObserver<R> {
            override fun onNext(value: R) = continuation.resume(value)
            override fun onError(t: Throwable) = continuation.resumeWithException(t)
            override fun onCompleted() {  }
        }

        call(outboundMessage, resultHandler)
        COROUTINE_SUSPENDED
    }
}

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

interface Logger{
    fun log(message:String, sender: String)
}

class LoggingInterceptor(val logger: Logger): ServerInterceptor {

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

                logger.log("$fullMethodName $messageString".trim(), "API $direction")
                super.sendMessage(message)
            }
        }

        val actual = next.startCall(outboundInterceptor, headers)

        val inboundInterceptor = object: ForwardingServerCallListener.SimpleForwardingServerCallListener<T>(actual) {
            override fun onComplete() {
                if( ! type.serverSendsOneMessage()){
                    logger.log("$fullMethodName".trim(), "API CLOSED")
                }
                actual.onComplete()
            }
            override fun onMessage(message: T) {
                val messageType = (message ?: Any())::class.simpleName
                val messageString = message.toString().let { if(it.isBlank()) "[empty $messageType]" else "\n$it" }
                logger.log("$fullMethodName $messageString".trim(), "API INBOUND")
                actual.onMessage(message)
            }
        }

        return inboundInterceptor
    }
}
