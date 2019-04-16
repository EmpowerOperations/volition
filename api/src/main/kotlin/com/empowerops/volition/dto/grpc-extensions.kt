package com.empowerops.volition.dto

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

fun <T> StreamObserver<T>.consume(block: () -> T) {
    try {
        val result = block()
        onNext(result)
    } catch (ex: Exception) {
        onError(ex)
        throw ex
    } finally {
        onCompleted()
    }
}

fun <T> StreamObserver<T>.consumeAsync(block: suspend () -> T) {
     GlobalScope.launch {
        try {
            val result = block()
            onNext(result)
        } catch(ex: Exception){
            onError(ex)
            throw ex
        } finally {
            onCompleted()
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

            private val name: String = when(type){
                MethodDescriptor.MethodType.UNARY -> "OUTBOUND"
                MethodDescriptor.MethodType.CLIENT_STREAMING -> "OUTBOUND"
                MethodDescriptor.MethodType.SERVER_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.BIDI_STREAMING -> "OUTBOUND-ITEM"
                MethodDescriptor.MethodType.UNKNOWN -> "OUTBOUND-ITEM"
                null -> TODO()
            }

            override fun sendMessage(message: R) {
                val messageString = message.toString().let { if(it.isBlank()) "[empty message]" else "\n$it"}

                logger.log("$name: $fullMethodName $messageString", "API")
                super.sendMessage(message)
            }
        }

        val actual = next.startCall(outboundInterceptor, headers)

        val inboundInterceptor = object: ForwardingServerCallListener.SimpleForwardingServerCallListener<T>(actual) {
            override fun onComplete() {
                if( ! type.serverSendsOneMessage()){
                    logger.log("$fullMethodName", "API CLOSED")
                }
                actual.onComplete()
            }
            override fun onMessage(message: T) {
                val messageString = message.toString().let { if(it.isBlank()) "[empty message]" else "\n$it" }
                logger.log("$fullMethodName $messageString", "API INBOUND")
                actual.onMessage(message)
            }
        }

        return inboundInterceptor
    }
}
