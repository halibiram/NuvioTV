package com.nuvio.tv.core.player

import com.nuvio.tv.core.network.buildWithAppDns
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 * Algorithm: hash = fileSize + sum of first 64KB longs + sum of last 64KB longs (little-endian)
 * https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 */
object OpenSubtitlesHasher {

    private const val CHUNK_SIZE = 65536L // 64 KB
    private const val LONG_SIZE = 8
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .buildWithAppDns()
    }

    data class Result(val hash: String, val fileSize: Long)

    suspend fun compute(url: String, headers: Map<String, String>): Result? =
        withContext(Dispatchers.IO) {
            try {
                val fileSize = getContentLength(url, headers) ?: return@withContext null
                if (fileSize < CHUNK_SIZE * 2) return@withContext null

                var hash = fileSize
                hash += readChunkSum(url, headers, offset = 0, length = CHUNK_SIZE)
                hash += readChunkSum(url, headers, offset = fileSize - CHUNK_SIZE, length = CHUNK_SIZE)

                Result(
                    hash = "%016x".format(hash),
                    fileSize = fileSize
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun getContentLength(url: String, headers: Map<String, String>): Long? {
        val request = buildRequest(url = url, headers = headers, method = "HEAD")
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.header("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: response.body?.contentLength()?.takeIf { it > 0 }
        }
    }

    private fun readChunkSum(url: String, headers: Map<String, String>, offset: Long, length: Long): Long {
        val request = buildRequest(
            url = url,
            headers = headers,
            method = "GET",
            range = "bytes=$offset-${offset + length - 1}"
        )
        var sum = 0L
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("Unexpected response code ${response.code} for $url")
            }
            val stream: InputStream = response.body?.byteStream() ?: return 0L
            stream.use {
                val buf = ByteArray(LONG_SIZE)
                var remaining = length
                while (remaining >= LONG_SIZE) {
                    var read = 0
                    while (read < LONG_SIZE) {
                        val n = it.read(buf, read, LONG_SIZE - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < LONG_SIZE) break
                    sum += buf.toLongLE()
                    remaining -= LONG_SIZE
                }
            }
        }
        return sum
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        method: String,
        range: String? = null
    ): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (k.equals("Range", ignoreCase = true)) return@forEach
            builder.header(k, v)
        }
        range?.let { builder.header("Range", it) }
        return when (method) {
            "HEAD" -> builder.head().build()
            else -> builder.get().build()
        }
    }

    private fun ByteArray.toLongLE(): Long {
        var v = 0L
        for (i in 0 until LONG_SIZE) v = v or ((this[i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
