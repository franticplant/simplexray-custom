// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.data.source

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileManagerTest {
    private val dir: File = Files.createTempDirectory("configs").toFile()

    @Test
    fun newConfigFile_usesTheNameWhenItIsFree() {
        assertEquals("Server.json", FileManager.newConfigFile(dir, "Server").name)
    }

    @Test
    fun newConfigFile_neverReplacesAnExistingConfig() {
        File(dir, "Server.json").writeText("{}")
        File(dir, "Server (2).json").writeText("{}")

        assertEquals("Server (3).json", FileManager.newConfigFile(dir, "Server").name)
        assertEquals("Other.json", FileManager.newConfigFile(dir, "Other").name)
    }
}
