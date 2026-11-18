package com.ruri

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalBackupManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun snapshotIsVerifiableAndExcludesEncryptedSecrets() {
        val root = temporaryFolder.newFolder("ruri")
        FileManager.init(root)
        FileManager.write(root, "memory/L1_facts.json", """{"facts":[{"text":"remember"}]}""")
        File(root, "core/secrets.enc.json").writeText("DO_NOT_BACK_UP")

        val archive = LocalBackupManager.createSnapshot(root, 1_800_000_000_000L)
        val verified = LocalBackupManager.verify(archive)

        assertTrue(verified.error, verified.valid)
        assertTrue(verified.files.containsKey("memory/L1_facts.json"))
        assertFalse(verified.files.containsKey("core/secrets.enc.json"))
        assertFalse(verified.files.values.any { it.toString(Charsets.UTF_8).contains("DO_NOT_BACK_UP") })
    }

    @Test
    fun restoreCreatesPreRestoreSnapshotAndRestoresVerifiedFiles() {
        val root = temporaryFolder.newFolder("ruri")
        FileManager.init(root)
        FileManager.write(root, "memory/L1_facts.json", """{"facts":["before"]}""")
        val archive = LocalBackupManager.createSnapshot(root, 1_800_000_000_000L)

        FileManager.write(root, "memory/L1_facts.json", """{"facts":["after"]}""")
        FileManager.write(root, "memory/created_after_backup.json", """{"temporary":true}""")
        val result = LocalBackupManager.restore(root, archive.name, 1_800_000_100_000L)

        assertTrue(result.optBoolean("restored"))
        assertTrue(result.optBoolean("pre_restore_backup_created"))
        assertEquals("""{"facts":["before"]}""", FileManager.read(root, "memory/L1_facts.json"))
        assertFalse(FileManager.exists(root, "memory/created_after_backup.json"))
        assertEquals(2, LocalBackupManager.status(root).optInt("count"))
    }

    @Test
    fun retentionKeepsOnlyFourteenNewestSnapshots() {
        val root = temporaryFolder.newFolder("ruri")
        FileManager.init(root)
        repeat(15) { index ->
            LocalBackupManager.createSnapshot(root, 1_800_000_000_000L + index * 86_400_000L)
        }
        assertEquals(14, LocalBackupManager.status(root).optInt("count"))
    }

    @Test
    fun dueCheckDoesNotCreateMoreThanOneBackupPerDay() {
        val root = temporaryFolder.newFolder("ruri")
        FileManager.init(root)
        val first = LocalBackupManager.createIfDue(root, 1_800_000_000_000L)
        val second = LocalBackupManager.createIfDue(root, 1_800_000_100_000L)
        assertTrue(first.optBoolean("created"))
        assertFalse(second.optBoolean("created"))
        assertEquals("not_due", second.optString("reason"))
    }
}
