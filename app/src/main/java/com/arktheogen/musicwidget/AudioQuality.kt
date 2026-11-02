package com.arktheogen.musicwidget

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Bundle
import java.util.Locale

/**
 * The audio chain for the info view, from facts the phone actually reports:
 *  - the stream: Apple Music publishes its playback format in its session (codec, bit depth,
 *    sample rate, bit rate, channels; relay badge test: ALAC 24-bit / 44.1 kHz, 1,759 kbps);
 *  - the output: where Android sends media, and the rate its mixer runs at (48 kHz on the Pixel).
 * Apps that don't publish a format get only the output part.
 */
object AudioQuality {
    data class Chain(
        val codec: String?,
        val tier: String?,
        val bitDepth: Int?,
        val sampleRate: Int?,
        val bitRate: Int?,
        val channels: Int?,
        val output: String,
        val mixerRate: Int?,
        /** True when Android hands the stream to a USB DAC untouched (bit-perfect mixer mode). */
        val bitPerfect: Boolean,
    ) {
        /** The stream's rate differs from the mixer's: Android resamples it before the output. */
        val resampled: Boolean get() = !bitPerfect && sampleRate != null && mixerRate != null && sampleRate != mixerRate
    }

    fun read(ctx: Context, pkg: String?): Chain {
        val extras = Diagnostics.sessions(ctx)?.firstOrNull { it.packageName == pkg }?.playbackState?.extras
        val codec = extras.text("PLAYBACK_FORMAT_CODECS") ?: extras.text("PLAYBACK_FORMAT_CODEC_MIME_TYPE")
        val bitDepth = extras.number("PLAYBACK_FORMAT_BIT_DEPTH")
        val sampleRate = extras.number("PLAYBACK_FORMAT_SAMPLE_RATE")
        val channels = extras.number("PLAYBACK_FORMAT_CHANNEL_COUNT")
        val am = ctx.getSystemService(AudioManager::class.java)
        val device = mediaDevice(am)
        return Chain(
            codec = codec?.let { codecName(it) },
            tier = codec?.let { tier(it, bitDepth, sampleRate, channels) },
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            bitRate = extras.number("PLAYBACK_FORMAT_BIT_RATE"),
            channels = channels,
            output = device?.let { describe(it) } ?: "Unknown",
            // The reported rate is the main (speaker) output's. Bluetooth and USB run their own
            // outputs whose rate an app can't read, so we claim nothing for them.
            mixerRate = if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() else null,
            bitPerfect = device != null && isBitPerfect(am, device),
        )
    }

    /** Apple's tiers: Lossless is ALAC up to 24-bit/48 kHz; Hi-Res Lossless is above 48 kHz. */
    private fun tier(codec: String, bitDepth: Int?, rate: Int?, channels: Int?): String {
        val c = codec.lowercase(Locale.ROOT)
        return when {
            c.contains("ec-3") || c.contains("eac3") || c.contains("ac-4") || (channels ?: 2) > 2 -> "Dolby Atmos / surround"
            c.contains("alac") || c.contains("flac") ->
                if ((rate ?: 0) > 48_000 && (bitDepth ?: 0) >= 24) "Hi-Res Lossless" else "Lossless"
            else -> "Lossy"
        }
    }

    private fun codecName(codec: String): String = when (val c = codec.lowercase(Locale.ROOT)) {
        "alac" -> "ALAC (Apple Lossless)"
        "flac" -> "FLAC"
        "ec-3", "eac3" -> "Dolby Digital Plus (E-AC-3)"
        "ac-4" -> "Dolby AC-4"
        else -> if (c.startsWith("mp4a") || c.contains("aac")) "AAC ($codec)" else codec
    }

    /** The device Android routes media to right now. */
    private fun mediaDevice(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 33) return null
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val routed = am.getAudioDevicesForAttributes(attrs).firstOrNull() ?: return null
        // The routed entry has only type and address; the full device (name, rates) comes from the list.
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == routed.type && it.address == routed.address }
            ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == routed.type }
    }

    private fun describe(d: AudioDeviceInfo): String {
        val name = d.productName?.toString().orEmpty()
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> return "Phone speaker"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE Audio"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Output type ${d.type}"
        }
        val rates = d.sampleRates.takeIf { it.isNotEmpty() }?.let { r -> " · supports ${r.distinct().sorted().joinToString("/") { khz(it) }} kHz" }.orEmpty()
        return if (name.isBlank()) kind + rates else "$kind: $name$rates"
    }

    /** Android 14+: a USB DAC can be put in bit-perfect mode by the app that plays (not by us). */
    private fun isBitPerfect(am: AudioManager, d: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        return am.getPreferredMixerAttributes(attrs, d)?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
    }

    fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.ROOT, "%.1f", hz / 1000.0)

    /** Apple's keys are long ("com.apple...PLAYBACK_STATE_EXTRA_PLAYBACK_FORMAT_BIT_DEPTH"); match by ending. */
    private fun Bundle?.key(suffix: String) = this?.keySet()?.firstOrNull { it.endsWith(suffix) }

    @Suppress("DEPRECATION")
    private fun Bundle?.text(suffix: String) = key(suffix)?.let { this!!.get(it)?.toString() }?.takeIf { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun Bundle?.number(suffix: String) = key(suffix)?.let { (this!!.get(it) as? Number)?.toInt() }?.takeIf { it > 0 }
}
