package com.moblized.jellydash.jellyfin

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** A small real HTTP server on this machine, standing in for Jellyfin in tests. */
class FakeHttpServer(private val handle: (Request) -> Pair<Int, String>) : Closeable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val address: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun line(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) bytes += b.toByte()
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun serve(client: Socket) = client.use {
        val input = BufferedInputStream(client.getInputStream())
        val first = line(input).split(' ')
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(input)
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = input.readNBytes(length).decodeToString()
        val (code, answer) = handle(Request(first[0], first.getOrElse(1) { "/" }, headers, body))
        val bytes = answer.toByteArray()
        val head = "HTTP/1.1 $code Status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() = socket.close()
}
