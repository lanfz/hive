/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.txn.compactor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.Lists;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.common.ValidWriteIdList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.CompactionType;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.ShowCompactRequest;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponseElement;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.txn.CompactionInfo;
import org.apache.hadoop.hive.metastore.txn.TxnUtils;
import org.apache.hadoop.hive.ql.DriverFactory;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hive.streaming.HiveStreamingConnection;
import org.apache.hive.streaming.StreamingConnection;
import org.apache.hive.streaming.StrictDelimitedInputWriter;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.TypeDescription;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import static org.apache.hadoop.hive.ql.txn.compactor.TestCompactor.executeStatementOnDriver;
import static org.apache.hadoop.hive.ql.txn.compactor.CompactorTestUtil.executeStatementOnDriverAndReturnResults;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SuppressWarnings("deprecation")
public class TestCrudCompactorOnTez extends CompactorOnTezTest {

  @Test
  public void testMajorCompactionNotPartitionedWithoutBuckets() throws Exception {
    String dbName = "default";
    String tblName = "testMajorCompaction";
    TestDataProvider testDataProvider = new TestDataProvider();
    testDataProvider.createFullAcidTable(tblName, false, false);
    testDataProvider.insertTestData(tblName);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas (delta_0000001_0000001_0000, delta_0000002_0000002_0000) are present
    Assert.assertEquals("Delta directories does not match before compaction",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000",
            "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify that delete delta (delete_delta_0000003_0000003_0000) is present
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));

