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

package org.apache.spark.sql.hbase

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.hbase.execution._
import org.apache.spark.sql.hbase.util.{BytesUtils, Util}
import org.apache.spark.sql.types._
import org.apache.spark.SparkContext

class BulkLoadIntoTableSuite extends QueriesSuiteBase {
  val sc: SparkContext = TestHbase.sparkContext
  val sparkHome = TestHbase.sparkContext.getSparkHome().getOrElse("./")
  if (sparkHome == null || sparkHome.isEmpty)
    logError("Spark Home is not defined; may lead to unexpected error!")

  // Test if we can parse 'LOAD DATA LOCAL INPATH './usr/file.txt' INTO TABLE tb1'
  test("bulk load parser test, local file") {

    val parser = new HBaseSQLParser()
    val sql = raw"LOAD PARALL DATA LOCAL INPATH './usr/file.txt' INTO TABLE tb1"

    val plan: LogicalPlan = parser(sql)
    assert(plan != null)
    assert(plan.isInstanceOf[ParallelizedBulkLoadIntoTableCommand])

    val l = plan.asInstanceOf[ParallelizedBulkLoadIntoTableCommand]
    assert(l.inputPath.equals(raw"./usr/file.txt"))
    assert(l.isLocal)
    assert(l.tableName.equals("tb1"))
  }

  // Test if we can parse 'LOAD DATA INPATH '/usr/hdfsfile.txt' INTO TABLE tb1'
  test("bulkload parser test, load hdfs file") {

    val parser = new HBaseSQLParser()
    val sql = raw"LOAD PARALL DATA INPATH '/usr/hdfsfile.txt' INTO TABLE tb1"

    val plan: LogicalPlan = parser(sql)
    assert(plan != null)
    assert(plan.isInstanceOf[ParallelizedBulkLoadIntoTableCommand])

    val l = plan.asInstanceOf[ParallelizedBulkLoadIntoTableCommand]
    assert(l.inputPath.equals(raw"/usr/hdfsfile.txt"))
    assert(!l.isLocal)
    assert(l.tableName.equals("tb1"))
  }

  test("bulkload parser test, using delimiter") {

    val parser = new HBaseSQLParser()
    val sql = raw"LOAD PARALL DATA INPATH '/usr/hdfsfile.txt' INTO TABLE tb1 FIELDS TERMINATED BY '\\|' "

    val plan: LogicalPlan = parser(sql)
    assert(plan != null)
    assert(plan.isInstanceOf[ParallelizedBulkLoadIntoTableCommand])

    val l = plan.asInstanceOf[ParallelizedBulkLoadIntoTableCommand]
    assert(l.inputPath.equals(raw"/usr/hdfsfile.txt"))
    assert(!l.isLocal)
    assert(l.tableName.equals("tb1"))
    assert(l.delimiter.get.equals(raw"\\|"))
  }

  test("write data to HFile with non-parallelized bulk loader") {
    val columns = Seq(new KeyColumn("k1", IntegerType, 0), new NonKeyColumn("v1", IntegerType, "cf1", "c1"))
    val hbaseRelation = HBaseRelation("testtablename", "hbasenamespace", "hbasetablename", columns)(TestHbase)
    val bulkLoad = BulkLoadIntoTableCommand(sparkHome + "/sql/hbase/src/test/resources/test.txt", "hbasetablename",
      isLocal = true, Option(","))
    val splitKeys = (1 to 40).filter(_ % 5 == 0).map { r =>
      val bytesUtils = BytesUtils.create(IntegerType)
      bytesUtils.toBytes(r)
    }
    val conf = TestHbase.sparkContext.hadoopConfiguration
    val job = Job.getInstance(conf)

    val hadoopReader = {
      val fs = FileSystem.getLocal(conf)
      val pathString = fs.pathToFile(new Path(bulkLoad.path)).getCanonicalPath
      new HadoopReader(TestHbase.sparkContext, pathString, bulkLoad.delimiter)(hbaseRelation)
    }
    val tmpPath = Util.getTempFilePath(conf, hbaseRelation.tableName)
    bulkLoad.makeBulkLoadRDD(splitKeys.toArray, hadoopReader, job, tmpPath, hbaseRelation)
    val resStatus = FileSystem.get(conf).listStatus(new Path(tmpPath)).map(_.getPath)
    assert(resStatus.size == 2)
    assert(resStatus.exists(_.getName.endsWith("cf1")))
    assert(resStatus.exists(_.getName.endsWith("_SUCCESS")))
    FileSystem.get(conf).delete(new Path(tmpPath), true)
  }

