// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.kudu.backup

import java.nio.file.Files
import java.util

import com.google.common.base.Objects
import org.apache.commons.io.FileUtils
import org.apache.kudu.client.PartitionSchema.HashBucketSchema
import org.apache.kudu.client.CreateTableOptions
import org.apache.kudu.client.KuduTable
import org.apache.kudu.client.PartialRow
import org.apache.kudu.client.PartitionSchema
import org.apache.kudu.ColumnSchema
import org.apache.kudu.Schema
import org.apache.kudu.Type
import org.apache.kudu.spark.kudu._
import org.apache.kudu.test.RandomUtils
import org.apache.kudu.util.DataGenerator.DataGeneratorBuilder
import org.apache.kudu.util.DataGenerator
import org.apache.kudu.util.DecimalUtil
import org.apache.kudu.util.HybridTimeUtil
import org.apache.kudu.util.SchemaGenerator.SchemaGeneratorBuilder
import org.junit.Assert._
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Random

class TestKuduBackup extends KuduTestSuite {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var random: util.Random = _

  @Before
  def setUp(): Unit = {
    random = RandomUtils.getRandom
  }

  @Ignore("TODO(laiyingchun): KuduRow doesn't support serialization for performance problem")
  @Test
  def testSimpleBackupAndRestore() {
    insertRows(table, 100) // Insert data into the default test table.

    backupAndRestore(tableName)

    val rdd =
      kuduContext.kuduRDD(ss.sparkContext, s"$tableName-restore", List("key"))
    assert(rdd.collect.length == 100)

    val tA = kuduClient.openTable(tableName)
    val tB = kuduClient.openTable(s"$tableName-restore")
    assertEquals(tA.getNumReplicas, tB.getNumReplicas)
    assertTrue(schemasMatch(tA.getSchema, tB.getSchema))
    assertTrue(partitionSchemasMatch(tA.getPartitionSchema, tB.getPartitionSchema))
  }

  @Test
  def testSimpleBackupAndRestoreWithSpecialCharacters() {
    // Use an Impala-style table name to verify url encoding/decoding of the table name works.
    val impalaTableName = "impala::default.test"

    val tableOptions = new CreateTableOptions()
      .setRangePartitionColumns(List("key").asJava)
      .setNumReplicas(1)

    kuduClient.createTable(impalaTableName, simpleSchema, tableOptions)

    backupAndRestore(impalaTableName)

    val rdd = kuduContext
      .kuduRDD(ss.sparkContext, s"$impalaTableName-restore", List("key"))
    // Only verifying the file contents could be read, the contents are expected to be empty.
    assert(rdd.isEmpty())
  }

  @Ignore("TODO(laiyingchun): KuduRow doesn't support serialization for performance problem")
  @Test
  def testRandomBackupAndRestore() {
    val table = createRandomTable()
    val tableName = table.getName
    loadRandomData(table)

    backupAndRestore(tableName)

    val backupRows = kuduContext.kuduRDD(ss.sparkContext, s"$tableName").collect
    val restoreRows =
      kuduContext.kuduRDD(ss.sparkContext, s"$tableName-restore").collect
    assertEquals(backupRows.length, restoreRows.length)

    val tA = kuduClient.openTable(tableName)
    val tB = kuduClient.openTable(s"$tableName-restore")
    assertEquals(tA.getNumReplicas, tB.getNumReplicas)
    assertTrue(schemasMatch(tA.getSchema, tB.getSchema))
    assertTrue(partitionSchemasMatch(tA.getPartitionSchema, tB.getPartitionSchema))
  }

  @Test
  def testBackupAndRestoreMultipleTables() {
    val numRows = 1
    val table1Name = "table1"
    val table2Name = "table2"

    val table1 = kuduClient.createTable(table1Name, schema, tableOptions)
    val table2 = kuduClient.createTable(table2Name, schema, tableOptions)

    insertRows(table1, numRows)
    insertRows(table2, numRows)

    backupAndRestore(table1Name, table2Name)

    val rdd1 =
      kuduContext.kuduRDD(ss.sparkContext, s"$table1Name-restore", List("key"))
    assertResult(numRows)(rdd1.count())

    val rdd2 =
      kuduContext.kuduRDD(ss.sparkContext, s"$table2Name-restore", List("key"))
    assertResult(numRows)(rdd2.count())
  }

  // TODO: Move to a Schema equals/equivalent method.
  def schemasMatch(before: Schema, after: Schema): Boolean = {
    if (before eq after) return true
    if (before.getColumns.size != after.getColumns.size) return false
    (0 until before.getColumns.size).forall { i =>
      columnsMatch(before.getColumnByIndex(i), after.getColumnByIndex(i))
    }
  }

