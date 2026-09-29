package com.media.app

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// ============================================================================
//  AURA SHARE - the file half
//
//  The renderer does not receive anything. It is handed a URL and comes back
//  to fetch the file itself, so this phone has to be an HTTP server for as
//  long as the TV is playing.
//
//  RANGE REQUESTS ARE THE WHOLE JOB. A server that only answers 200 will play
//  a file from the beginning and nothing else: seeking, resuming, and a TV's
//  own habit of probing the tail for a duration all arrive as Range headers,
//  and a renderer that asks for bytes 40000000- and gets byte 0 either stalls
//  or plays the wrong thing. HEAD comes first from most TVs, and answering it
//  wrongly means the file is rejected before a single byte moves.
//
//  One file at a time, by design. This is not a media server; it is a pipe
//  for the thing you are watching, and it closes when you stop.
// ============================================================================

class ShareServer(private val context: Context) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile private var uri: Uri? = null
    @Volatile private var mime: String = "application/octet-stream"
    @Volatile private var token: String = ""

    val port: Int get() = server?.localPort ?: -1
    val running: Boolean get() = server != null

    /** Starts serving [item] and returns the URL to hand the renderer. */
    @Synchronized
    fun serve(item: AppMediaItem, host: String): String? {
        if (server == null) {
            val s = try {
                ServerSocket(0)
            } catch (t: Throwable) {
                return null
            }
            server = s
            pool.execute { accept(s) }
        }
        uri = item.uri
        mime = item.mimeType.ifBlank {
            if (item.type == MediaType.VIDEO) "video/mp4" else "audio/mpeg"
        }
        // The path changes per item. A renderer that cached the last one
        // cannot accidentally be served the next one.
        token = java.lang.Long.toHexString(System.nanoTime())
        val ext = mime.substringAfter('/').substringBefore(';')
        return "http://$host:${server?.localPort}/$token.$ext"
    }

    @Synchronized
    fun stop() {
        uri = null
        runCatching { server?.close() }
        server = null
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (t: Throwable) {
                return
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 15_000
                val input = sock.getInputStream()
                val head = StringBuilder()
                // Read the request head a byte at a time. The body, if any, is
                // none of our business and must not be swallowed.
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return
                    head.append(b.toChar())
                    if (head.length > 8192) return
                }
                val text = head.toString()
                val request = text.lineSequence().first().split(' ')
                val method = request.getOrNull(0).orEmpty().uppercase()
                val path = request.getOrNull(1).orEmpty()
                val out = sock.getOutputStream()

                val target = uri
                if (target == null || !path.contains(token) || token.isEmpty()) {
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                        .toByteArray()); out.flush(); return
                }

                val pfd = context.contentResolver.openFileDescriptor(target, "r")
                if (pfd == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                        .toByteArray()); out.flush(); return
                }
                pfd.use { descriptor ->
                    val total = descriptor.statSize
                    val range = text.lineSequence()
                        .firstOrNull { it.startsWith("Range:", true) }
                        ?.substringAfter('=')?.trim()
                    var start = 0L
                    var end = total - 1
                    if (range != null && total > 0) {
                        val from = range.substringBefore('-').trim().toLongOrNull()
                        val to = range.substringAfter('-').trim().toLongOrNull()
                        if (from != null) {
                            start = from
                            if (to != null) end = minOf(to, total - 1)
                        } else if (to != null) {
                            // "-500" means the LAST 500 bytes, not the first.
                            start = (total - to).coerceAtLeast(0)
                        }
                    }
                    if (total > 0 && (start >= total || start > end)) {
                        out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */$total\r\nConnection: close\r\n\r\n")
                            .toByteArray()); out.flush(); return@use
                    }
                    val length = if (total > 0) end - start + 1 else -1L
                    val status = if (range != null && total > 0) "206 Partial Content" else "200 OK"
                    val header = StringBuilder()
                        .append("HTTP/1.1 ").append(status).append("\r\n")
                        .append("Content-Type: ").append(mime).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Connection: close\r\n")
                    if (length >= 0) header.append("Content-Length: ").append(length).append("\r\n")
                    if (range != null && total > 0)
                        header.append("Content-Range: bytes ").append(start).append('-')
                            .append(end).append('/').append(total).append("\r\n")
                    header.append("\r\n")
                    out.write(header.toString().toByteArray())
                    out.flush()
                    if (method == "HEAD") return@use

                    FileInputStream(descriptor.fileDescriptor).use { file ->
                        file.channel.position(start)
                        copy(file, out, length)
                    }
                }
            } catch (t: Throwable) {
                // A renderer that seeks hangs up mid-body every single time.
                // That is not an error, it is how seeking looks from here.
            }
        }
    }

    private fun copy(from: FileInputStream, to: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left != 0L) {
            val want = if (left < 0) buf.size else minOf(left, buf.size.toLong()).toInt()
            val n = from.read(buf, 0, want)
            if (n <= 0) return
            to.write(buf, 0, n)
            if (left > 0) left -= n
        }
        to.flush()
    }
}
