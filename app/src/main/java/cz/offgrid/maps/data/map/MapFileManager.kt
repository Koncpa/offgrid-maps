package cz.offgrid.maps.data.map

import android.content.Context
import java.io.File

/**
 * Správce lokálních offline mapových podkladů (MBTiles).
 *
 * Jak funguje MBTiles:
 * - MBTiles je ve skutečnosti SQLite databáze obsahující jednotlivé dlaždice (Vector Tiles / MVT).
 * - Místo milionů malých souborů na disku má celá mapa ČR pouze jeden jediný soubor (např. czechia.mbtiles).
 * - SQLite umožňuje indexované čtení podle (zoom, x, y) s minimální režií a bez zbytečné zátěže RAM.
 */
class MapFileManager(private val context: Context) {

    /**
     * Adresář pro ukládání offline map v interním úložišti aplikace.
     */
    val mapsDirectory: File
        get() = File(context.filesDir, "maps").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Vrátí seznam všech dostupných offline .mbtiles souborů.
     */
    fun getAvailableMaps(): List<File> {
        return mapsDirectory.listFiles { file ->
            file.extension.equals("mbtiles", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    /**
     * Vrátí výchozí mapový soubor, pokud existuje.
     */
    fun getDefaultMap(): File? {
        return getAvailableMaps().firstOrNull()
    }

    /**
     * Vrátí celkovou velikost všech stažených map v megabytech.
     */
    fun getTotalMapsSizeMb(): Long {
        val totalBytes = getAvailableMaps().sumOf { it.length() }
        return totalBytes / (1024 * 1024)
    }
}
