package com.securecam.core.network

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class LocalApiServer(port: Int, val token: String, val context: Context) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.parameters["token"]?.firstOrNull() != token) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        return when (session.uri) {
            "/api/logs" -> { 
                try {
                    val activeDb = context.getDatabasePath("securecam_db")
                    if (!activeDb.exists()) return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")

                    val db = SQLiteDatabase.openDatabase(activeDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    if (session.method == Method.DELETE) {
                        val id = session.parameters["id"]?.firstOrNull()
                        if (id != null) db.execSQL("DELETE FROM security_logs WHERE id = $id")
                        db.close()
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Deleted")
                    } else {
                        val logs = mutableListOf<Map<String, Any?>>()
                        val dc = db.rawQuery("SELECT * FROM security_logs ORDER BY logTime DESC", null)
                        val cols = dc.columnNames
                        while (dc.moveToNext()) {
                            val m = mutableMapOf<String, Any?>()
                            for (i in cols.indices) {
                                when (dc.getType(i)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> m[cols[i]] = dc.getLong(i)
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> m[cols[i]] = dc.getFloat(i)
                                    android.database.Cursor.FIELD_TYPE_STRING -> m[cols[i]] = dc.getString(i)
                                    else -> m[cols[i]] = null
                                }
                            }
                            logs.add(m)
                        }
                        dc.close()
                        db.close()
                        newFixedLengthResponse(Response.Status.OK, "application/json", Gson().toJson(logs))
                    }
                } catch(e: Exception) {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
                }
            }
            "/api/video" -> { 
                val f = File(context.filesDir, session.parameters["file"]?.firstOrNull() ?: "")
                if (!f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
                
                // CRITICAL FIX: HTTP 206 Partial Content format required by ExoPlayer to stream network MP4 files
                val rangeHeader = session.headers["range"]
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val range = rangeHeader.substring(6).split("-")
                    val start = range[0].toLongOrNull() ?: 0L
                    val end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else f.length() - 1
                    val contentLength = end - start + 1
                    
                    val fis = FileInputStream(f)
                    fis.skip(start)
                    val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", fis, contentLength)
                    res.addHeader("Content-Range", "bytes $start-$end/${f.length()}")
                    res.addHeader("Accept-Ranges", "bytes")
                    return res
                } else {
                    val res = newFixedLengthResponse(Response.Status.OK, "video/mp4", FileInputStream(f), f.length())
                    res.addHeader("Accept-Ranges", "bytes")
                    return res
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
        }
    }
}