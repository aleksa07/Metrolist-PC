package com.metrolist.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback HTTP server that exposes a googlevideo stream to FFmpeg as a plain seekable resource.
 * Upstream requests carry the extractor's headers and use bounded byte ranges, which some
 * clients require and which avoids server-side throttling of long open-ended reads.
 */
object RangeProxy {
    private const val CHUNK = 1L shl 20 // 1 MiB keeps time-to-first-audio and seek latency low.

    class Entry(val stream: ResolvedStream, @Volatile var length: Long?, @Volatile var lastStatus: Int = 200)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val http: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "range-proxy").apply { isDaemon = true } }
            createContext("/s/") { ex ->
                Timber.tag("RangeProxy").d("${ex.requestMethod} range=${ex.requestHeaders.getFirst("Range")}")
                runCatching { handle(ex) }.onFailure { Timber.tag("RangeProxy").e(it, "handler failed"); ex.close() }
            }
            start()
        }
    }

    fun register(stream: ResolvedStream): Pair<String, Entry> {
        val id = UUID.randomUUID().toString()
        val entry = Entry(stream, stream.contentLength?.takeIf { it > 0 })
        entries[id] = entry
        if (entries.size > 8) entries.keys.take(entries.size - 8).forEach(entries::remove)
        return "http://127.0.0.1:${server.address.port}/s/$id" to entry
    }

    fun unregister(url: String) {
        entries.remove(url.substringAfterLast('/'))
    }

    private fun upstream(entry: Entry, start: Long, endInclusive: Long): HttpResponse<java.io.InputStream> {
        val builder =
            HttpRequest.newBuilder(URI(entry.stream.url))
                .timeout(Duration.ofSeconds(30))
                .header("Range", "bytes=$start-$endInclusive")
        entry.stream.headers.forEach { (k, v) ->
            if (!k.equals("range", true) && !k.equals("host", true) && !k.equals("connection", true)) {
                runCatching { builder.header(k, v) }
            }
        }
        if (entry.stream.headers.keys.none { it.equals("user-agent", true) }) {
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun totalLength(entry: Entry): Long? {
        entry.length?.let { return it }
        val r = upstream(entry, 0, 0)
        r.body().close()
        entry.lastStatus = r.statusCode()
        val total =
            r.headers().firstValue("content-range").orElse(null)?.substringAfterLast('/')?.toLongOrNull()
        entry.length = total
        return total
    }

    private fun handle(ex: HttpExchange) {
        val entry = entries[ex.requestURI.path.substringAfterLast('/')]
        if (entry == null) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        val total = totalLength(entry)
        if (total == null) {
            ex.sendResponseHeaders(if (entry.lastStatus in 400..599) entry.lastStatus else 502, -1)
            ex.close()
            return
        }
        val rangeHeader = ex.requestHeaders.getFirst("Range")
        var start = 0L
        var end = total - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            spec.substringBefore('-').toLongOrNull()?.let { start = it }
            spec.substringAfter('-', "").toLongOrNull()?.let { end = minOf(it, total - 1) }
        }
        if (start >= total) {
            ex.responseHeaders.add("Content-Range", "bytes */$total")
            ex.sendResponseHeaders(416, -1)
            ex.close()
            return
        }
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("Connection", "close")
        ex.responseHeaders.add("Content-Type", entry.stream.mimeType.substringBefore(';').ifBlank { "application/octet-stream" })
        if (ex.requestMethod.equals("HEAD", true)) {
            ex.responseHeaders.add("Content-Length", total.toString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
            return
        }
        val partial = rangeHeader != null
        if (partial) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$total")

        // Fetch the first chunk before committing headers so upstream rejections reach the player.
        var pos = start
        var resp = upstream(entry, pos, minOf(pos + CHUNK - 1, end))
        entry.lastStatus = resp.statusCode()
        if (resp.statusCode() !in 200..299) {
            resp.body().close()
            Timber.tag("RangeProxy").w("upstream HTTP ${resp.statusCode()}")
            ex.sendResponseHeaders(resp.statusCode(), -1)
            ex.close()
            return
        }
        ex.sendResponseHeaders(if (partial) 206 else 200, end - start + 1)
        val out = ex.responseBody
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                resp.body().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                }
                if (pos > end) break
                resp = upstream(entry, pos, minOf(pos + CHUNK - 1, end))
                entry.lastStatus = resp.statusCode()
                if (resp.statusCode() !in 200..299) {
                    resp.body().close()
                    throw IOException("upstream HTTP ${resp.statusCode()}")
                }
            }
        } catch (e: IOException) {
            Timber.tag("RangeProxy").d("stream closed at $pos: $e")
            // Client closed the connection (seek / stop) or upstream failed mid-stream.
        } finally {
            runCatching { out.close() }
            ex.close()
        }
    }
}
