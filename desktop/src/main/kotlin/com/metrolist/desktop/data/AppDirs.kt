package com.metrolist.desktop.data

import java.io.File

object AppDirs {
    val data: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base =
            when {
                os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
                os.contains("mac") -> File(home, "Library/Application Support")
                else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
            }
        File(base, "Metrolist").apply { mkdirs() }
    }

    fun file(name: String) = File(data, name)
}