  test("load data into hbase") {

    val drop = "drop table testblk"
    val executeSql0 = TestHbase.executeSql(drop)
    try {
      executeSql0.toRdd.collect()
    } catch {
      case e: IllegalStateException =>
    }

    // create sql table map with hbase table and run simple sql
    val sql1 =
      s"""CREATE TABLE testblk(col1 STRING, col2 STRING, col3 STRING, PRIMARY KEY(col1))
          MAPPED BY (testblkHTable, COLS=[col2=cf1.a, col3=cf1.b])"""
        .stripMargin

    val sql2 =
      s"""select * from testblk limit 5"""
        .stripMargin

    val executeSql1 = TestHbase.executeSql(sql1)
    executeSql1.toRdd.collect()

    val executeSql2 = TestHbase.executeSql(sql2)
    executeSql2.toRdd.collect()

    val inputFile = "'" + sparkHome + "/sql/hbase/src/test/resources/loadData.txt'"

    // then load data into table
    val loadSql = "LOAD DATA LOCAL INPATH " + inputFile + " INTO TABLE testblk"

    val executeSql3 = TestHbase.executeSql(loadSql)
    executeSql3.toRdd.collect()

    checkAnswer(TestHbase.sql("select * from testblk"),
      Row("row4", "4", "8") ::
        Row("row5", "5", "10") ::
        Row("row6", "6", "12") :: Nil)

    // cleanup
    TestHbase.sql(drop)
  }

  test("load parall data into hbase") {

    val drop = "drop table testblk"
    val executeSql0 = TestHbase.executeSql(drop)
    try {
      executeSql0.toRdd.collect()
    } catch {
      case e: IllegalStateException =>
        // do not throw exception here
        println(e.getMessage)
    }

    // create sql table map with hbase table and run simple sql
    val sql1 =
      s"""CREATE TABLE testblk(col1 STRING, col2 STRING, col3 STRING, PRIMARY KEY(col1))
          MAPPED BY (testblkHTable, COLS=[col2=cf1.a, col3=cf1.b])"""
        .stripMargin

    val sql2 =
      s"""select * from testblk limit 5"""
        .stripMargin

    val executeSql1 = TestHbase.executeSql(sql1)
    executeSql1.toRdd.collect()

    val executeSql2 = TestHbase.executeSql(sql2)
    executeSql2.toRdd.collect()

    val inputFile = "'" + sparkHome + "/sql/hbase/src/test/resources/loadData.txt'"

    // then load parall data into table
    val loadSql = "LOAD PARALL DATA LOCAL INPATH " + inputFile + " INTO TABLE testblk"

    val executeSql3 = TestHbase.executeSql(loadSql)
    executeSql3.toRdd.collect()

    checkAnswer(TestHbase.sql("select * from testblk"),
      Row("row4", "4", "8") ::
        Row("row5", "5", "10") ::
        Row("row6", "6", "12") :: Nil)

    // cleanup
    TestHbase.sql(drop)
  }

  test("load data with null column values into hbase") {

    val drop = "drop table testNullColumnBulkload"
    val executeSql0 = TestHbase.executeSql(drop)
    try {
      executeSql0.toRdd.collect()
    } catch {
      case e: IllegalStateException =>
    }

    // create sql table map with hbase table and run simple sql
    val sql1 =
      s"""CREATE TABLE testNullColumnBulkload(col1 STRING, col2 STRING, col3 STRING, col4 STRING, PRIMARY KEY(col1))
          MAPPED BY (testNullColumnBulkloadHTable, COLS=[col2=cf1.a, col3=cf1.b, col4=cf1.c])"""
        .stripMargin

    val sql2 =
      s"""select * from testNullColumnBulkload"""
        .stripMargin

    val executeSql1 = TestHbase.executeSql(sql1)
    executeSql1.toRdd.collect()

    val executeSql2 = TestHbase.executeSql(sql2)
    executeSql2.toRdd.collect()

    val inputFile = "'" + sparkHome + "/sql/hbase/src/test/resources/loadNullableData.txt'"

    // then load data into table
    val loadSql = "LOAD DATA LOCAL INPATH " + inputFile + " INTO TABLE testNullColumnBulkload"

    val executeSql3 = TestHbase.executeSql(loadSql)
    executeSql3.toRdd.collect()

    val sqlResult = TestHbase.sql("select * from testNullColumnBulkload")
    val rows = sqlResult.collect()
    assert(rows.length == 3, s"load parall data with null column values into hbase")
    assert(rows(0)(1).asInstanceOf[String].isEmpty, s"load parall data into hbase test failed to select empty-string col1 value")
    assert(rows(1)(2).asInstanceOf[String].isEmpty, s"load parall data into hbase test failed to select empty-string col2 value")
    assert(rows(2)(3) == null, s"load parall data into hbase test failed to select null col3 value")
    checkAnswer(sqlResult,
      Row("row1", "", "8", "101") ::
        Row("row2", "2", "", "102") ::
        Row("row3", "3", "10", null) :: Nil)

    // cleanup
    TestHbase.sql(drop)
  }

