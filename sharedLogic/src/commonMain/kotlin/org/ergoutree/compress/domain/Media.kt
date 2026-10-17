package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class LocalPath(val value: String) {
    init { require(value.isNotBlank() && '\u0000' !in value) }
}

@Serializable
data class Rational(val numerator: Long, val denominator: Long) {
    init { require(denominator > 0) }
    companion object {
        fun parse(value: String?): Rational? {
            val parts = value?.split('/') ?: return null
            if (parts.size != 2) return null
            val n = parts[0].toLongOrNull() ?: return null
            val d = parts[1].toLongOrNull() ?: return null
            return if (n > 0 && d > 0) Rational(n, d) else null
        }
    }
}

@Serializable
data class FileIdentity(val path: LocalPath, val sizeBytes: Long, val modifiedMillis: Long, val fileKey: String)

@Serializable
data class ContentEvidence(val identity: FileIdentity, val sha256: String)

@Serializable
enum class StreamKind { VIDEO, AUDIO, SUBTITLE, ATTACHMENT, DATA, UNKNOWN }

@Serializable
data class MediaStream(
    val index: Int,
    val kind: StreamKind,
    val codec: String,
    val attachedPicture: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val pixelFormat: String? = null,
    val frameRate: Rational? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    val startUs: Long? = null,
    val durationUs: Long? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val colorSpace: String? = null,
    val colorRange: String? = null,
    val fieldOrder: String? = null,
    val sideDataTypes: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val dispositions: Map<String, Int> = emptyMap(),
)

@Serializable
data class Chapter(val startUs: Long, val endUs: Long, val tags: Map<String, String> = emptyMap())

@Serializable
data class MediaInfo(
    val schemaVersion: Int = 1,
    val format: String,
    val durationUs: Long?,
    val startUs: Long? = null,
    val streams: List<MediaStream>,
    val chapters: List<Chapter> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
enum class ErrorCode {
    TOOLCHAIN_INVALID, UNSUPPORTED_MEDIA, INPUT_CHANGED, INPUT_MISSING, DESTINATION_CONFLICT,
    INSUFFICIENT_SPACE, ENCODE_FAILED, VALIDATION_FAILED, STORE_UNAVAILABLE, USER_INTERRUPTED, PROCESS_FAILED,
}

class CompressException(val code: ErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)
