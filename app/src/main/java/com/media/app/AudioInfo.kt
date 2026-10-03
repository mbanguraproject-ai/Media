package com.media.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================
//  AUDIO PATH
//
//  What the file is, what the decoder hands on, what the chain does to it and
//  where it ends up - each stage reported from where it can actually be read,
//  and SOURCE kept apart from OUTPUT (blueprint §15). A 24-bit/96 kHz FLAC
//  played through the phone speaker is still a 24-bit/96 kHz FLAC; the screen
//  just says honestly that the mixer resampled it to 48 kHz on the way out.
// ============================================================================

data class SourceFormat(
    val codec: String?,
    val container: String?,
    val lossless: Boolean?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val bitrateKbps: Int?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val mime: String?
) {
    /** "FLAC · 24-bit · 96 kHz", the badge under the title. */
    val badge: String? get() {
        val parts = listOfNotNull(
            codec,
            bitDepth?.takeIf { lossless == true }?.let { "$it-bit" },
            sampleRate?.let { khz(it) },
            bitrateKbps?.takeIf { lossless != true }?.let { "$it kbps" }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Above CD quality: more than 16 bits or faster than 48 kHz, losslessly. */
    val hiRes: Boolean get() = lossless == true && ((bitDepth ?: 0) > 16 || (sampleRate ?: 0) > 48000)
}

data class OutputRoute(
    val name: String,
    // A stable English key ("Phone speaker"): playback logic compares it.
    val kind: String,
    // The same thing in the app's language, for the screen.
    val kindLabel: String,
    val mixerRate: Int?,
    val deviceRates: List<Int>,
    val bluetooth: Boolean,
    val usb: Boolean
)

internal fun khz(hz: Int): String {
    val k = hz / 1000.0
    return if (hz % 1000 == 0) "${hz / 1000} kHz" else "${"%.1f".format(java.util.Locale.ROOT, k)} kHz"
}

object AudioInfo {

    private val cache = android.util.LruCache<String, Pair<SourceFormat, FileTags?>>(32)

    /** Source format from the file's own headers, then the platform extractor for the rest. */
    suspend fun source(context: Context, item: AppMediaItem): Pair<SourceFormat, FileTags?> =
        withContext(Dispatchers.IO) {
            val key = "${item.uri}@${item.dateModified}"
            cache.get(key)?.let { return@withContext it }
            val tags = readTags(context, item.uri)
            var mime: String? = null
            var rate: Int? = null
            var channels: Int? = null
            var bitrate: Int? = null
            var bits: Int? = null
            var duration: Long? = null
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, item.uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!m.startsWith("audio/")) continue
                    mime = m
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                    if (f.containsKey(MediaFormat.KEY_DURATION)) duration = f.getLong(MediaFormat.KEY_DURATION) / 1000
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        bits = when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                            else -> null
                        }
                    }
                    break
                }
            } catch (t: Throwable) {
                // Unreadable by the platform: the tag reader's answer stands.
            } finally {
                runCatching { ex.release() }
            }
            val size = runCatching {
                context.contentResolver.openFileDescriptor(item.uri, "r")?.use { it.statSize }
            }.getOrNull()
            val codec = tags?.codec ?: codecOf(mime)
            val lossless = tags?.lossless ?: mime?.let { it in LOSSLESS_MIMES }
            val durationMs = tags?.durationMs ?: duration ?: item.durationMs.takeIf { it > 0 }
            // Average bitrate from size and length when the extractor has none
            // (VBR MP3, most FLAC).
            val avgKbps = bitrate ?: if (size != null && durationMs != null && durationMs > 0)
                (size * 8 / durationMs).toInt() else null
            val fmt = SourceFormat(
                codec = codec,
                container = tags?.container,
                lossless = lossless,
                sampleRate = tags?.sampleRate ?: rate,
                bitDepth = tags?.bitDepth ?: bits,
                channels = tags?.channels ?: channels,
                bitrateKbps = avgKbps,
                durationMs = durationMs,
                sizeBytes = size,
                mime = mime ?: item.mimeType.ifBlank { null }
            )
            (fmt to tags).also { cache.put(key, it) }
        }

    private val LOSSLESS_MIMES = setOf("audio/flac", "audio/alac", "audio/raw", "audio/x-wav", "audio/wav")

    fun codecOf(mime: String?): String? = when (mime) {
        null -> null
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/vorbis" -> "Vorbis"
        "audio/opus" -> "Opus"
        "audio/raw" -> "PCM"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/amr-wb" -> "AMR-WB"
        "audio/3gpp" -> "AMR"
        else -> mime.removePrefix("audio/").uppercase()
    }

    /** Where media audio is going right now. */
    fun output(context: Context): OutputRoute {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mixer = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val device: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                am.getAudioDevicesForAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                ).firstOrNull()?.let { attr ->
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                        it.type == attr.type && (attr.address.isEmpty() || it.address == attr.address)
                    }
                }
            }.getOrNull()
        } else null
        val chosen = device ?: guess(am)
        val kind = chosen?.let { kindOf(it.type) } ?: "Phone speaker"
        val kindLabel = context.getString(
            chosen?.let { kindLabelRes(it.type) } ?: R.string.route_phone_speaker
        )
        val name = chosen?.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: kindLabel
        return OutputRoute(
            name = name,
            kind = kind,
            kindLabel = kindLabel,
            mixerRate = mixer,
            deviceRates = chosen?.sampleRates?.toList()?.sorted().orEmpty(),
            bluetooth = chosen != null && chosen.type in BT_TYPES,
            usb = chosen != null && chosen.type in USB_TYPES
        )
    }

    private val BT_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26, 27)
    private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)

    /** Before Android 13 there is no API for the active route; the most specific one plugged in wins. */
    private fun guess(am: AudioManager): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        for (t in order) outs.firstOrNull { it.type == t }?.let { return it }
        return outs.firstOrNull()
    }

    private fun kindOf(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call quality)"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        26 -> "Bluetooth LE Audio"          // TYPE_BLE_HEADSET
        27 -> "Bluetooth LE Audio"          // TYPE_BLE_SPEAKER
        else -> "Audio output"
    }

    /** [kindOf], in the app's language. */
    private fun kindLabelRes(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.route_phone_speaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.route_earpiece
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.route_wired_headphones
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.route_wired_headset
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> R.string.route_bluetooth
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.route_bluetooth_sco
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.route_usb_dac
        AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.route_usb_headset
        AudioDeviceInfo.TYPE_HDMI -> R.string.route_hdmi
        26, 27 -> R.string.route_ble
        else -> R.string.route_other
    }
}
