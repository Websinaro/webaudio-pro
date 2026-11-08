package com.weaudio.pro.samples

import android.content.Context
import android.net.Uri
import com.weaudio.pro.audio.sampler.DrumInstrument
import java.io.IOException

data class KitInfo(val id: String, val name: String)

/** Where kit sample bytes come from. Faked in unit tests. */
interface KitSource {
    fun kits(): List<KitInfo>
    /** Bytes of the instrument's sample in the kit, or null if the kit lacks that file. */
    fun read(kitId: String, instrument: DrumInstrument): ByteArray?
}

/** Kits bundled in assets/kits/<id>/<instrument file>. */
class AssetKitSource(context: Context) : KitSource {
    private val assets = context.applicationContext.assets
    override fun kits(): List<KitInfo> =
        (assets.list("kits") ?: emptyArray()).filter { (assets.list("kits/$it") ?: emptyArray()).isNotEmpty() }
            .sorted().map { KitInfo(it, it.split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }) }
    override fun read(kitId: String, instrument: DrumInstrument): ByteArray? = try {
        assets.open("kits/$kitId/${instrument.fileName}").use { it.readBytes() }
    } catch (e: IOException) { null }
}

/** Reads a user-picked document (Storage Access Framework: no storage permission needed). */
object UserSampleReader {
    const val MAX_BYTES = 16 * 1024 * 1024
    class Result(val name: String, val bytes: ByteArray?, val error: String?)

    fun read(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null } ?: uri.lastPathSegment ?: "sample.wav"
        return try {
            val stream = resolver.openInputStream(uri) ?: return Result(name, null, "Could not open the file")
            stream.use {
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = it.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) return Result(name, null, "File is larger than 16 MB")
                }
                Result(name, buf.toByteArray(), null)
            }
        } catch (e: Exception) { Result(name, null, "Could not read the file: ${e.message}") }
    }
}
