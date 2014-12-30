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

import org.apache.spark.sql.catalyst.types.IntegerType
import org.apache.spark.sql.hbase.catalyst.types.HBaseBytesType
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import org.apache.spark.Logging
import org.apache.spark.sql._

class BytesUtilsSuite extends FunSuite with BeforeAndAfterAll with Logging{

  test("BytesUtils for int") {
    val intBytesUtils = BytesUtils.create(IntegerType)
    assert(BytesUtils.toInt(intBytesUtils.toBytes(40), 0) == 40)
  }

  test("byte test") {
    val s = Seq(-257,-256, -255, -129, -128, -127, -64, -16, -4, -1,
      0, 1, 4, 16, 64, 127, 128, 129, 255, 256,257)
      .map(i => (i, BytesUtils.create(IntegerType).toBytes(i)))
      .sortWith((f, s) =>
      HBaseBytesType.ordering.gt(
        f._2.asInstanceOf[HBaseBytesType.JvmType], s._2.asInstanceOf[HBaseBytesType.JvmType]))
    s.foreach(t => println(t._1))
  }
}
