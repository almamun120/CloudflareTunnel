package com.cloudflare.tunnel

import fi.iki.elonen.NanoHTTPD
import android.util.Log

class TestHttpServer(private val port: Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val msg = """
            <html>
            <head>
                <title>Cloudflare Tunnel Success</title>
                <style>
                    body { font-family: sans-serif; background-color: #f4f4f9; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                    .card { background: white; padding: 2rem; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); text-align: center; }
                    h1 { color: #f38020; }
                    p { color: #555; }
                    .status { font-weight: bold; color: green; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>Cloudflare Tunnel Works!</h1>
                    <p>Your Android device is now successfully serving traffic through a secure Cloudflare Tunnel.</p>
                    <p class="status">Status: Connected & Reachable</p>
                    <hr>
                    <p><small>Requested Path: ${session.uri}</small></p>
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(msg)
    }

    override fun start() {
        try {
            super.start(SOCKET_READ_TIMEOUT, false)
            Log.i("TestHttpServer", "Server started on port $port")
        } catch (e: Exception) {
            Log.e("TestHttpServer", "Could not start server", e)
        }
    }
}
