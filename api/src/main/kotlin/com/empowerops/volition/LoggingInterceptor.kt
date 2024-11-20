package com.empowerops.volition

import com.google.protobuf.Any
import com.google.protobuf.Message
import com.google.protobuf.TextFormat
import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

class LoggingInterceptor(val logger: (String) -> Unit): ServerInterceptor {

    companion object {
        val IncludeFullStackTracePropertyName = "${LoggingInterceptor::class.qualifiedName}.${(LoggingInterceptor::IncludeFullStackTrace).name}"
    }
    val IncludeFullStackTrace: Boolean = System.getProperty(IncludeFullStackTracePropertyName)?.toBoolean() ?: false

    override fun <Q, R> interceptCall(
        call: ServerCall<Q, R>,
        headers: Metadata,
        next: ServerCallHandler<Q, R>
    ): ServerCall.Listener<Q> {
        val outboundInterceptor = LoggingOutboundServerCall(call)
        val callActual = next.startCall(outboundInterceptor, headers)
        return LoggingServerCallListener(callActual, call.methodDescriptor.type, call.methodDescriptor.fullMethodName)
    }

    inner class LoggingServerCallListener<Q>(
        private val listenerDelegate: ServerCall.Listener<Q>,
        private val callType: MethodDescriptor.MethodType,
        private val fullMethodName: String?
    ) : ForwardingServerCallListener<Q>() {
        override fun delegate(): ServerCall.Listener<Q> = listenerDelegate

        override fun onComplete() {
            if( ! callType.serverSendsOneMessage()){
                logger("API CLOSED > $fullMethodName".trim())
            }
            listenerDelegate.onComplete()
        }

        // receive query
        override fun onMessage(message: Q) {

            val loggingMessage = formatMessage("API INBOUND >", fullMethodName, "received", message)

            logger(loggingMessage)
            listenerDelegate.onMessage(message)
        }
    }

    inner class LoggingOutboundServerCall<Q, R>(
        private val serverCallDelegate: ServerCall<Q, R>
    ) : ForwardingServerCall<Q, R>() {
        override fun delegate(): ServerCall<Q, R> = serverCallDelegate

        val callType = serverCallDelegate.methodDescriptor.type
        val fullMethodName = serverCallDelegate.methodDescriptor.fullMethodName

        val direction: String = when(callType){
            MethodDescriptor.MethodType.UNARY -> "OUTBOUND"
            MethodDescriptor.MethodType.CLIENT_STREAMING -> "OUTBOUND"
            MethodDescriptor.MethodType.SERVER_STREAMING -> "OUTBOUND-ITEM"
            MethodDescriptor.MethodType.BIDI_STREAMING -> "OUTBOUND-ITEM"
            null, MethodDescriptor.MethodType.UNKNOWN -> "???"
        }
        val verb: String = when(callType) {
            MethodDescriptor.MethodType.UNARY -> "returned"
            MethodDescriptor.MethodType.CLIENT_STREAMING -> "returned"
            MethodDescriptor.MethodType.SERVER_STREAMING -> "emitted"
            MethodDescriptor.MethodType.BIDI_STREAMING -> "emitted"
            null, MethodDescriptor.MethodType.UNKNOWN -> "???"
        }

        override fun close(status: Status, trailers: Metadata) {
            if( ! status.isOk){
                val message = buildString {
                    append("API close ${status.code.name}_ERROR (code ${status.code.value()}) > $fullMethodName '${status.description}'")
                    // given that we're sending exceptions client side, it doesnt make sense to include the stack trace here.
                    // but I still think its worth mentioning the problem
                    val cause = status.cause
                    if(cause != null) {
                        appendLine()

                        if (IncludeFullStackTrace)
                            append(cause.stackTraceToString()) else
                            generateSequence(status.cause) { ex -> ex.cause.takeIf { it != ex } }
                                .joinTo(this, separator = "/") { ex -> ex.toString() }
                    }
                }
                logger(message)
            }

            serverCallDelegate.close(status, trailers)
        }

        // send response
        override fun sendMessage(message: R) {
            val loggingMessage = formatMessage("API $direction >", fullMethodName, verb, message)
            logger(loggingMessage)
            serverCallDelegate.sendMessage(message)
        }
    }

    private fun <T> formatMessage(
        direction: String,
        fullMethodName: String?,
        verb: String,
        message: T
    ): String {

        val messageType = (message ?: Any.getDefaultInstance())::class.simpleName

        val loggingMessage = buildString {
            append(direction).append(' ')
                .append(fullMethodName).append(' ')
                .append(verb).append(' ')

            val messageStringBuilder = StringBuilder()
            when {
                message is Message -> TextFormat.printer().print(message, messageStringBuilder)
                else -> messageStringBuilder.append(message)
            }

            if (messageStringBuilder.isBlank()) {
                append("$messageType {}")
            }
            else {
                append(messageType).append(" {").appendLine()
                for (messageLine in messageStringBuilder.trim().lines()) {
                    append("  ").append(messageLine).appendLine()
                }
                append("}")
            }
        }
        return loggingMessage
    }
}