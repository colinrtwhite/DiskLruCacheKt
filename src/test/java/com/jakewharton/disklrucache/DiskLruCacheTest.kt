/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jakewharton.disklrucache

import okio.buffer
import okio.sink
import okio.source
import org.apache.commons.io.FileUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileWriter

class DiskLruCacheTest {

    private lateinit var cacheDir: File
    private lateinit var journalFile: File
    private lateinit var journalBkpFile: File
    private lateinit var cache: DiskLruCache

    @get:Rule
    var tempDir = TemporaryFolder()

    @Before
    fun setUp() {
        cacheDir = tempDir.newFolder("DiskLruCacheTest")
        journalFile = File(cacheDir, DiskLruCache.JOURNAL_FILE)
        journalBkpFile = File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP)
        cacheDir.listFiles().forEach { it.delete() }
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
    }

    @After
    fun tearDown() {
        cache.close()
    }

    @Test
    fun emptyCache() {
        cache.close()
        assertJournalEquals()
    }

    @Test
    fun validateKey() {
        var key: String? = null
        try {
            key = "has_space "
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("Keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        try {
            key = "has_CR\r"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("Keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        try {
            key = "has_LF\n"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("Keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        try {
            key = "has_invalid/"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("Keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        try {
            key = "has_invalid\u2603"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("Keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        try {
            key = "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_" +
                    "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long"
            cache.edit(key)
            fail("Expecting an IllegalArgumentException as the key was too long.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo("Keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        // Test valid cases.

        // Exactly 120.
        key = "0123456789012345678901234567890123456789012345678901234567890123456789" + "01234567890123456789012345678901234567890123456789"
        cache.edit(key)!!.abort()
        // Contains all valid characters.
        key = "abcdefghijklmnopqrstuvwxyz_0123456789"
        cache.edit(key)!!.abort()
        // Contains dash.
        key = "-20384573948576"
        cache.edit(key)!!.abort()
    }

    @Test
    fun writeAndReadEntry() {
        val creator = cache.edit("k1")!!
        creator[0] = "ABC"
        creator[1] = "DE"
        assertThat(creator.getString(0)).isNull()
        assertThat(creator.newSource(0)).isNull()
        assertThat(creator.getString(1)).isNull()
        assertThat(creator.newSource(1)).isNull()
        creator.commit()

        val snapshot = cache["k1"]!!
        assertThat(snapshot.getString(0)).isEqualTo("ABC")
        assertThat(snapshot.getLength(0)).isEqualTo(3)
        assertThat(snapshot.getString(1)).isEqualTo("DE")
        assertThat(snapshot.getLength(1)).isEqualTo(2)
    }

    @Test
    fun readAndWriteEntryAcrossCacheOpenAndClose() {
        val creator = cache.edit("k1")!!
        creator[0] = "A"
        creator[1] = "B"
        creator.commit()
        cache.close()

        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        val snapshot = cache["k1"]!!
        assertThat(snapshot.getString(0)).isEqualTo("A")
        assertThat(snapshot.getLength(0)).isEqualTo(1)
        assertThat(snapshot.getString(1)).isEqualTo("B")
        assertThat(snapshot.getLength(1)).isEqualTo(1)
        snapshot.close()
    }

    @Test
    fun readAndWriteEntryWithoutProperClose() {
        val creator = cache.edit("k1")!!
        creator[0] = "A"
        creator[1] = "B"
        creator.commit()

        // Simulate a dirty close of 'cache' by opening the cache directory again.
        val cache2 = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        val snapshot = cache2["k1"]!!
        assertThat(snapshot.getString(0)).isEqualTo("A")
        assertThat(snapshot.getLength(0)).isEqualTo(1)
        assertThat(snapshot.getString(1)).isEqualTo("B")
        assertThat(snapshot.getLength(1)).isEqualTo(1)
        snapshot.close()
        cache2.close()
    }

    @Test
    fun journalWithEditAndPublish() {
        val creator = cache.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator[0] = "AB"
        creator[1] = "C"
        creator.commit()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1")
    }

    @Test
    fun revertedNewFileIsRemoveInJournal() {
        val creator = cache.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator[0] = "AB"
        creator[1] = "C"
        creator.abort()
        cache.close()
        assertJournalEquals("DIRTY k1", "REMOVE k1")
    }

    @Test
    fun unterminatedEditIsRevertedOnClose() {
        cache.edit("k1")
        cache.close()
        assertJournalEquals("DIRTY k1", "REMOVE k1")
    }

    @Test
    fun journalDoesNotIncludeReadOfYetUnpublishedValue() {
        val creator = cache.edit("k1")!!
        assertThat(cache["k1"]).isNull()
        creator[0] = "A"
        creator[1] = "BC"
        creator.commit()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 1 2")
    }

    @Test
    fun journalWithEditAndPublishAndRead() {
        val k1Creator = cache.edit("k1")!!
        k1Creator[0] = "AB"
        k1Creator[1] = "C"
        k1Creator.commit()
        val k2Creator = cache.edit("k2")!!
        k2Creator[0] = "DEF"
        k2Creator[1] = "G"
        k2Creator.commit()
        val k1Snapshot = cache["k1"]!!
        k1Snapshot.close()
        cache.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1")
    }

    @Test
    fun cannotOperateOnEditAfterPublish() {
        val editor = cache.edit("k1")!!
        editor[0] = "A"
        editor[1] = "B"
        editor.commit()
        assertInoperable(editor)
    }

    @Test
    fun cannotOperateOnEditAfterRevert() {
        val editor = cache.edit("k1")!!
        editor[0] = "A"
        editor[1] = "B"
        editor.abort()
        assertInoperable(editor)
    }

    @Test
    fun explicitRemoveAppliedToDiskImmediately() {
        val editor = cache.edit("k1")!!
        editor[0] = "ABC"
        editor[1] = "B"
        editor.commit()
        val k1 = getCleanFile("k1", 0)
        assertThat(readFile(k1)).isEqualTo("ABC")
        cache.remove("k1")
        assertThat(k1.exists()).isFalse()
    }

    /**
     * Each read sees a snapshot of the file at the time read was called.
     * This means that two reads of the same key can see different data.
     */
    @Test
    fun readAndWriteOverlapsMaintainConsistency() {
        val v1Creator = cache.edit("k1")!!
        v1Creator[0] = "AAaa"
        v1Creator[1] = "BBbb"
        v1Creator.commit()

        val snapshot1 = cache["k1"]!!
        val inV1 = snapshot1.getSource(0).buffer()
        assertThat(inV1.readByte()).isEqualTo('A'.toByte())
        assertThat(inV1.readByte()).isEqualTo('A'.toByte())

        val v1Updater = cache.edit("k1")!!
        v1Updater[0] = "CCcc"
        v1Updater[1] = "DDdd"
        v1Updater.commit()

        val snapshot2 = cache["k1"]!!
        assertThat(snapshot2.getString(0)).isEqualTo("CCcc")
        assertThat(snapshot2.getLength(0)).isEqualTo(4)
        assertThat(snapshot2.getString(1)).isEqualTo("DDdd")
        assertThat(snapshot2.getLength(1)).isEqualTo(4)
        snapshot2.close()

        assertThat(inV1.readByte()).isEqualTo('a'.toByte())
        assertThat(inV1.readByte()).isEqualTo('a'.toByte())
        assertThat(snapshot1.getString(1)).isEqualTo("BBbb")
        assertThat(snapshot1.getLength(1)).isEqualTo(4)
        snapshot1.close()
    }

    @Test
    fun openWithDirtyKeyDeletesAllFilesForThatKey() {
        cache.close()
        val cleanFile0 = getCleanFile("k1", 0)
        val cleanFile1 = getCleanFile("k1", 1)
        val dirtyFile0 = getDirtyFile("k1", 0)
        val dirtyFile1 = getDirtyFile("k1", 1)
        writeFile(cleanFile0, "A")
        writeFile(cleanFile1, "B")
        writeFile(dirtyFile0, "C")
        writeFile(dirtyFile1, "D")
        createJournal("CLEAN k1 1 1", "DIRTY   k1")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertThat(cleanFile0.exists()).isFalse()
        assertThat(cleanFile1.exists()).isFalse()
        assertThat(dirtyFile0.exists()).isFalse()
        assertThat(dirtyFile1.exists()).isFalse()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithInvalidVersionClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "0", "100", "2", "")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidAppVersionClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "101", "2", "")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidValueCountClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "1", "")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidBlankLineClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "2", "x")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
    }

    @Test
    fun openWithInvalidJournalLineClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1", "BOGUS")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithInvalidFileSizeClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 0000x001 1")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun openWithTruncatedLineDiscardsThatLine() {
        cache.close()
        writeFile(getCleanFile("k1", 0), "A")
        writeFile(getCleanFile("k1", 1), "B")
        val writer = FileWriter(journalFile)
        writer.write(DiskLruCache.MAGIC + "\n" + DiskLruCache.VERSION_1 + "\n100\n2\n\nCLEAN k1 1 1") // no trailing newline
        writer.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertThat(cache["k1"]).isNull()

        // The journal is not corrupt when editing after a truncated line.
        set("k1", "C", "D")
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertValue("k1", "C", "D")
    }

    @Test
    fun openWithTooManyFileSizesClearsDirectory() {
        cache.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1 1")
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
        assertGarbageFilesAllDeleted()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun keyWithSpaceNotPermitted() {
        try {
            cache.edit("my key")
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun keyWithNewlineNotPermitted() {
        try {
            cache.edit("my\nkey")
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun keyWithCarriageReturnNotPermitted() {
        try {
            cache.edit("my\rkey")
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun createNewEntryWithTooFewValuesFails() {
        val creator = cache.edit("k1")!!
        creator[1] = "A"
        try {
            creator.commit()
            fail()
        } catch (expected: IllegalStateException) {
        }

        assertThat(getCleanFile("k1", 0).exists()).isFalse()
        assertThat(getCleanFile("k1", 1).exists()).isFalse()
        assertThat(getDirtyFile("k1", 0).exists()).isFalse()
        assertThat(getDirtyFile("k1", 1).exists()).isFalse()
        assertThat(cache["k1"]).isNull()

        val creator2 = cache.edit("k1")!!
        creator2[0] = "B"
        creator2[1] = "C"
        creator2.commit()
    }

    @Test
    fun revertWithTooFewValues() {
        val creator = cache.edit("k1")!!
        creator[1] = "A"
        creator.abort()
        assertThat(getCleanFile("k1", 0).exists()).isFalse()
        assertThat(getCleanFile("k1", 1).exists()).isFalse()
        assertThat(getDirtyFile("k1", 0).exists()).isFalse()
        assertThat(getDirtyFile("k1", 1).exists()).isFalse()
        assertThat(cache["k1"]).isNull()
    }

    @Test
    fun updateExistingEntryWithTooFewValuesReusesPreviousValues() {
        val creator = cache.edit("k1")!!
        creator[0] = "A"
        creator[1] = "B"
        creator.commit()

        val updater = cache.edit("k1")!!
        updater[0] = "C"
        updater.commit()

        val snapshot = cache["k1"]!!
        assertThat(snapshot.getString(0)).isEqualTo("C")
        assertThat(snapshot.getLength(0)).isEqualTo(1)
        assertThat(snapshot.getString(1)).isEqualTo("B")
        assertThat(snapshot.getLength(1)).isEqualTo(1)
        snapshot.close()
    }

    @Test
    fun growMaxSize() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        cache.setMaxSize(20)
        set("c", "c", "c") // size 12
        assertThat(cache.size()).isEqualTo(12)
    }

    @Test
    fun shrinkMaxSizeEvicts() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 20)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        set("c", "c", "c") // size 12
        cache.setMaxSize(10)
        assertThat(cache.executorService.queue.size).isEqualTo(1)
        cache.executorService.purge()
    }

    @Test
    fun evictOnInsert() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)

        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        assertThat(cache.size()).isEqualTo(10)

        // Cause the size to grow to 12 should evict 'A'.
        set("c", "c", "c")
        cache.flush()
        assertThat(cache.size()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")

        // Causing the size to grow to 10 should evict nothing.
        set("d", "d", "d")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")
        assertValue("d", "d", "d")

        // Causing the size to grow to 18 should evict 'B' and 'C'.
        set("e", "eeee", "eeee")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertAbsent("b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "eeee", "eeee")
    }

    @Test
    fun evictOnUpdate() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)

        set("a", "a", "aa") // size 3
        set("b", "b", "bb") // size 3
        set("c", "c", "cc") // size 3
        assertThat(cache.size()).isEqualTo(9)

        // Causing the size to grow to 11 should evict 'A'.
        set("b", "b", "bbbb")
        cache.flush()
        assertThat(cache.size()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "b", "bbbb")
        assertValue("c", "c", "cc")
    }

    @Test
    fun evictionHonorsLruFromCurrentSession() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        cache["b"]!!.close() // 'B' is now least recently used.

        // Causing the size to grow to 12 should evict 'A'.
        set("f", "f", "f")
        // Causing the size to grow to 12 should evict 'C'.
        set("g", "g", "g")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
    }

    @Test
    fun evictionHonorsLruFromPreviousSession() {
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        set("f", "f", "f")
        cache["b"]!!.close() // 'B' is now least recently used.
        assertThat(cache.size()).isEqualTo(12)
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)

        set("g", "g", "g")
        cache.flush()
        assertThat(cache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
        assertValue("g", "g", "g")
    }

    @Test
    fun cacheSingleEntryOfSizeGreaterThanMaxSize() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)
        set("a", "aaaaa", "aaaaaa") // size=11
        cache.flush()
        assertAbsent("a")
    }

    @Test
    fun cacheSingleValueOfSizeGreaterThanMaxSize() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)
        set("a", "aaaaaaaaaaa", "a") // size=12
        cache.flush()
        assertAbsent("a")
    }

    @Test
    fun constructorDoesNotAllowZeroCacheSize() {
        try {
            DiskLruCache.open(cacheDir, APP_VERSION, 2, 0)
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun constructorDoesNotAllowZeroValuesPerEntry() {
        try {
            DiskLruCache.open(cacheDir, APP_VERSION, 0, 10)
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun removeAbsentElement() {
        cache.remove("a")
    }

    @Test
    fun readingTheSameStreamMultipleTimes() {
        set("a", "a", "b")
        val snapshot = cache["a"]!!
        assertThat(snapshot.getSource(0)).isSameAs(snapshot.getSource(0))
        snapshot.close()
    }

    @Test
    fun rebuildJournalOnRepeatedReads() {
        set("a", "a", "a")
        set("b", "b", "b")
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile.length()
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            if (journalLength < lastJournalLength) {
                println("Journal compacted from $lastJournalLength bytes to $journalLength bytes")
                break // Test passed!
            }
            lastJournalLength = journalLength
        }
    }

    @Test
    fun rebuildJournalOnRepeatedEdits() {
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile.length()
            set("a", "a", "a")
            set("b", "b", "b")
            if (journalLength < lastJournalLength) {
                println("Journal compacted from $lastJournalLength bytes to $journalLength bytes")
                break
            }
            lastJournalLength = journalLength
        }

        // Sanity check that a rebuilt journal behaves normally.
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
    }

    /**
     * @see [Issue .28](https://github.com/JakeWharton/DiskLruCache/issues/28)
     */
    @Test
    fun rebuildJournalOnRepeatedReadsWithOpenAndClose() {
        set("a", "a", "a")
        set("b", "b", "b")
        var lastJournalLength = 0L
        while (true) {
            val journalLength = journalFile.length()
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            cache.close()
            cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
            if (journalLength < lastJournalLength) {
                println("Journal compacted from $lastJournalLength bytes to $journalLength bytes.")
                break // Test passed!
            }
            lastJournalLength = journalLength
        }
    }

    /**
     * @see [Issue .28](https://github.com/JakeWharton/DiskLruCache/issues/28)
     */
    @Test
    fun rebuildJournalOnRepeatedEditsWithOpenAndClose() {
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile.length()
            set("a", "a", "a")
            set("b", "b", "b")
            cache.close()
            cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)
            if (journalLength < lastJournalLength) {
                println("Journal compacted from $lastJournalLength bytes to $journalLength bytes.")
                break
            }
            lastJournalLength = journalLength
        }
    }

    @Test
    fun restoreBackupFile() {
        val creator = cache.edit("k1")!!
        creator[0] = "ABC"
        creator[1] = "DE"
        creator.commit()
        cache.close()

        assertThat(journalFile.renameTo(journalBkpFile)).isTrue()
        assertThat(journalFile.exists()).isFalse()

        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)

        val snapshot = cache["k1"]!!
        assertThat(snapshot.getString(0)).isEqualTo("ABC")
        assertThat(snapshot.getLength(0)).isEqualTo(3)
        assertThat(snapshot.getString(1)).isEqualTo("DE")
        assertThat(snapshot.getLength(1)).isEqualTo(2)

        assertThat(journalBkpFile.exists()).isFalse()
        assertThat(journalFile.exists()).isTrue()
    }

    @Test
    fun journalFileIsPreferredOverBackupFile() {
        var creator = cache.edit("k1")!!
        creator[0] = "ABC"
        creator[1] = "DE"
        creator.commit()
        cache.flush()

        FileUtils.copyFile(journalFile, journalBkpFile)

        creator = cache.edit("k2")!!
        creator[0] = "F"
        creator[1] = "GH"
        creator.commit()
        cache.close()

        assertThat(journalFile.exists()).isTrue()
        assertThat(journalBkpFile.exists()).isTrue()

        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, Long.MAX_VALUE)

        val snapshotA = cache["k1"]!!
        assertThat(snapshotA.getString(0)).isEqualTo("ABC")
        assertThat(snapshotA.getLength(0)).isEqualTo(3)
        assertThat(snapshotA.getString(1)).isEqualTo("DE")
        assertThat(snapshotA.getLength(1)).isEqualTo(2)

        val snapshotB = cache["k2"]!!
        assertThat(snapshotB.getString(0)).isEqualTo("F")
        assertThat(snapshotB.getLength(0)).isEqualTo(1)
        assertThat(snapshotB.getString(1)).isEqualTo("GH")
        assertThat(snapshotB.getLength(1)).isEqualTo(2)

        assertThat(journalBkpFile.exists()).isFalse()
        assertThat(journalFile.exists()).isTrue()
    }

    @Test
    fun openCreatesDirectoryIfNecessary() {
        cache.close()
        val dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary")
        cache = DiskLruCache.open(dir, APP_VERSION, 2, Long.MAX_VALUE)
        set("a", "a", "a")
        assertThat(File(dir, "a.0").exists()).isTrue()
        assertThat(File(dir, "a.1").exists()).isTrue()
        assertThat(File(dir, "journal").exists()).isTrue()
    }

    @Test
    fun fileDeletedExternally() {
        set("a", "a", "a")
        getCleanFile("a", 1).delete()
        assertThat(cache["a"]).isNull()
    }

    @Test
    fun editSameVersion() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        val editor = snapshot.edit()!!
        editor[1] = "a2"
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    fun editSnapshotAfterChangeAborted() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        val toAbort = snapshot.edit()!!
        toAbort[0] = "b"
        toAbort.abort()
        val editor = snapshot.edit()!!
        editor[1] = "a2"
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    fun editSnapshotAfterChangeCommitted() {
        set("a", "a", "a")
        val snapshot = cache["a"]!!
        val toAbort = snapshot.edit()!!
        toAbort[0] = "b"
        toAbort.commit()
        assertThat(snapshot.edit()).isNull()
    }

    @Test
    fun editSinceEvicted() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache["a"]!!
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        cache.flush()
        assertThat(snapshot.edit()).isNull()
    }

    @Test
    fun editSinceEvictedAndRecreated() {
        cache.close()
        cache = DiskLruCache.open(cacheDir, APP_VERSION, 2, 10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache["a"]!!
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        set("a", "a", "aaaa") // size 5; will evict 'B'
        cache.flush()
        assertThat(snapshot.edit()).isNull()
    }

    /**
     * @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    fun aggressiveClearingHandlesWrite() {
        FileUtils.deleteDirectory(cacheDir)
        set("a", "a", "a")
        assertValue("a", "a", "a")
    }

    /**
     * @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    fun aggressiveClearingHandlesEdit() {
        set("a", "a", "a")
        val a = cache["a"]!!.edit()!!
        FileUtils.deleteDirectory(cacheDir)
        a[1] = "a2"
        a.commit()
    }

    @Test
    fun removeHandlesMissingFile() {
        set("a", "a", "a")
        getCleanFile("a", 0).delete()
        cache.remove("a")
    }

    /**
     * @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    fun aggressiveClearingHandlesPartialEdit() {
        set("a", "a", "a")
        set("b", "b", "b")
        val a = cache["a"]!!.edit()!!
        a[0] = "a1"
        FileUtils.deleteDirectory(cacheDir)
        a[1] = "a2"
        a.commit()
        assertThat(cache["a"]).isNull()
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    fun aggressiveClearingHandlesRead() {
        FileUtils.deleteDirectory(cacheDir)
        assertThat(cache["a"]).isNull()
    }

    private fun assertJournalEquals(vararg expectedBodyLines: String) {
        val expectedLines = ArrayList<String>()
        expectedLines.add(DiskLruCache.MAGIC)
        expectedLines.add(DiskLruCache.VERSION_1)
        expectedLines.add("100")
        expectedLines.add("2")
        expectedLines.add("")
        expectedLines.addAll(expectedBodyLines.toList())
        assertThat(readJournalLines()).isEqualTo(expectedLines)
    }

    private fun createJournal(vararg bodyLines: String) {
        createJournalWithHeader(DiskLruCache.MAGIC, DiskLruCache.VERSION_1, "100", "2", "", *bodyLines)
    }

    private fun createJournalWithHeader(
        magic: String,
        version: String,
        appVersion: String,
        valueCount: String,
        blank: String,
        vararg bodyLines: String
    ) {
        val writer = FileWriter(journalFile)
        writer.write(magic + "\n")
        writer.write(version + "\n")
        writer.write(appVersion + "\n")
        writer.write(valueCount + "\n")
        writer.write(blank + "\n")
        for (line in bodyLines) {
            writer.write(line)
            writer.write('\n'.toInt())
        }
        writer.close()
    }

    private fun readJournalLines(): List<String> {
        val lines = mutableListOf<String>()
        val sink = journalFile.source().buffer()
        var line = sink.readUtf8Line()
        while (line != null) {
            lines += line
            line = sink.readUtf8Line()
        }
        return lines
    }

    private fun getCleanFile(key: String, index: Int): File {
        return File(cacheDir, "$key.$index")
    }

    private fun getDirtyFile(key: String, index: Int): File {
        return File(cacheDir, "$key.$index.tmp")
    }

    private fun generateSomeGarbageFiles() {
        val dir1 = File(cacheDir, "dir1")
        val dir2 = File(dir1, "dir2")
        writeFile(getCleanFile("g1", 0), "A")
        writeFile(getCleanFile("g1", 1), "B")
        writeFile(getCleanFile("g2", 0), "C")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(File(cacheDir, "otherFile0"), "E")
        dir1.mkdir()
        dir2.mkdir()
        writeFile(File(dir2, "otherFile1"), "F")
    }

    private fun assertGarbageFilesAllDeleted() {
        assertThat(getCleanFile("g1", 0)).doesNotExist()
        assertThat(getCleanFile("g1", 1)).doesNotExist()
        assertThat(getCleanFile("g2", 0)).doesNotExist()
        assertThat(getCleanFile("g2", 1)).doesNotExist()
        assertThat(File(cacheDir, "otherFile0")).doesNotExist()
        assertThat(File(cacheDir, "dir1")).doesNotExist()
    }

    private operator fun set(key: String, value0: String, value1: String) {
        val editor = cache.edit(key)!!
        editor[0] = value0
        editor[1] = value1
        editor.commit()
    }

    private fun assertAbsent(key: String) {
        val snapshot = cache[key]
        if (snapshot != null) {
            snapshot.close()
            fail()
        }
        assertThat(getCleanFile(key, 0)).doesNotExist()
        assertThat(getCleanFile(key, 1)).doesNotExist()
        assertThat(getDirtyFile(key, 0)).doesNotExist()
        assertThat(getDirtyFile(key, 1)).doesNotExist()
    }

    private fun assertValue(key: String, value0: String, value1: String) {
        val snapshot = cache[key]!!
        assertThat(snapshot.getString(0)).isEqualTo(value0)
        assertThat(snapshot.getLength(0)).isEqualTo(value0.length.toLong())
        assertThat(snapshot.getString(1)).isEqualTo(value1)
        assertThat(snapshot.getLength(1)).isEqualTo(value1.length.toLong())
        assertThat(getCleanFile(key, 0)).exists()
        assertThat(getCleanFile(key, 1)).exists()
        snapshot.close()
    }

    companion object {

        private const val APP_VERSION = 100

        private fun readFile(file: File): String {
            return file.source().buffer().readString(Charsets.UTF_8)
        }

        fun writeFile(file: File, content: String) {
            file.sink().buffer().writeUtf8(content)
        }

        private fun assertInoperable(editor: DiskLruCache.Editor) {
            try {
                editor.getString(0)
                fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor[0] = "A"
                fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.newSource(0)
                fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.newSink(0)
                fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.commit()
                fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.abort()
                fail()
            } catch (expected: IllegalStateException) {
            }
        }
    }
}
