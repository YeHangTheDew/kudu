/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kudu.spark.kudu

import java.security.AccessController
import java.security.PrivilegedAction
import java.sql.Timestamp
import javax.security.auth.Subject
import javax.security.auth.login.AppConfigurationEntry
import javax.security.auth.login.Configuration
import javax.security.auth.login.LoginContext

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.hadoop.util.ShutdownHookManager
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.util.AccumulatorV2
import org.apache.spark.util.LongAccumulator
import org.apache.yetus.audience.InterfaceAudience
import org.apache.yetus.audience.InterfaceStability
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.kudu.client.SessionConfiguration.FlushMode
import org.apache.kudu.client._
import org.apache.kudu.spark.kudu.SparkUtil._
import org.apache.kudu.Schema
import org.apache.kudu.Type

/**
 * KuduContext is a serializable container for Kudu client connections.
 *
 * If a Kudu client connection is needed as part of a Spark application, a
 * [[KuduContext]] should be created in the driver, and shared with executors
 * as a serializable field.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
class KuduContext(val kuduMaster: String, sc: SparkContext, val socketReadTimeoutMs: Option[Long])
    extends Serializable {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def this(kuduMaster: String, sc: SparkContext) = this(kuduMaster, sc, None)

  /**
   * A collection of accumulator metrics describing the usage of a KuduContext.
   */
  private[kudu] val numInserts: LongAccumulator = sc.longAccumulator("kudu.num_inserts")
  private[kudu] val numUpserts: LongAccumulator = sc.longAccumulator("kudu.num_upserts")
  private[kudu] val numUpdates: LongAccumulator = sc.longAccumulator("kudu.num_updates")
  private[kudu] val numDeletes: LongAccumulator = sc.longAccumulator("kudu.num_deletes")

  // Increments the appropriate metric given an OperationType and a count.
  private def addForOperation(count: Long, opType: OperationType): Unit = {
    opType match {
      case Insert => numInserts.add(count)
      case Upsert => numUpserts.add(count)
      case Update => numUpdates.add(count)
      case Delete => numDeletes.add(count)
    }
  }

  /**
   * TimestampAccumulator accumulates the maximum value of client's
   * propagated timestamp of all executors and can only read by the
   * driver.
   */
  private[kudu] class TimestampAccumulator(var timestamp: Long = 0L)
      extends AccumulatorV2[Long, Long] {
    override def isZero: Boolean = {
      timestamp == 0
    }

    override def copy(): AccumulatorV2[Long, Long] = {
      new TimestampAccumulator(timestamp)
    }

    override def reset(): Unit = {
      timestamp = 0L
    }

    override def add(v: Long): Unit = {
      timestamp = timestamp.max(v)
    }

    override def merge(other: AccumulatorV2[Long, Long]): Unit = {
      timestamp = timestamp.max(other.value)

      // Since for every write/scan operation, each executor holds its own copy of
      // client. We need to update the propagated timestamp on the driver based on
      // the latest propagated timestamp from all executors through TimestampAccumulator.
      syncClient.updateLastPropagatedTimestamp(timestampAccumulator.value)
    }

    override def value: Long = timestamp
  }

  val timestampAccumulator = new TimestampAccumulator()
  sc.register(timestampAccumulator)

  val durationHistogram = new HdrHistogramAccumulator()
  sc.register(durationHistogram, "kudu.write_duration")

  @Deprecated()
  def this(kuduMaster: String) {
    this(kuduMaster, new SparkContext())
  }

  @transient lazy val syncClient: KuduClient = asyncClient.syncClient()

  @transient lazy val asyncClient: AsyncKuduClient = {
    val c = KuduClientCache.getAsyncClient(kuduMaster, socketReadTimeoutMs)
    if (authnCredentials != null) {
      c.importAuthenticationCredentials(authnCredentials)
    }
    c
  }

  // Visible for testing.
  private[kudu] val authnCredentials: Array[Byte] = {
    Subject.doAs(KuduContext.getSubject(sc), new PrivilegedAction[Array[Byte]] {
      override def run(): Array[Byte] =
        syncClient.exportAuthenticationCredentials()
    })
  }

  /**
   * Create an RDD from a Kudu table.
   *
   * @param tableName          table to read from
   * @param columnProjection   list of columns to read. Not specifying this at all
   *                           (i.e. setting to null) or setting to the special
   *                           string '*' means to project all columns
   * @return a new RDD that maps over the given table for the selected columns
   */
  def kuduRDD(
      sc: SparkContext,
      tableName: String,
      columnProjection: Seq[String] = Nil,
      options: KuduReadOptions = KuduReadOptions()): RDD[Row] = {
    new KuduRDD(
      this,
      syncClient.openTable(tableName),
      columnProjection.toArray,
      Array(),
      options,
      sc)
  }

  /**
   * Check if kudu table already exists
   *
   * @param tableName name of table to check
   * @return true if table exists, false if table does not exist
   */
  def tableExists(tableName: String): Boolean =
    syncClient.tableExists(tableName)

  /**
   * Delete kudu table
   *
   * @param tableName name of table to delete
   * @return DeleteTableResponse
   */
  def deleteTable(tableName: String): DeleteTableResponse =
    syncClient.deleteTable(tableName)

  /**
   * Creates a kudu table for the given schema. Partitioning can be specified through options.
   *
   * @param tableName table to create
   * @param schema struct schema of table
   * @param keys primary keys of the table
   * @param options replication and partitioning options for the table
   * @return the KuduTable that was created
   */
  def createTable(
      tableName: String,
      schema: StructType,
      keys: Seq[String],
      options: CreateTableOptions): KuduTable = {
    val kuduSchema = createSchema(schema, keys)
    createTable(tableName, kuduSchema, options)
  }

  /**
   * Creates a kudu table for the given schema. Partitioning can be specified through options.
   *
   * @param tableName table to create
   * @param schema schema of table
   * @param options replication and partitioning options for the table
   * @return the KuduTable that was created
   */
  def createTable(tableName: String, schema: Schema, options: CreateTableOptions): KuduTable = {
    syncClient.createTable(tableName, schema, options)
  }

  /**
   * Creates a kudu schema for the given struct schema.
   *
   * @param schema struct schema of table
   * @param keys primary keys of the table
   * @return the Kudu schema
   */
  def createSchema(schema: StructType, keys: Seq[String]): Schema = {
    kuduSchema(schema, keys)
  }

  /** Map Spark SQL type to Kudu type */
  def kuduType(dt: DataType): Type = {
    sparkTypeToKuduType(dt)
  }

  /**
   * Inserts the rows of a [[DataFrame]] into a Kudu table.
   *
   * @param data the data to insert
   * @param tableName the Kudu table to insert into
   * @param writeOptions the Kudu write options
   */
  def insertRows(
      data: DataFrame,
      tableName: String,
      writeOptions: KuduWriteOptions = new KuduWriteOptions): Unit = {
    log.info(s"inserting into table '$tableName'")
    writeRows(data, tableName, Insert, writeOptions)
    log.info(s"inserted ${numInserts.value} rows into table '$tableName'")
  }

  /**
   * Inserts the rows of a [[DataFrame]] into a Kudu table, ignoring any new
   * rows that have a primary key conflict with existing rows.
   *
   * This function call is equivalent to the following, which is preferred:
   * {{{
   * insertRows(data, tableName, new KuduWriteOptions(ignoreDuplicateRowErrors = true))
   * }}}
   *
   * @param data the data to insert into Kudu
   * @param tableName the Kudu table to insert into
   */
  @deprecated(
    "Use KuduContext.insertRows(data, tableName, new KuduWriteOptions(ignoreDuplicateRowErrors = true))")
  def insertIgnoreRows(data: DataFrame, tableName: String): Unit = {
    val writeOptions = KuduWriteOptions(ignoreDuplicateRowErrors = true)
    log.info(s"inserting into table '$tableName'")
    writeRows(data, tableName, Insert, writeOptions)
    log.info(s"inserted ${numInserts.value} rows into table '$tableName'")
  }

  /**
   * Upserts the rows of a [[DataFrame]] into a Kudu table.
   *
   * @param data the data to upsert into Kudu
   * @param tableName the Kudu table to upsert into
   * @param writeOptions the Kudu write options
   */
  def upsertRows(
      data: DataFrame,
      tableName: String,
      writeOptions: KuduWriteOptions = new KuduWriteOptions): Unit = {
    log.info(s"upserting into table '$tableName'")
    writeRows(data, tableName, Upsert, writeOptions)
    log.info(s"upserted ${numUpserts.value} rows into table '$tableName'")
  }

  /**
   * Updates a Kudu table with the rows of a [[DataFrame]].
   *
   * @param data the data to update into Kudu
   * @param tableName the Kudu table to update
   * @param writeOptions the Kudu write options
   */
  def updateRows(
      data: DataFrame,
      tableName: String,
      writeOptions: KuduWriteOptions = new KuduWriteOptions): Unit = {
    log.info(s"updating rows in table '$tableName'")
    writeRows(data, tableName, Update, writeOptions)
    log.info(s"updated ${numUpdates.value} rows in table '$tableName'")
  }

  /**
   * Updates a Kudu table with the rows of a [[DataFrame]].
   *
   * @param data the data to update into Kudu
   * @param tableName the Kudu table to update
   * @param columns the key value to be set
   */
  def updateRows(data: DataFrame, tableName: String, columns: Map[String, String]): Unit = {
    val schema = data.schema
    data.queryExecution.toRdd.foreachPartition(iterator => {
      val pendingErrors = updatePartitionRows(iterator, schema, tableName, columns)
      val errorCount = pendingErrors.getRowErrors.length
      if (errorCount > 0) {
        val errors = pendingErrors.getRowErrors.take(5).map(_.getErrorStatus).mkString
        throw new RuntimeException(
          s"failed to write $errorCount rows from DataFrame to Kudu; sample errors: $errors")
      }
    })
  }

  /**
   * Deletes the rows of a [[DataFrame]] from a Kudu table.
   *
   * @param data the data to delete from Kudu
   *             note that only the key columns should be specified for deletes
   * @param tableName The Kudu tabe to delete from
   * @param writeOptions the Kudu write options
   */
  def deleteRows(
      data: DataFrame,
      tableName: String,
      writeOptions: KuduWriteOptions = new KuduWriteOptions): Unit = {
    log.info(s"deleting rows from table '$tableName'")
    writeRows(data, tableName, Delete, writeOptions)
    log.info(s"deleted ${numDeletes.value} rows from table '$tableName'")
  }

  private[kudu] def writeRows(
      data: DataFrame,
      tableName: String,
      operation: OperationType,
      writeOptions: KuduWriteOptions = new KuduWriteOptions) {
    val schema = data.schema
    // Get the client's last propagated timestamp on the driver.
    val lastPropagatedTimestamp = syncClient.getLastPropagatedTimestamp
    data.queryExecution.toRdd.foreachPartition(iterator => {
      val pendingErrors = writePartitionRows(
        iterator,
        schema,
        tableName,
        operation,
        lastPropagatedTimestamp,
        writeOptions)
      val errorCount = pendingErrors.getRowErrors.length
      if (errorCount > 0) {
        val errors =
          pendingErrors.getRowErrors.take(5).map(_.getErrorStatus).mkString
        throw new RuntimeException(
          s"failed to write $errorCount rows from DataFrame to Kudu; sample errors: $errors")
      }
    })
    log.info(s"completed $operation ops: duration histogram: $durationHistogram")
  }

  private def writePartitionRows(
      rows: Iterator[InternalRow],
      schema: StructType,
      tableName: String,
      opType: OperationType,
      lastPropagatedTimestamp: Long,
      writeOptions: KuduWriteOptions): RowErrorsAndOverflowStatus = {
    // Since each executor has its own KuduClient, update executor's propagated timestamp
    // based on the last one on the driver.
    syncClient.updateLastPropagatedTimestamp(lastPropagatedTimestamp)
    val table: KuduTable = syncClient.openTable(tableName)
    val indices: Array[(Int, Int)] = schema.fields.zipWithIndex.map({
      case (field, sparkIdx) =>
        sparkIdx -> table.getSchema.getColumnIndex(field.name)
    })
    val session: KuduSession = syncClient.newSession
    session.setFlushMode(FlushMode.AUTO_FLUSH_BACKGROUND)
    session.setIgnoreAllDuplicateRows(writeOptions.ignoreDuplicateRowErrors)
    val typeConverter = CatalystTypeConverters.createToScalaConverter(schema)
    var numRows = 0
    log.info(s"applying operations of type '${opType.toString}' to table '$tableName'")
    val startTime = System.currentTimeMillis()
    try {
      for (internalRow <- rows) {
        val row = typeConverter(internalRow).asInstanceOf[Row]
        val operation = opType.operation(table)
        for ((sparkIdx, kuduIdx) <- indices) {
          if (operationType == Delete && !table.getSchema
              .getColumnByIndex(kuduIdx)
              .isKey) {} else if (row.isNullAt(sparkIdx)) {
            if (table.getSchema.getColumnByIndex(kuduIdx).isKey) {
              val key_name = table.getSchema.getColumnByIndex(kuduIdx).getName
              throw new IllegalArgumentException(
                s"Can't set primary key column '$key_name' to null")
            }
            if (!writeOptions.ignoreNull) operation.getRow.setNull(kuduIdx)
          } else {
            schema.fields(sparkIdx).dataType match {
              case DataTypes.StringType =>
                operation.getRow.addString(kuduIdx, row.getString(sparkIdx))
              case DataTypes.BinaryType =>
                operation.getRow
                  .addBinary(kuduIdx, row.getAs[Array[Byte]](sparkIdx))
              case DataTypes.BooleanType =>
                operation.getRow.addBoolean(kuduIdx, row.getBoolean(sparkIdx))
              case DataTypes.ByteType =>
                operation.getRow.addByte(kuduIdx, row.getByte(sparkIdx))
              case DataTypes.ShortType =>
                operation.getRow.addShort(kuduIdx, row.getShort(sparkIdx))
              case DataTypes.IntegerType =>
                operation.getRow.addInt(kuduIdx, row.getInt(sparkIdx))
              case DataTypes.LongType =>
                operation.getRow.addLong(kuduIdx, row.getLong(sparkIdx))
              case DataTypes.FloatType =>
                operation.getRow.addFloat(kuduIdx, row.getFloat(sparkIdx))
              case DataTypes.DoubleType =>
                operation.getRow.addDouble(kuduIdx, row.getDouble(sparkIdx))
              case DataTypes.TimestampType =>
                operation.getRow
                  .addTimestamp(kuduIdx, row.getTimestamp(sparkIdx))
              case DecimalType() =>
                operation.getRow.addDecimal(kuduIdx, row.getDecimal(sparkIdx))
              case t =>
                throw new IllegalArgumentException(s"No support for Spark SQL type $t")
            }
          }
        }
        session.apply(operation)
        numRows += 1
      }
    } finally {
      session.close()
      // Update timestampAccumulator with the client's last propagated
      // timestamp on each executor.
      timestampAccumulator.add(syncClient.getLastPropagatedTimestamp)
      addForOperation(numRows, opType)
      val elapsedTime = System.currentTimeMillis() - startTime
      durationHistogram.add(elapsedTime)
      log.info(s"applied $numRows ${opType}s to table '$tableName' in ${elapsedTime}ms")
    }
    session.getPendingErrors
  }

  private def updatePartitionRows(
      rows: Iterator[InternalRow],
      schema: StructType,
      tableName: String,
      columns: Map[String, String]): RowErrorsAndOverflowStatus = {
    val table: KuduTable = syncClient.openTable(tableName)
    val indices: Array[(Int, Int)] = schema.fields.zipWithIndex.map({
      case (field, sparkIdx) =>
        sparkIdx -> table.getSchema.getColumnIndex(field.name)
    })
    val session: KuduSession = syncClient.newSession
    session.setFlushMode(FlushMode.AUTO_FLUSH_BACKGROUND)
    val kuduTableSchema = table.getSchema
    val columnSettingMap = transformColumnSettings(kuduTableSchema, columns)
    val typeConverter = CatalystTypeConverters.createToScalaConverter(schema)
    try {
      for (internalRow <- rows) {
        val operation = Update.operation(table)
        val row = typeConverter(internalRow).asInstanceOf[Row]
        for ((sparkIdx, kuduIdx) <- indices) {
          val isKey = kuduTableSchema.getColumnByIndex(kuduIdx).isKey
          val isUpdate = columnSettingMap.contains(kuduIdx)
          val columnNeed = isKey || isUpdate
          if (columnNeed) {
            val value = columnSettingMap.get(kuduIdx)
            if (isUpdate && value.contains(null)) {
              operation.getRow.setNull(kuduIdx)
            } else {
              schema.fields(sparkIdx).dataType match {
                case DataTypes.StringType =>
                  operation.getRow.addString(
                    kuduIdx,
                    if (isKey) row.getString(sparkIdx) else value.get.asInstanceOf[String])
                case DataTypes.BinaryType =>
                  operation.getRow.addBinary(
                    kuduIdx,
                    if (isKey) row.getAs[Array[Byte]](sparkIdx)
                    else value.get.asInstanceOf[Array[Byte]])
                case DataTypes.BooleanType =>
                  operation.getRow.addBoolean(
                    kuduIdx,
                    if (isKey) row.getBoolean(sparkIdx) else value.get.asInstanceOf[Boolean])
                case DataTypes.ByteType =>
                  operation.getRow.addByte(
                    kuduIdx,
                    if (isKey) row.getByte(sparkIdx) else value.get.asInstanceOf[Byte])
                case DataTypes.ShortType =>
                  operation.getRow.addShort(
                    kuduIdx,
                    if (isKey) row.getShort(sparkIdx) else value.get.asInstanceOf[Short])
                case DataTypes.IntegerType =>
                  operation.getRow.addInt(
                    kuduIdx,
                    if (isKey) row.getInt(sparkIdx) else value.get.asInstanceOf[Int])
                case DataTypes.LongType =>
                  operation.getRow.addLong(
                    kuduIdx,
                    if (isKey) row.getLong(sparkIdx) else value.get.asInstanceOf[Long])
                case DataTypes.FloatType =>
                  operation.getRow.addFloat(
                    kuduIdx,
                    if (isKey) row.getFloat(sparkIdx) else value.get.asInstanceOf[Float])
                case DataTypes.DoubleType =>
                  operation.getRow.addDouble(
                    kuduIdx,
                    if (isKey) row.getDouble(sparkIdx) else value.get.asInstanceOf[Double])
                case DataTypes.TimestampType =>
                  operation.getRow.addTimestamp(
                    kuduIdx,
                    if (isKey) row.getTimestamp(sparkIdx) else value.get.asInstanceOf[Timestamp])
                case t => throw new IllegalArgumentException(s"No support for Spark SQL type $t")
              }
            }
          }
        }
        session.apply(operation)
      }
    } finally {
      session.close()
    }
    session.getPendingErrors
  }

  private def transformColumnSettings(
      schema: Schema,
      columns: Map[String, String]): Map[Int, Any] = {
    columns.map(e => {
      val value = if (e._2 == null) {
        null
      } else {
        schema.getColumn(e._1).getType match {
          case Type.STRING => e._2
          case Type.BINARY => e._2.getBytes
          case Type.BOOL => e._2.toBoolean
          case Type.INT8 => e._2.toByte
          case Type.INT16 => e._2.toShort
          case Type.INT32 => e._2.toInt
          case Type.INT64 => e._2.toLong
          case Type.FLOAT => e._2.toFloat
          case Type.DOUBLE => e._2.toDouble
          case Type.DECIMAL => e._2.toDouble
          case Type.UNIXTIME_MICROS => timestamp(e._2)
        }
      }
      (schema.getColumnIndex(e._1), value)
    })
  }

  private def timestamp(dateTime: String): Timestamp = {
    Timestamp.valueOf(dateTime)
  }
}