  // TODO: Move to a ColumnSchema equals/equivalent method.
  def columnsMatch(before: ColumnSchema, after: ColumnSchema): Boolean = {
    if (before eq after) return true
    Objects.equal(before.getName, after.getName) &&
    Objects.equal(before.getType, after.getType) &&
    Objects.equal(before.isKey, after.isKey) &&
    Objects.equal(before.isNullable, after.isNullable) &&
    defaultValuesMatch(before.getDefaultValue, after.getDefaultValue) &&
    Objects.equal(before.getDesiredBlockSize, after.getDesiredBlockSize) &&
    Objects.equal(before.getEncoding, after.getEncoding) &&
    Objects
      .equal(before.getCompressionAlgorithm, after.getCompressionAlgorithm) &&
    Objects.equal(before.getTypeAttributes, after.getTypeAttributes)
  }

  // Special handling because default values can be a byte array which is not
  // handled by Guava's Objects.equals.
  // See https://github.com/google/guava/issues/1425
  def defaultValuesMatch(before: Any, after: Any): Boolean = {
    if (before.isInstanceOf[Array[Byte]] && after.isInstanceOf[Array[Byte]]) {
      util.Objects.deepEquals(before, after)
    } else {
      Objects.equal(before, after)
    }
  }

  // TODO: Move to a PartitionSchema equals/equivalent method.
  def partitionSchemasMatch(before: PartitionSchema, after: PartitionSchema): Boolean = {
    if (before eq after) return true
    val beforeBuckets = before.getHashBucketSchemas.asScala
    val afterBuckets = after.getHashBucketSchemas.asScala
    if (beforeBuckets.size != afterBuckets.size) return false
    val hashBucketsMatch = (0 until beforeBuckets.size).forall { i =>
      HashBucketSchemasMatch(beforeBuckets(i), afterBuckets(i))
    }
    hashBucketsMatch &&
    Objects.equal(before.getRangeSchema.getColumnIds, after.getRangeSchema.getColumnIds)
  }

  def HashBucketSchemasMatch(before: HashBucketSchema, after: HashBucketSchema): Boolean = {
    if (before eq after) return true
    Objects.equal(before.getColumnIds, after.getColumnIds) &&
    Objects.equal(before.getNumBuckets, after.getNumBuckets) &&
    Objects.equal(before.getSeed, after.getSeed)
  }

  def createRandomTable(): KuduTable = {
    val columnCount = random.nextInt(50) + 1 // At least one column.
    val keyColumnCount = random.nextInt(columnCount) + 1 // At least one key.
    val schemaGenerator = new SchemaGeneratorBuilder()
      .random(random)
      .columnCount(columnCount)
      .keyColumnCount(keyColumnCount)
      .build()
    val schema = schemaGenerator.randomSchema()
    val options = schemaGenerator.randomCreateTableOptions(schema)
    options.setNumReplicas(1)
    val name = s"random-${System.currentTimeMillis()}"
    kuduClient.createTable(name, schema, options)
  }

  // TODO: Add updates and deletes when incremental backups are supported.
  def loadRandomData(table: KuduTable): IndexedSeq[PartialRow] = {
    val kuduSession = kuduClient.newSession()
    val dataGenerator = new DataGeneratorBuilder()
      .random(random)
      .build()
    val rowCount = random.nextInt(200)
    (0 to rowCount).map { i =>
      val upsert = table.newUpsert()
      val row = upsert.getRow
      dataGenerator.randomizeRow(row)
      kuduSession.apply(upsert)
      row
    }
  }

  def backupAndRestore(tableNames: String*): Unit = {
    val dir = Files.createTempDirectory("backup")
    val path = dir.toUri.toString
    val nowMs = System.currentTimeMillis()

    // Log the timestamps to simplify flaky debugging.
    log.info(s"nowMs: ${System.currentTimeMillis()}")
    val hts = HybridTimeUtil.HTTimestampToPhysicalAndLogical(kuduClient.getLastPropagatedTimestamp)
    log.info(s"propagated physicalMicros: ${hts(0)}")
    log.info(s"propagated logical: ${hts(1)}")

    // Add one millisecond to our target snapshot time. This will ensure we read all of the records
    // in the backup and prevent flaky off-by-one errors. The underlying reason for adding 1 ms is
    // that we pass the timestamp in millisecond granularity but the snapshot time has microsecond
    // granularity. This means if the test runs fast enough that data is inserted with the same
    // millisecond value as nowMs (after truncating the micros) the records inserted in the
    // microseconds after truncation could be unread.
    val backupOptions =
      new KuduBackupOptions(tableNames, path, harness.getMasterAddressesAsString, nowMs + 1)
    KuduBackup.run(backupOptions, ss)

    val restoreOptions =
      new KuduRestoreOptions(tableNames, path, harness.getMasterAddressesAsString)
    KuduRestore.run(restoreOptions, ss)

    FileUtils.deleteDirectory(dir.toFile)
  }
}