    List<String> expectedRsBucket0 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":4}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":5}\t2\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t3\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":4}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":5}\t4\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t5\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":4}\t6\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":5}\t6\t4"));
    // Check bucket contents
    Assert.assertEquals("pre-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));

    // Run major compaction and cleaner
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Should contain only one base directory now
    String expectedBase = "base_0000005_v0000009";
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList(expectedBase),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Check base dir contents
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, null, expectedBase));
    // Check bucket contents
    Assert.assertEquals("post-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 0);
  }

  /**
   * TestDataProvider uses 2 buckets, I want to test 4 buckets here.
   * @throws Exception
   */
  @Test
  public void testMajorCompactionNotPartitioned4Buckets() throws Exception {
    String dbName = "default";
    String tblName = "testMajorCompaction";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create transactional table " + tblName + " (a int, b int) clustered"
        + " by (a) into 4 buckets"
        + " stored as ORC TBLPROPERTIES('bucketing_version'='2', 'transactional'='true',"
        + " 'transactional_properties'='default')", driver);
    executeStatementOnDriver("insert into " + tblName + " values(1,2),(1,3),(1,4),(2,2),(2,3),(2,4)", driver);
    executeStatementOnDriver("insert into " + tblName + " values(3,2),(3,3),(3,4),(4,2),(4,3),(4,4)", driver);
    executeStatementOnDriver("delete from " + tblName + " where b = 2", driver);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);

    Table table = msClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas (delta_0000001_0000001_0000, delta_0000002_0000002_0000) are present
    Assert.assertEquals("Delta directories does not match before compaction",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify that delete delta (delete_delta_0000003_0000003_0000) is present
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    List<String> expectedRsBucket0 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t4"
    ));
    List<String> expectedRsBucket1 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":1}\t1\t3",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":2}\t1\t4",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":2}\t4\t4"
    ));
    List<String> expectedRsBucket2 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":2,\"bucketid\":537001984,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":537001984,\"rowid\":2}\t3\t4"
    ));
    TestDataProvider testDataProvider = new TestDataProvider();
    List<String> preCompactionRsBucket0 = testDataProvider.getBucketData(tblName, "536870912");
    List<String> preCompactionRsBucket1 = testDataProvider.getBucketData(tblName, "536936448");
    List<String> preCompactionRsBucket2 = testDataProvider.getBucketData(tblName, "537001984");
    if (runsOnTez) { // Check bucket contents
      Assert.assertEquals("pre-compaction bucket 0", expectedRsBucket0, preCompactionRsBucket0);
      Assert.assertEquals("pre-compaction bucket 1", expectedRsBucket1, preCompactionRsBucket1);
      Assert.assertEquals("pre-compaction bucket 2", expectedRsBucket2, preCompactionRsBucket2);
    } else {
      // MR sometimes inserts rows in the opposite order from Tez, so rowids won't match. so we
      // just check whether the bucket contents change during compaction.
      expectedRsBucket0 = preCompactionRsBucket0;
      expectedRsBucket1 = preCompactionRsBucket1;
      expectedRsBucket2 = preCompactionRsBucket2;
    }

    // Run major compaction and cleaner
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Should contain only one base directory now
    String expectedBase = "base_0000003_v0000009";
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList(expectedBase),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Check files in base
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001", "bucket_00002");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, null, "base_0000003_v0000009"));
    // Check buckets contents
    Assert.assertEquals("post-compaction bucket 0", expectedRsBucket0, testDataProvider.getBucketData(tblName,
      "536870912"));
    Assert.assertEquals("post-compaction bucket 1", expectedRsBucket1, testDataProvider.getBucketData(tblName,
      "536936448"));
    Assert.assertEquals("post-compaction bucket 2", expectedRsBucket2, testDataProvider.getBucketData(tblName,
      "537001984"));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 1);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 2);
  }

  @Test
  public void testMajorCompactionPartitionedWithoutBuckets() throws Exception {
    String dbName = "default";
    String tblName = "testMajorCompaction";
    TestDataProvider testDataProvider = new TestDataProvider();
    testDataProvider.createFullAcidTable(tblName, true, false);
    testDataProvider.insertTestDataPartitioned(tblName);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);
    String tablePath = table.getSd().getLocation();
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Path todayPath = new Path(tablePath, partitionToday);
    Path tomorrowPath = new Path(tablePath, partitionTomorrow);
    Path yesterdayPath = new Path(tablePath, partitionYesterday);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));

    List<String> expectedRsBucket0 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t3\tyesterday",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t4\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t4\t3\ttomorrow",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t4\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2\tyesterday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3\tyesterday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t6\t2\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t6\t3\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t4\ttoday"));
    if (runsOnTez) { // Check bucket contents
      Assert.assertEquals("pre-compaction bucket 0", expectedRsBucket0,
          testDataProvider.getBucketData(tblName, "536870912"));
    } else {
      // MR sometimes inserts rows in the opposite order from Tez, so rowids won't match. so we
      // just check whether the bucket contents change during compaction.
      expectedRsBucket0 = testDataProvider.getBucketData(tblName, "536870912");
    }

    // Run major compaction and cleaner for all 3 partitions
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true,
        partitionToday, partitionTomorrow, partitionYesterday);
    CompactorTestUtil.runCleaner(conf);
    // 3 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction( 3);
    // Should contain only one base directory now
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionToday));
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000013"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionTomorrow));
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000017"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionYesterday));
    // Check base dir contents
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, "base_0000005_v0000009"));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionTomorrow, "base_0000005_v0000013"));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionYesterday, "base_0000005_v0000017"));
    // Check buckets contents
    Assert.assertEquals("post-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(todayPath, "base_0000005_v0000009"), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(tomorrowPath, "base_0000005_v0000013"), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(yesterdayPath, "base_0000005_v0000017"), 0);
  }

  @Test public void testMajorCompactionPartitionedWithBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMajorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, true, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestDataPartitioned(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));
    // Check bucket contents
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");

    List<String> expectedRsBucket1 = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t3\tyesterday",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t4\ttoday",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":0}\t4\t3\ttomorrow",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t2\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t5\t3\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t6\t2\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":2}\t6\t3\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":3}\t6\t4\ttoday");
    List<String> rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    if (runsOnTez) {
      Assert.assertEquals(expectedRsBucket0, rsBucket0);
      Assert.assertEquals(expectedRsBucket1, rsBucket1);
    } else {
      // MR sometimes inserts rows in the opposite order from Tez, so rowids won't match. so we
      // just check whether the bucket contents change during compaction.
      expectedRsBucket0 = rsBucket0;
      expectedRsBucket1 = rsBucket1;
    }

    // Run a compaction
    CompactorTestUtil
        .runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true, partitionToday,
            partitionTomorrow,
            partitionYesterday);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction( 3);
    // Verify base directories after compaction in each partition
    String expectedBaseToday = "base_0000005_v0000011";
    String expectedBaseTomorrow = "base_0000005_v0000015";
    String expectedBaseYesterday = "base_0000005_v0000019";
    List<String> baseDeltasInToday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionToday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList(expectedBaseToday), baseDeltasInToday);
    List<String> baseDeltasInTomorrow =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionTomorrow);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList(expectedBaseTomorrow), baseDeltasInTomorrow);
    List<String> baseDeltasInYesterday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionYesterday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList(expectedBaseYesterday), baseDeltasInYesterday);
    // Verify contents of bases
    Assert.assertEquals("Bucket names are not matching after compaction", Arrays.asList("bucket_00000", "bucket_00001"),
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, expectedBaseToday));
    Assert.assertEquals("Bucket names are not matching after compaction", Arrays.asList("bucket_00001"),
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionTomorrow, expectedBaseTomorrow));
    Assert.assertEquals("Bucket names are not matching after compaction", Arrays.asList("bucket_00000", "bucket_00001"),
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionYesterday, expectedBaseYesterday));
    // Verify contents of bucket files.
    // Bucket 0
    rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Bucket 1
    rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRsBucket1, rsBucket1);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    String tablePath = table.getSd().getLocation();
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionToday), expectedBaseToday), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionToday), expectedBaseToday), 1);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionTomorrow), expectedBaseTomorrow), 1);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionYesterday), expectedBaseYesterday), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionYesterday), expectedBaseYesterday), 1);
  }

  @Test
  public void testMinorCompactionNotPartitionedWithoutBuckets() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterComp);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":4}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":5}\t2\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t3\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":4}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":5}\t4\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t5\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":4}\t6\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":5}\t6\t4");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionNotPartitionedWithBuckets() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterComp);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t3\t4");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Bucket 1
    List<String> expectedRs1Bucket = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":1}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":2}\t2\t4",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":2}\t4\t4",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t2",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t5\t3",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":2}\t5\t4",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":3}\t6\t2",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":4}\t6\t3",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":5}\t6\t4");
    List<String> rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRs1Bucket, rsBucket1);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionPartitionedWithoutBuckets() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, true, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestDataPartitioned(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));
    // Run a compaction
    CompactorTestUtil
        .runCompaction(conf, dbName, tableName, CompactionType.MINOR, true, partitionToday, partitionTomorrow,
            partitionYesterday);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(3);
    // Verify delta directories after compaction in each partition
    List<String> actualDeltasAfterCompPartToday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterCompPartToday);
    List<String> actualDeleteDeltasAfterCompPartToday =
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterCompPartToday);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeltasAfterCompPartToday.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeleteDeltasAfterCompPartToday.get(0)));

    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionPartitionedWithBuckets() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, true, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestDataPartitioned(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));
    // Run a compaction
    CompactorTestUtil
        .runCompaction(conf, dbName, tableName, CompactionType.MINOR, true, partitionToday, partitionTomorrow,
            partitionYesterday);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction( 3);
    // Verify delta directories after compaction in each partition
    List<String> actualDeltasAfterCompPartToday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterCompPartToday);
    List<String> actualDeleteDeltasAfterCompPartToday =
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterCompPartToday);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeltasAfterCompPartToday.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeleteDeltasAfterCompPartToday.get(0)));
    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Bucket 1
    List<String> expectedRsBucket1 = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t3\tyesterday",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t4\ttoday",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":0}\t4\t3\ttomorrow",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t2\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t5\t3\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t6\t2\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":2}\t6\t3\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":3}\t6\t4\ttoday");
    List<String> rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRsBucket1, rsBucket1);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompaction10DeltaDirs() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName, 10);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Verify deltas
    List<String> deltaNames = CompactorTestUtil
        .getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals(10, deltaNames.size());
    List<String> deleteDeltaName =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals(5, deleteDeltaName.size());
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction( 1);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals(Collections.singletonList("delta_0000001_0000015_v0000019"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert
        .assertEquals(Collections.singletonList("delete_delta_0000001_0000015_v0000019"), actualDeleteDeltasAfterComp);
    // Verify bucket file in delta dir
    List<String> expectedBucketFile = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFile,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFile,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify contents of bucket file
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(5, rsBucket0.size());
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMultipleMinorCompactions() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(3);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000015_v0000044"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000015_v0000044"), actualDeleteDeltasAfterComp);

  }

  @Test
  public void testMinorCompactionWhileStreaming() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(a INT, b STRING) " + " CLUSTERED BY(a) INTO 1 BUCKETS"
        + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')", driver);
    StreamingConnection connection = null;
    try {
      // Write a couple of batches
      for (int i = 0; i < 2; i++) {
        CompactorTestUtil.writeBatch(conf, dbName, tableName, false, false);
      }

      // Start a third batch, but don't close it.
      connection = CompactorTestUtil.writeBatch(conf, dbName, tableName, false, true);

      // Now, compact
      CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);

      // Find the location of the table
      IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
      Table table = metaStoreClient.getTable(dbName, tableName);
      FileSystem fs = FileSystem.get(conf);
      Assert.assertEquals("Delta names does not match", Arrays
          .asList("delta_0000001_0000002", "delta_0000001_0000005_v0000009", "delta_0000003_0000004",
              "delta_0000005_0000006"), CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
      CompactorTestUtil.checkExpectedTxnsPresent(null,
          new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000005_v0000009")}, "a,b", "int:string",
          0, 1L, 4L, null, 1);

    } finally {
      if (connection != null) {
        connection.close();
      }
    }
  }

  @Test
  public void testMinorCompactionWhileStreamingAfterAbort() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(a INT, b STRING) " + " CLUSTERED BY(a) INTO 1 BUCKETS"
        + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')", driver);
    CompactorTestUtil.runStreamingAPI(conf, dbName, tableName, Lists
        .newArrayList(new CompactorTestUtil.StreamingConnectionOption(false, false),
            new CompactorTestUtil.StreamingConnectionOption(false, false),
            new CompactorTestUtil.StreamingConnectionOption(true, false)));
    // Now, compact
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    Assert.assertEquals("Delta names does not match",
        Arrays.asList("delta_0000001_0000002", "delta_0000001_0000006_v0000009", "delta_0000003_0000004"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
    CompactorTestUtil.checkExpectedTxnsPresent(null,
        new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000006_v0000009")}, "a,b", "int:string", 0,
        1L, 4L, Lists.newArrayList(5, 6), 1);
  }

  @Test
  public void testMinorCompactionWhileStreamingWithAbort() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver(
        "CREATE TABLE " + tableName + "(a INT, b STRING) " + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')",
        driver);
    CompactorTestUtil.runStreamingAPI(conf, dbName, tableName, Lists
        .newArrayList(new CompactorTestUtil.StreamingConnectionOption(false, false),
            new CompactorTestUtil.StreamingConnectionOption(true, false),
            new CompactorTestUtil.StreamingConnectionOption(false, false)));
    // Now, copact
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    Assert.assertEquals("Delta names does not match",
        Arrays.asList("delta_0000001_0000002", "delta_0000001_0000006_v0000009", "delta_0000005_0000006"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
    CompactorTestUtil.checkExpectedTxnsPresent(null,
        new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000006_v0000009")}, "a,b", "int:string", 0,
        1L, 6L, Lists.newArrayList(3, 4), 1);
  }

  @Test
  public void testMinorCompactionWhileStreamingWithAbortInMiddle() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver(
        "CREATE TABLE " + tableName + "(a INT, b STRING) " + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')",
        driver);
    StrictDelimitedInputWriter writer = StrictDelimitedInputWriter.newBuilder().withFieldDelimiter(',').build();
    StreamingConnection connection = HiveStreamingConnection.newBuilder().withDatabase(dbName).withTable(tableName)
        .withAgentInfo("UT_" + Thread.currentThread().getName()).withHiveConf(conf).withRecordWriter(writer).connect();
    connection.beginTransaction();
    connection.write("50,Kiev".getBytes());
    connection.write("51,St. Petersburg".getBytes());
    connection.write("52,Boston".getBytes());
    connection.commitTransaction();
    connection.beginTransaction();
    connection.write("60,Budapest".getBytes());
    connection.abortTransaction();
    connection.beginTransaction();
    connection.write("71,Szeged".getBytes());
    connection.write("72,Debrecen".getBytes());
    connection.commitTransaction();
    connection.close();
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    CompactorTestUtil.runCleaner(conf);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    Assert.assertEquals("Delta names does not match", Collections.singletonList("delta_0000001_0000003_v0000006"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
    CompactorTestUtil.checkExpectedTxnsPresent(null,
        new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000003_v0000006")}, "a,b", "int:string", 0,
        1L, 3L, Lists.newArrayList(2), 1);
  }

  @Test
  public void testMajorCompactionAfterMinor() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify delta directories after compaction
    Assert.assertEquals("Delta directories does not match after minor compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    Assert.assertEquals("Delete delta directories does not match after minor compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Insert another round of test data
    dataProvider.insertTestData(tableName);
    expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Verify base directory after compaction
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000010_v0000029"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Verify all contents
    actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test
  public void testMinorCompactionAfterMajor() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify base directory after compaction
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Insert another round of test data
    dataProvider.insertTestData(tableName);
    expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Verify base directory after compaction
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    Assert.assertEquals("Delta directories do not match after major compaction",
        Collections.singletonList("delta_0000001_0000010_v0000020"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify all contents
    actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test
  public void testMinorCompactionWhileStreamingWithSplitUpdate() throws Exception {
    Assume.assumeTrue(runsOnTez);
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(a INT, b STRING) " + " CLUSTERED BY(a) INTO 1 BUCKETS"
        + " STORED AS ORC  TBLPROPERTIES ('transactional'='true'," + "'transactional_properties'='default')", driver);
    StreamingConnection connection = null;
    // Write a couple of batches
    try {
      for (int i = 0; i < 2; i++) {
        CompactorTestUtil.writeBatch(conf, dbName, tableName, false, false);
      }
      // Start a third batch, but don't close it.
      connection = CompactorTestUtil.writeBatch(conf, dbName, tableName, false, true);
      // Now, compact
      CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
      // Find the location of the table
      IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
      Table table = metaStoreClient.getTable(dbName, tableName);
      FileSystem fs = FileSystem.get(conf);
      Assert.assertEquals("Delta names does not match", Arrays
          .asList("delta_0000001_0000002", "delta_0000001_0000005_v0000009", "delta_0000003_0000004",
              "delta_0000005_0000006"), CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
      CompactorTestUtil.checkExpectedTxnsPresent(null,
          new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000005_v0000009")}, "a,b", "int:string",
          0, 1L, 4L, null, 1);
      //Assert that we have no delete deltas if there are no input delete events.
      Assert.assertEquals(0,
          CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null).size());
    } finally {
      if (connection != null) {
        connection.close();
      }
    }

  }

  @Test
  public void testCompactionWithSchemaEvolutionAndBuckets() throws Exception {
    String dbName = "default";
    String tblName = "testCompactionWithSchemaEvolutionAndBuckets";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create transactional table " + tblName
        + " (a int, b int) partitioned by(ds string) clustered by (a) into 2 buckets"
        + " stored as ORC TBLPROPERTIES('bucketing_version'='2', 'transactional'='true',"
        + " 'transactional_properties'='default')", driver);
    // Insert some data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(1,2,'today'),(1,3,'today'),(1,4,'yesterday'),(2,2,'yesterday'),(2,3,'today'),(2,4,'today')",
        driver);
    // Add a new column
    executeStatementOnDriver("alter table " + tblName + " add columns(c int)", driver);
    // Insert more data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(3,2,1000,'yesterday'),(3,3,1001,'today'),(3,4,1002,'yesterday'),(4,2,1003,'today'),"
        + "(4,3,1004,'yesterday'),(4,4,1005,'today')", driver);
    executeStatementOnDriver("delete from " + tblName + " where b = 2", driver);

    List<String> expectedRsBucket0PtnToday = new ArrayList<>();
    expectedRsBucket0PtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":0}\t2\t3\tNULL\ttoday");
    expectedRsBucket0PtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t4\tNULL\ttoday");
    expectedRsBucket0PtnToday.add("{\"writeid\":3,\"bucketid\":536870912,\"rowid\":0}\t3\t3\t1001\ttoday");
    List<String> expectedRsBucket1PtnToday = new ArrayList<>();
    expectedRsBucket1PtnToday.add("{\"writeid\":1,\"bucketid\":536936448,\"rowid\":1}\t1\t3\tNULL\ttoday");
    expectedRsBucket1PtnToday.add("{\"writeid\":3,\"bucketid\":536936448,\"rowid\":1}\t4\t4\t1005\ttoday");
    // Bucket 0, partition 'today'
    List<String> rsBucket0PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536870912 and ds='today' order by a,b", driver);
    // Bucket 1, partition 'today'
    List<String> rsBucket1PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536936448 and ds='today' order by a,b", driver);
    if (runsOnTez) {
      Assert.assertEquals("pre-compaction read", expectedRsBucket0PtnToday, rsBucket0PtnToday);
      Assert.assertEquals("pre-compaction read", expectedRsBucket1PtnToday, rsBucket1PtnToday);
    } else {
      // MR sometimes inserts rows in the opposite order from Tez, so rowids won't match. so we
      // just check whether the bucket contents change during compaction.
      expectedRsBucket0PtnToday = rsBucket0PtnToday;
      expectedRsBucket1PtnToday = rsBucket1PtnToday;
    }

    //  Run major compaction and cleaner
    CompactorTestUtil
        .runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true, "ds=yesterday", "ds=today");
    CompactorTestUtil.runCleaner(conf);

    // Bucket 0, partition 'today'
    List<String> rsCompactBucket0PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536870912 and ds='today' order by a,b", driver);
    Assert.assertEquals("compacted read", expectedRsBucket0PtnToday, rsCompactBucket0PtnToday);
    // Bucket 1, partition 'today'
    List<String> rsCompactBucket1PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536936448 and ds='today' order by a,b", driver);
    Assert.assertEquals("compacted read", expectedRsBucket1PtnToday, rsCompactBucket1PtnToday);
    // Clean up
    executeStatementOnDriver("drop table " + tblName, driver);
  }

  @Test
  public void testCompactionWithSchemaEvolutionNoBucketsMultipleReducers() throws Exception {
    HiveConf hiveConf = new HiveConf(conf);
    hiveConf.setIntVar(HiveConf.ConfVars.MAXREDUCERS, 2);
    hiveConf.setIntVar(HiveConf.ConfVars.HADOOPNUMREDUCERS, 2);
    driver = DriverFactory.newDriver(hiveConf);
    String dbName = "default";
    String tblName = "testCompactionWithSchemaEvolutionNoBucketsMultipleReducers";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create transactional table " + tblName + " (a int, b int) partitioned by(ds string)"
        + " stored as ORC TBLPROPERTIES('transactional'='true'," + " 'transactional_properties'='default')", driver);
    // Insert some data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(1,2,'today'),(1,3,'today'),(1,4,'yesterday'),(2,2,'yesterday'),(2,3,'today'),(2,4,'today')",
        driver);
    // Add a new column
    executeStatementOnDriver("alter table " + tblName + " add columns(c int)", driver);
    // Insert more data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(3,2,1000,'yesterday'),(3,3,1001,'today'),(3,4,1002,'yesterday'),(4,2,1003,'today'),"
        + "(4,3,1004,'yesterday'),(4,4,1005,'today')", driver);
    executeStatementOnDriver("delete from " + tblName + " where b = 2", driver);
    //  Run major compaction and cleaner
    CompactorTestUtil
        .runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true, "ds=yesterday", "ds=today");
    CompactorTestUtil.runCleaner(hiveConf);
    List<String> expectedRsPtnToday = new ArrayList<>();
    expectedRsPtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t1\t3\tNULL\ttoday");
    expectedRsPtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t3\tNULL\ttoday");
    expectedRsPtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":3}\t2\t4\tNULL\ttoday");
    expectedRsPtnToday.add("{\"writeid\":3,\"bucketid\":536870912,\"rowid\":0}\t3\t3\t1001\ttoday");
    expectedRsPtnToday.add("{\"writeid\":3,\"bucketid\":536870912,\"rowid\":2}\t4\t4\t1005\ttoday");
    List<String> expectedRsPtnYesterday = new ArrayList<>();
    expectedRsPtnYesterday.add("{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t1\t4\tNULL\tyesterday");
    expectedRsPtnYesterday.add("{\"writeid\":3,\"bucketid\":536936448,\"rowid\":1}\t3\t4\t1002\tyesterday");
    expectedRsPtnYesterday.add("{\"writeid\":3,\"bucketid\":536936448,\"rowid\":2}\t4\t3\t1004\tyesterday");
    // Partition 'today'
    List<String> rsCompactPtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  " + tblName
        + " where ds='today'", driver);
    Assert.assertEquals("compacted read", expectedRsPtnToday, rsCompactPtnToday);
    // Partition 'yesterday'
    List<String> rsCompactPtnYesterday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  " + tblName
        + " where ds='yesterday'", driver);
    Assert.assertEquals("compacted read", expectedRsPtnYesterday, rsCompactPtnYesterday);
    // Clean up
    executeStatementOnDriver("drop table " + tblName, driver);
  }

  @Test public void testMajorCompactionDb() throws Exception {
    testCompactionDb(CompactionType.MAJOR, "base_0000005_v0000011");
  }

  @Test public void testMinorCompactionDb() throws Exception {
    testCompactionDb(CompactionType.MINOR, "delta_0000001_0000005_v0000011");
  }

  /**
   * Make sure db is specified in compaction queries.
   */
  private void testCompactionDb(CompactionType compactionType, String resultDirName)
      throws Exception {
    String dbName = "myDb";
    String tableName = "testCompactionDb";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createDb(dbName);
    dataProvider.createFullAcidTable(dbName, tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(dbName, tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(dbName, tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, compactionType, true);
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Verify directories after compaction
    PathFilter pathFilter = compactionType == CompactionType.MAJOR ? AcidUtils.baseFileFilter :
        AcidUtils.deltaFileFilter;
    Assert.assertEquals("Result directory does not match after " + compactionType.name()
            + " compaction", Collections.singletonList(resultDirName),
        CompactorTestUtil.getBaseOrDeltaNames(fs, pathFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(dbName, tableName);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test public void testVectorizationOff() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.HIVE_VECTORIZATION_ENABLED, false);
    testMinorCompactionAfterMajor();
  }

  /**
   * Verify that the expected number of transactions have run, and their state is "succeeded".
   *
   * @param expectedSuccessfulCompactions number of compactions already run
   * @throws MetaException
   */
  private void verifySuccessfulCompaction(int expectedSuccessfulCompactions) throws MetaException {
    List<ShowCompactResponseElement> compacts =
        TxnUtils.getTxnStore(conf).showCompact(new ShowCompactRequest()).getCompacts();
    Assert.assertEquals("Completed compaction queue must contain " + expectedSuccessfulCompactions + " element(s)",
        expectedSuccessfulCompactions, compacts.size());
    compacts.forEach(
        c -> Assert.assertEquals("Compaction state is not succeeded", "succeeded", c.getState()));
  }

  /**
   * Read file, and
   * 1. make sure that the bucket property in each row matches the file name.
   * For example, if the bucketId is 0, we check file bucket_00000 to make sure that the third
   * column contains only the value 536870912.
   * 2. make sure that rowIds are in ascending order
   * @param fs file system
   * @param path where to look for the bucket file
   * @param bucketId bucket Id to check, e.g. 0.
   */
  private void checkBucketIdAndRowIdInAcidFile(FileSystem fs, Path path, int bucketId) throws IOException {
    Path bucketFilePath = AcidUtils.createBucketFile(path, bucketId);
    Reader orcReader = OrcFile.createReader(bucketFilePath,
        OrcFile.readerOptions(fs.getConf()).filesystem(fs));
    TypeDescription schema = orcReader.getSchema();
    try (RecordReader rows = orcReader.rows()) {
      VectorizedRowBatch batch = schema.createRowBatch();
      rows.nextBatch(batch);
      // check that bucket property in each row matches the bucket in the file name
      long[] bucketIdVector = ((LongColumnVector) batch.cols[2]).vector;
      for (int i = 0; i < batch.count(); i++) {
        Assert.assertEquals(bucketId, decodeBucketProperty(bucketIdVector[i]));
      }
      // check that writeIds, then rowIds are sorted in ascending order
      long[] writeIdVector = ((LongColumnVector) batch.cols[1]).vector;
      long[] rowIdVector = ((LongColumnVector) batch.cols[3]).vector;
      long writeId = writeIdVector[0];
      long rowId = 0;
      for (int i = 0; i < batch.count(); i++) {
        long currentWriteId = writeIdVector[i];
        long currentRowId = rowIdVector[i];
        if (writeId == writeIdVector[i]) {
          Assert.assertTrue(rowId <= currentRowId);
          rowId = currentRowId;
        } else {
          Assert.assertTrue(writeId < currentWriteId);
          writeId = currentWriteId;
          rowId = 0;
        }
      }
    }
  }

  /**
   * Couldn't find any way to get the bucket property from BucketCodec, so just reverse
   * engineered the encoding. The actual bucketId is represented by bits 2-11 of 29 bits
   */
  private int decodeBucketProperty(long bucketCodec) {
    return (int) ((bucketCodec >> 16) & (0xFFF));
  }

  /**
   * Tests whether hive.llap.io.etl.skip.format config is handled properly whenever QueryCompactor#runCompactionQueries
   * is invoked.
   * @throws Exception
   */
  @Test
  public void testLlapCacheOffDuringCompaction() throws Exception {
    // Setup
    QueryCompactor qc = new QueryCompactor() {
      @Override
      void runCompaction(HiveConf hiveConf, Table table, Partition partition, StorageDescriptor storageDescriptor,
                         ValidWriteIdList writeIds, CompactionInfo compactionInfo) throws IOException {
      }

      @Override
      protected void commitCompaction(String dest, String tmpTableName, HiveConf conf, ValidWriteIdList actualWriteIds,
                                      long compactorTxnId) throws IOException, HiveException {
      }
    };
    StorageDescriptor sdMock = mock(StorageDescriptor.class);
    doAnswer(invocationOnMock -> {
      return null;
    }).when(sdMock).getLocation();
    List<String> emptyQueries = new ArrayList<>();
    HiveConf hiveConf = new HiveConf();
    hiveConf.set(ValidTxnList.VALID_TXNS_KEY, "8:9223372036854775807::");

    // Check for default case.
    qc.runCompactionQueries(hiveConf, null, sdMock, null, null, null, emptyQueries, emptyQueries, emptyQueries);
    Assert.assertEquals("all", hiveConf.getVar(HiveConf.ConfVars.LLAP_IO_ETL_SKIP_FORMAT));

    // Check for case where  hive.llap.io.etl.skip.format is explicitly set to none - as to always use cache.
    hiveConf.setVar(HiveConf.ConfVars.LLAP_IO_ETL_SKIP_FORMAT, "none");
    qc.runCompactionQueries(hiveConf, null, sdMock, null, null, null, emptyQueries, emptyQueries, emptyQueries);
    Assert.assertEquals("none", hiveConf.getVar(HiveConf.ConfVars.LLAP_IO_ETL_SKIP_FORMAT));
  }
}
