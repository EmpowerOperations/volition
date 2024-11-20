package com.empowerops.volition.dto

import com.google.protobuf.duration
import java.time.Duration
import java.util.UUID
import com.google.protobuf.Duration as DurationDTO

/**
 * The suggested maximum message size to set for GRPC.
 *
 * At time of writing, the largest message seen is from [UnaryOptimizerGrpc.UnaryOptimizerStub.requestRunResult],
 * which can result in very large messages for larger optimizations
 * (eg: 2000 pts of a 100 var multi-objective problem can be ~10Mb large.)
 */
const val RecommendedMaxMessageSize = 100 * 1024 * 1024 // 100MB

/**
 * The suggested maximum header (metadata) size for GRPC.
 *
 * At time of writing, [com.empowerops.volition.BetterExceptionsInterceptor] will include
 * server-side exception traces as a [com.google.rpc.DebugInfo] in metadata;
 * these traces can be fairly large (especially for things like a stackoverflow error).
 */
const val RecommendedMaxHeaderSize = 10 * 1024 * 1024 // 10MB

fun DurationDTO.toDuration(): Duration = Duration.ofSeconds(seconds, nanos.toLong())
fun Duration.toRunIDDTO(): DurationDTO = duration { seconds = getSeconds(); nanos = getNano() }

fun RunIDDTO.toUUIDOrNull(): UUID? = if(value.isNullOrBlank()) null else UUID.fromString(value)
fun RunIDDTO.toUUID(): UUID = UUID.fromString(value)
fun UUID.toRunIDDTO(): RunIDDTO = runIDDTO { value = this@toRunIDDTO.toString() }

fun NotificationIDDTO.toUUIDOrNull(): UUID? = if(value.isNullOrBlank()) null else UUID.fromString(value)
fun NotificationIDDTO.toUUID(): UUID = UUID.fromString(value)
fun UUID.toNotificationIDDTO(): NotificationIDDTO = notificationIDDTO { value = this@toNotificationIDDTO.toString() }