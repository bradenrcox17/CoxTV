package com.coxtv.data.remote

import java.io.BufferedReader

object M3uParser {
    data class Entry(
        val name: String,
        val url: String,
        val tvgId: String?,
        val tvgName: String?,
        val logo: String?,
        val group: String?,
        val chno: String?,
    )

    data class Result(val entries: List<Entry>, val epgUrl: String?)

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(reader: BufferedReader): Result {
        val entries = ArrayList<Entry>()
        var epgUrl: String? = null
        var attrs: Map<String, String>? = null
        var name = ""
        var extGroup: String? = null

        for (raw in reader.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = attributes(line)
                    epgUrl = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()
                }
                line.startsWith("#EXTINF") -> {
                    attrs = attributes(line)
                    name = displayName(line)
                    extGroup = null
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val a = attrs
                    // Live TV only: provider "m3u_plus" playlists also list every movie and
                    // series episode (often 3-4x the live channels).
                    if (a != null && !line.contains("/movie/") && !line.contains("/series/")) {
                        entries += Entry(
                            name = name.ifBlank { a["tvg-name"].orEmpty() },
                            url = line,
                            tvgId = a["tvg-id"].nullIfBlank(),
                            tvgName = a["tvg-name"].nullIfBlank(),
                            logo = a["tvg-logo"].nullIfBlank(),
                            group = (a["group-title"] ?: extGroup).nullIfBlank(),
                            chno = (a["tvg-chno"] ?: a["channel-number"] ?: a["tvg-num"]).nullIfBlank(),
                        )
                    }
                    attrs = null
                }
            }
        }
        return Result(entries, epgUrl.nullIfBlank())
    }

    private fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The display name is everything after the first comma that is not inside quotes. */
    private fun displayName(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return line.substring(i + 1).trim()
            }
        }
        return ""
    }

    private fun String?.nullIfBlank() = this?.trim()?.takeIf { it.isNotEmpty() }
}