private object KuduContext {
  val Log: Logger = LoggerFactory.getLogger(classOf[KuduContext])

  /**
   * Returns a new Kerberos-authenticated [[Subject]] if the Spark context contains
   * principal and keytab options, otherwise returns the currently active subject.
   *
   * The keytab and principal options should be set when deploying a Spark
   * application in cluster mode with Yarn against a secure Kudu cluster. Spark
   * internally will grab HDFS and HBase delegation tokens (see
   * [[org.apache.spark.deploy.SparkSubmit]]), so we do something similar.
   *
   * This method can only be called on the driver, where the SparkContext is
   * available.
   *
   * @return A Kerberos-authenticated subject if the Spark context contains
   *         principal and keytab options, otherwise returns the currently
   *         active subject
   */
  private def getSubject(sc: SparkContext): Subject = {
    val subject = Subject.getSubject(AccessController.getContext)

    val principal =
      sc.getConf.getOption("spark.yarn.principal").getOrElse(return subject)
    val keytab =
      sc.getConf.getOption("spark.yarn.keytab").getOrElse(return subject)

    Log.info(s"Logging in as principal $principal with keytab $keytab")

    val conf = new Configuration {
      override def getAppConfigurationEntry(name: String): Array[AppConfigurationEntry] = {
        val options = Map(
          "principal" -> principal,
          "keyTab" -> keytab,
          "useKeyTab" -> "true",
          "useTicketCache" -> "false",
          "doNotPrompt" -> "true",
          "refreshKrb5Config" -> "true"
        )

        Array(
          new AppConfigurationEntry(
            "com.sun.security.auth.module.Krb5LoginModule",
            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
            options.asJava))
      }
    }

    val loginContext = new LoginContext("kudu-spark", new Subject(), null, conf)
    loginContext.login()
    loginContext.getSubject
  }
}

