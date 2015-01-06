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

import org.apache.spark.sql._
import org.scalatest.BeforeAndAfterAll

/* Implicits */

import org.apache.spark.sql.hbase.TestHbase._

class HBaseAdvancedSQLQuerySuite extends QueryTest with BeforeAndAfterAll {
  // Make sure the tables are loaded.
  TestData

  //  override def beforeAll: Unit = {
  //    import org.apache.spark.sql.hbase.HBaseMainTest._
  //
  //    HBaseMainTest.main(null)
  //    hbaseAdmin.close()
  //  }

  test("aggregation with codegen") {
    val originalValue = codegenEnabled
    setConf(HBaseSQLConf.CODEGEN_ENABLED, "true")
    sql("SELECT col1 FROM ta GROUP BY col1").collect()
    setConf(HBaseSQLConf.CODEGEN_ENABLED, originalValue.toString)
  }

  test("dsl simple select 0") {
    val tableA = sql("SELECT * FROM ta")
    checkAnswer(
      tableA.where('col7 === 1).orderBy('col2.asc).select('col4),
      Seq(Seq(1)))
  }

  test("metadata is propagated correctly") {
    val tableA = sql("SELECT col7, col1, col3 FROM ta")
    val schema = tableA.schema
    val docKey = "doc"
    val docValue = "first name"
    val metadata = new MetadataBuilder()
      .putString(docKey, docValue)
      .build()
    val schemaWithMeta = new StructType(Seq(
      schema("col7"), schema("col1").copy(metadata = metadata), schema("col3")))
    val personWithMeta = applySchema(tableA, schemaWithMeta)
    def validateMetadata(rdd: SchemaRDD): Unit = {
      assert(rdd.schema("col1").metadata.getString(docKey) == docValue)
    }
    personWithMeta.registerTempTable("personWithMeta")
    validateMetadata(personWithMeta.select('col1))
    validateMetadata(personWithMeta.select("col1".attr))
    validateMetadata(personWithMeta.select('col7, 'col1))
    validateMetadata(sql("SELECT * FROM personWithMeta"))
    validateMetadata(sql("SELECT col7, col1 FROM personWithMeta"))
    validateMetadata(sql("SELECT * FROM personWithMeta JOIN salary ON col7 = personId"))
    validateMetadata(sql("SELECT col1, salary FROM personWithMeta JOIN salary ON col7 = personId"))
  }
}
