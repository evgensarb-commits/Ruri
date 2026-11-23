package com.ruri

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DiaryThoughtOnlyTest {
    @Test fun spokenRepliesNeverEnterTheDiary() {
        val root = Files.createTempDirectory("ruri-thought-diary").toFile()
        try {
            FileManager.init(root)
            Diary.append(root, "Внутренняя мысль", "reflection", null, "thought")
            Diary.append(root, "Ответ Командиру", "chat_reply", null, "said")
            val entries = JSONObject(FileManager.read(root, "memory/L2_diary.json"))
                .getJSONArray("entries")
            assertEquals(1, entries.length())
            assertEquals("thought", entries.getJSONObject(0).optString("kind"))
        } finally { root.deleteRecursively() }
    }
}