private object KuduClientCache {
  val Log: Logger = LoggerFactory.getLogger(KuduClientCache.getClass)

  private case class CacheKey(kuduMaster: String, socketReadTimeoutMs: Option[Long])
  private case class CacheValue(kuduClient: AsyncKuduClient, shutdownHookHandle: Runnable)

  /**
   * Set to
   * [[org.apache.spark.util.ShutdownHookManager.DEFAULT_SHUTDOWN_PRIORITY]].
   * The client instances are closed through the JVM shutdown hook
   * mechanism in order to make sure that any unflushed writes are cleaned up
   * properly. Spark has no shutdown notifications.
   */
  private val ShutdownHookPriority = 100

  private val clientCache = new mutable.HashMap[CacheKey, CacheValue]()

  // Visible for testing.
  private[kudu] def clearCacheForTests() = {
    clientCache.values.foreach {
      case cacheValue =>
        try {
          cacheValue.kuduClient.close()
        } catch {
          case e: Exception => Log.warn("Error while shutting down the test client", e);
        }

        // A client may only be closed once, so once we've close this client,
        // we mustn't close it again at shutdown time.
        ShutdownHookManager.get().removeShutdownHook(cacheValue.shutdownHookHandle)
    }
    clientCache.clear()
  }

  def getAsyncClient(kuduMaster: String, socketReadTimeoutMs: Option[Long]): AsyncKuduClient = {
    val cacheKey = CacheKey(kuduMaster, socketReadTimeoutMs)
    clientCache.synchronized {
      if (!clientCache.contains(cacheKey)) {
        val builder = new AsyncKuduClient.AsyncKuduClientBuilder(kuduMaster)
        socketReadTimeoutMs match {
          case Some(timeout) => builder.defaultSocketReadTimeoutMs(timeout)
          case None =>
        }

        val asyncClient = builder.build()
        val hookHandle = new Runnable {
          override def run(): Unit = asyncClient.close()
        }
        ShutdownHookManager.get().addShutdownHook(hookHandle, ShutdownHookPriority)
        val cacheValue = CacheValue(asyncClient, hookHandle)
        clientCache.put(cacheKey, cacheValue)
      }
      return clientCache(cacheKey).kuduClient
    }
  }
}
