package com.emc.logservice.storageabstraction.mocks;

import com.emc.logservice.common.AsyncIterator;
import com.emc.logservice.storageabstraction.DataLogWriterNotPrimaryException;
import com.emc.logservice.storageabstraction.DurableDataLog;
import com.emc.nautilus.testcommon.AssertExtensions;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;

/**
 * Unit tests for InMemoryDurableDataLog.
 */
public class InMemoryDurableDataLogTests {
    private static final Duration Timeout = Duration.ofSeconds(30);
    private static final int writeCount = 250;

    //region General DurableDataLog Tests

    /**
     * Tests the ability to append to a DurableDataLog.
     */
    @Test
    public void testAppend() {
        try (DurableDataLog log = createDurableDataLog()) {
            // Check Append pre-initialization.
            AssertExtensions.assertThrows(
                    "append() worked before initialize()",
                    () -> log.append(new ByteArrayInputStream("h".getBytes()), Timeout),
                    ex -> ex instanceof IllegalStateException);

            log.initialize(Timeout).join();

            // Only verify sequence number monotonicity. We'll verify reads in its own test.
            long prevSeqNo = -1;
            for (int i = 0; i < writeCount; i++) {
                byte[] writeData = String.format("Write_%s", i).getBytes();
                ByteArrayInputStream writeStream = new ByteArrayInputStream(writeData);
                long seqNo = log.append(writeStream, Timeout).join();

                AssertExtensions.assertLessThan("Sequence Number is not monotonically increasing.", prevSeqNo, seqNo);
                prevSeqNo = seqNo;
            }
        }
    }

    /**
     * Tests the ability to read from a DurableDataLog.
     */
    @Test
    public void testGetReader() throws Exception {
        try (DurableDataLog log = createDurableDataLog()) {
            // Check Read pre-initialization.
            AssertExtensions.assertThrows(
                    "read() worked before initialize()",
                    () -> log.getReader(0),
                    ex -> ex instanceof IllegalStateException);

            log.initialize(Timeout).join();
            TreeMap<Long, byte[]> writeData = populate(log, writeCount);

            // Test reading after each sequence number that we got back.
            for (long seqNo : writeData.keySet()) {
                testRead(log, seqNo, writeData);
            }

            // Test reading from a sequence number before the first one.
            testRead(log, writeData.firstKey() - 1, writeData);

            // Test reading from a sequence number way beyond the last one.
            testRead(log, writeData.lastKey() * 2, writeData);
        }
    }

    /**
     * Tests the ability to truncate from a DurableDataLog.
     */
    @Test
    public void testTruncate() throws Exception {
        try (DurableDataLog log = createDurableDataLog()) {
            // Check Read pre-initialization.
            AssertExtensions.assertThrows(
                    "truncate() worked before initialize()",
                    () -> log.truncate(0, Timeout),
                    ex -> ex instanceof IllegalStateException);

            log.initialize(Timeout).join();
            TreeMap<Long, byte[]> writeData = populate(log, writeCount);
            ArrayList<Long> seqNos = new ArrayList<>(writeData.keySet());

            // Test truncating after each sequence number that we got back.
            for (long seqNo : seqNos) {
                log.truncate(seqNo, Timeout).join();
                writeData.remove(seqNo);
                testRead(log, -1, writeData);
            }
        }
    }

    //endregion

    //region InMemoryDurableDataLog-specific tests