  test("load parall data with null column values into hbase") {

    val drop = "drop table testNullColumnBulkload"
    val executeSql0 = TestHbase.executeSql(drop)
    try {
      executeSql0.toRdd.collect()
    } catch {
      case e: IllegalStateException =>
        // do not throw exception here
        println(e.getMessage)
    }

    // create sql table map with hbase table and run simple sql
    val sql1 =
      s"""CREATE TABLE testNullColumnBulkload(col1 STRING, col2 STRING, col3 STRING, col4 STRING, PRIMARY KEY(col1))
          MAPPED BY (testNullColumnBulkloadHTable, COLS=[col2=cf1.a, col3=cf1.b, col4=cf1.c])"""
        .stripMargin

    val sql2 =
      s"""select * from testNullColumnBulkload"""
        .stripMargin

    val executeSql1 = TestHbase.executeSql(sql1)
    executeSql1.toRdd.collect()

    val executeSql2 = TestHbase.executeSql(sql2)
    executeSql2.toRdd.collect()

    val inputFile = "'" + sparkHome + "/sql/hbase/src/test/resources/loadNullableData.txt'"

    // then load parall data into table
    val loadSql = "LOAD PARALL DATA LOCAL INPATH " + inputFile + " INTO TABLE testNullColumnBulkload"

    val executeSql3 = TestHbase.executeSql(loadSql)
    executeSql3.toRdd.collect()

    val sqlResult = TestHbase.sql("select * from testNullColumnBulkload")
    val rows = sqlResult.collect()
    assert(rows.length == 3, s"load parall data with null column values into hbase")
    assert(rows(0)(1).asInstanceOf[String].isEmpty, s"load parall data into hbase test failed to select empty-string col1 value")
    assert(rows(1)(2).asInstanceOf[String].isEmpty, s"load parall data into hbase test failed to select empty-string col2 value")
    assert(rows(2)(3) == null, s"load parall data into hbase test failed to select null col3 value")
    checkAnswer(sqlResult,
      Row("row1", "", "8", "101") ::
        Row("row2", "2", "", "102") ::
        Row("row3", "3", "10", null) :: Nil)

    // cleanup
    TestHbase.sql(drop)
  }

  test("load data on hbase table with more than 1 column family") {
    createNativeHbaseTable("multi_cf_table", Seq("cf1", "cf2"))
    // create sql table map with hbase table and run simple sql
    val sql1 =
      s"""CREATE TABLE testblk(col1 STRING, col2 STRING, col3 STRING, PRIMARY KEY(col1))
          MAPPED BY (multi_cf_table, COLS=[col2=cf1.a, col3=cf2.b])"""
        .stripMargin

    val sql2 =
      s"""select * from testblk limit 5"""
        .stripMargin

    val executeSql1 = TestHbase.executeSql(sql1)
    executeSql1.toRdd.collect()

    val executeSql2 = TestHbase.executeSql(sql2)
    executeSql2.toRdd.collect()

    val inputFile = "'" + sparkHome + "/sql/hbase/src/test/resources/loadData.txt'"

    // then load parall data into table
    val loadSql = "LOAD DATA LOCAL INPATH " + inputFile + " INTO TABLE testblk"

    val executeSql3 = TestHbase.executeSql(loadSql)
    executeSql3.toRdd.collect()

    checkAnswer(TestHbase.sql("select * from testblk"),
      Row("row4", "4", "8") ::
        Row("row5", "5", "10") ::
        Row("row6", "6", "12") :: Nil)

    // cleanup
    TestHbase.sql("drop table testblk")
  }
}