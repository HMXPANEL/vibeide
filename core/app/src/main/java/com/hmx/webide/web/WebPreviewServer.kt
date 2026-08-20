/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hmx.webide.web

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import org.slf4j.LoggerFactory

/**
 * Minimal static HTTP server for the local web preview. Serves a project
 * directory over `http://127.0.0.1:<port>` so that relative paths, `fetch()`
 * and ES modules work (no `file://` CORS pain). One thread per connection,
 * no dependencies, bound to loopback only.
 */
class WebPreviewServer(private val root: File) {

  private val log = LoggerFactory.getLogger(WebPreviewServer::class.java)

  @Volatile
  private var serverSocket: ServerSocket? = null
  private val connections = mutableListOf<Thread>()
  private val lock = Object()

  val port: Int
    get() = serverSocket?.localPort ?: -1

  fun start(): Int {
    val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    serverSocket = ss
    val acceptor = Thread({
      while (!ss.isClosed) {
        try {
          val client = ss.accept()
          val t = Thread({ serve(client) }, "web-preview-conn")
          t.isDaemon = true
          synchronized(lock) { connections.add(t) }
          t.start()
        } catch (_: java.io.IOException) {
          return@Thread
        }
      }
    }, "web-preview-accept")
    acceptor.isDaemon = true
    acceptor.start()
    return ss.localPort
  }

  fun stop() {
    serverSocket?.close()
    serverSocket = null
  }

  private fun serve(client: Socket) {
    try {
      client.use { socket ->
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = reader.readLine() ?: return
        if (!requestLine.startsWith("GET ")) {
          write(socket, 405, "text/plain", "Method Not Allowed".toByteArray())
          return
        }
        val rawPath = requestLine.substring(4).substringBefore(' ').substringBefore('?')
        val file = resolve(URLDecoder.decode(rawPath, "UTF-8"))
        if (file == null || !file.isFile) {
          write(socket, 404, "text/plain", "Not Found".toByteArray())
          return
        }
        write(socket, 200, contentType(file.name), file.readBytes())
      }
    } catch (e: Throwable) {
      log.debug("Preview request failed", e)
    } finally {
      val t = Thread.currentThread()
      synchronized(lock) { connections.remove(t) }
    }
  }

  private fun resolve(path: String): File? {
    if (path.contains("..")) {
      return null
    }
    val name = if (path == "/" || path.isEmpty()) "index.html" else path.removePrefix("/")
    val file = File(root, name)
    val rootCanonical = root.canonicalPath
    return if (file.canonicalPath == rootCanonical || file.canonicalPath.startsWith("$rootCanonical${File.separator}")) file else null
  }

  private fun write(socket: Socket, status: Int, type: String, body: ByteArray) {
    val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Method Not Allowed"
    val head = "HTTP/1.1 $status $reason\r\n" +
        "Content-Type: $type\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "Connection: close\r\n\r\n"
    val out = socket.getOutputStream()
    out.write(head.toByteArray(Charsets.UTF_8))
    out.write(body)
    out.flush()
  }

  private fun contentType(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
      "html", "htm" -> "text/html; charset=utf-8"
      "css" -> "text/css; charset=utf-8"
      "js", "mjs" -> "text/javascript; charset=utf-8"
      "json" -> "application/json"
      "svg" -> "image/svg+xml"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "gif" -> "image/gif"
      "webp" -> "image/webp"
      "ico" -> "image/x-icon"
      "txt" -> "text/plain; charset=utf-8"
      "md" -> "text/markdown; charset=utf-8"
      "xml" -> "application/xml"
      "woff" -> "font/woff"
      "woff2" -> "font/woff2"
      "ttf" -> "font/ttf"
      "otf" -> "font/otf"
      "wasm" -> "application/wasm"
      else -> "application/octet-stream"
    }
  }
}