    /**
     * Tests the ability of InMemoryDurableDataLog to simulate an exclusive writer, by only allowing one client at a time
     * to write to an "EntryCollection".
     */
    @Test
    public void testExclusiveWriteLock() throws Exception {
        InMemoryDurableDataLog.EntryCollection entries = new InMemoryDurableDataLog.EntryCollection();

        try (DurableDataLog log = new InMemoryDurableDataLog(entries)) {
            log.initialize(Timeout).join();

            // 1. No two logs can use the same EntryCollection.
            AssertExtensions.assertThrows(
                    "A second log was able to acquire the exclusive write lock, even if another log held it.",
                    () -> {
                        try (DurableDataLog log2 = new InMemoryDurableDataLog(entries)) {
                            log2.initialize(Timeout).join();
                        }
                    },
                    ex -> ex instanceof DataLogWriterNotPrimaryException);

            // Verify we can still append to the first log.
            TreeMap<Long, byte[]> writeData = populate(log, writeCount);

            // 2. If during the normal operation of a log, it loses its lock, it should no longer be able to append...
            entries.forceAcquireLock("ForceLock");
            AssertExtensions.assertThrows(
                    "A second log was able to acquire the exclusive write lock, even if another log held it.",
                    () -> log.append(new ByteArrayInputStream("h".getBytes()), Timeout).join(),
                    ex -> ex instanceof DataLogWriterNotPrimaryException);

            // ... or to truncate ...
            AssertExtensions.assertThrows(
                    "A second log was able to acquire the exclusive write lock, even if another log held it.",
                    () -> log.truncate(writeData.lastKey(), Timeout).join(),
                    ex -> ex instanceof DataLogWriterNotPrimaryException);

            // ... but it should still be able to read.
            testRead(log, -1, writeData);
        }
    }

    /**
     * Tests the constructor of InMemoryDurableDataLog. The constructor takes in an EntryCollection and this verifies
     * that information from a previous instance of an InMemoryDurableDataLog is still accessible.
     */
    @Test
    public void testConstructor() throws Exception {
        InMemoryDurableDataLog.EntryCollection entries = new InMemoryDurableDataLog.EntryCollection();
        TreeMap<Long, byte[]> writeData;
        // Create first log and write some data to it.
        try (DurableDataLog log = new InMemoryDurableDataLog(entries)) {
            log.initialize(Timeout).join();
            writeData = populate(log, writeCount);
        }

        // Close the first log, and open a second one, with the same EntryCollection in the constructor.
        try (DurableDataLog log = new InMemoryDurableDataLog(entries)) {
            log.initialize(Timeout).join();

            // Verify it contains the same entries.
            testRead(log, -1, writeData);
        }
    }

    //endregion

    private TreeMap<Long, byte[]> populate(DurableDataLog log, int writeCount) {
        TreeMap<Long, byte[]> writtenData = new TreeMap<>();
        for (int i = 0; i < writeCount; i++) {
            byte[] writeData = String.format("Write_%s", i).getBytes();
            ByteArrayInputStream writeStream = new ByteArrayInputStream(writeData);
            long seqNo = log.append(writeStream, Timeout).join();
            writtenData.put(seqNo, writeData);
        }

        return writtenData;
    }

    private void testRead(DurableDataLog log, long afterSequenceNumber, TreeMap<Long, byte[]> writeData) throws Exception {
        AsyncIterator<DurableDataLog.ReadItem> reader = log.getReader(afterSequenceNumber);
        SortedMap<Long, byte[]> expectedData = writeData.tailMap(afterSequenceNumber, false);
        Iterator<Long> expectedKeyIterator = expectedData.keySet().iterator();
        while (true) {
            DurableDataLog.ReadItem nextItem = reader.getNext(Timeout).join();
            if (nextItem == null) {
                Assert.assertFalse("Reader reached the end but there were still items to be read.", expectedKeyIterator.hasNext());
                break;
            }

            Assert.assertTrue("Reader has more items but there should not be any more items to be read.", expectedKeyIterator.hasNext());

            // Verify sequence number, as well as payload.
            long expectedSequenceNumber = expectedKeyIterator.next();
            Assert.assertEquals("Unexpected sequence number.", expectedSequenceNumber, nextItem.getSequence());
            Assert.assertArrayEquals("Unexpected payload for sequence number " + expectedSequenceNumber, expectedData.get(expectedSequenceNumber), nextItem.getPayload());
        }
    }

    protected DurableDataLog createDurableDataLog() {
        return new InMemoryDurableDataLog(new InMemoryDurableDataLog.EntryCollection());
    }
}
