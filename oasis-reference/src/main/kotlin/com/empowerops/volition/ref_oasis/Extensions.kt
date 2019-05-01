package com.empowerops.volition.ref_oasis

import java.util.*

fun String.toUUIDOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (e: IllegalArgumentException) {
    null
}