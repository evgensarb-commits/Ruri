package com.ruri

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyTest {
    @Test
    fun stateCheckpointIsSeparateAndDoesNotRewriteLiveL4() {
        val root = Files.createTempDirectory("ruri-backup-checkpoint").toFile()
        try {
            val liveState = JSONObject().put("mood", "обиженная").put("ignored_count", 2).toString(2)
            FileManager.write(root, "memory/L4_state.json", liveState)

            BackupPolicy.checkpointState(root)

            assertEquals(liveState, FileManager.read(root, "memory/L4_state.json"))
            val saved = JSONObject(FileManager.read(root, "backup/checkpoints/L4_state.json"))
            assertEquals("обиженная", saved.optString("mood"))
            assertTrue(FileManager.read(root, BackupPolicy.META).isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicWriterCannotEscapeBackupDirectory() {
        val root = Files.createTempDirectory("ruri-backup-path").toFile()
        try {
            val result = runCatching {
                BackupPolicy.atomicWrite(root, "memory/L4_state.json", "{}")
            }
            assertFalse(result.isSuccess)
        } finally {
            root.deleteRecursively()
        }
    }
}
