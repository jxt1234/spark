package org.apache.spark.sql.hbase

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.{HTable, Put, HBaseAdmin}
import org.apache.hadoop.hbase._
import org.apache.log4j.Logger
import org.apache.spark.sql.hbase.HBaseCatalog.{HBaseDataType, Column, Columns}
import org.apache.spark.sql.test.TestSQLContext._
import org.apache.spark.{Logging, SparkConf, SparkContext}
import org.scalatest.{BeforeAndAfterAll, FunSuite}

/**
 * HBaseIntegrationTest
 * Created by sboesch on 9/27/14.
 */
object HBaseMainTest extends FunSuite with BeforeAndAfterAll with Logging {
  @transient val logger = Logger.getLogger(getClass.getName)

  val NMasters = 1
  val NRegionServers = 3
  val NDataNodes = 0

  val NWorkers = 1


  logger.info("Insert data into the test table using applySchema")
  @transient var cluster : MiniHBaseCluster = null
  @transient var config : Configuration = null
  @transient var hbaseAdmin : HBaseAdmin = null
  @transient var hbContext : HBaseSQLContext = null
  @transient var catalog : HBaseCatalog = null
  @transient var testUtil :HBaseTestingUtility = null

  //  @inline def assert(p: Boolean, msg: String) = {
//    if (!p) {
//      throw new IllegalStateException(s"AssertionError: $msg")
//    }
//  }

  case class MyTable(col1: String, col2: Byte, col3: Short, col4: Int, col5: Long,
                     col6: Float, col7: Double)

  val DbName = "mynamespace"
  val TabName = "myTable"
  val HbaseTabName = "hbasetaba"

  def testGetTable = {
    println("get table")
    // prepare the test data
    HBaseCatalog.getKeysFromAllMetaTableRows(config)
      .foreach{ r => logger.info(s"Metatable Rowkey: ${new String(r)}")}

    val oresult = catalog.getTable(Some(DbName), TabName)
    assert(oresult.isDefined)
    val result = oresult.get
    assert(result.tablename == TabName)
    assert(result.hbaseTableName.tableName.getNameAsString == DbName + ":" + HbaseTabName)
    assert(result.colFamilies.size == 2)
    assert(result.columns.columns.size == 4)
    assert(result.rowKeyColumns.columns.size == 3)
    val relation = catalog.lookupRelation(Some(DbName), TabName)
    val hbRelation = relation.asInstanceOf[HBaseRelation]
    assert(hbRelation.colFamilies == Seq("cf1", "cf2"))
    assert(Seq("col7", "col1", "col3").zip(hbRelation.partitionKeys)
      .forall{x => x._1 == x._2.name})
    val rkColumns = new Columns(Seq(Column("col7",null, "col7", HBaseDataType.DOUBLE),
      Column("col1",null, "col1", HBaseDataType.STRING),
      Column("col3",null, "col3", HBaseDataType.SHORT)))
    assert(hbRelation.catalogTable.rowKeyColumns.equals(rkColumns))
    assert(relation.childrenResolved)
  }

  def main(args: Array[String]) = {

    logger.info(s"Spin up hbase minicluster w/ $NMasters mast, $NRegionServers RS, $NDataNodes dataNodes")
    testUtil = new HBaseTestingUtility
    //    cluster = HBaseTestingUtility.createLocalHTU.
    //      startMiniCluster(NMasters, NRegionServers, NDataNodes)
    //    config = HBaseConfiguration.create
    config = testUtil.getConfiguration
    config.set("hbase.regionserver.info.port","-1")
    config.set("hbase.master.info.port","-1")
    config.set("dfs.client.socket-timeout","240000")
    config.set("dfs.datanode.socket.write.timeout","240000")
    config.set("zookeeper.session.timeout","240000")
    config.set("zookeeper.minSessionTimeout","10")
    config.set("zookeeper.tickTime","10")
    config.set("hbase.rpc.timeout","240000")
    config.set("ipc.client.connect.timeout","240000")
    config.set("dfs.namenode.stale.datanode.interva","240000")
    config.set("hbase.rpc.shortoperation.timeout","240000")
    cluster = testUtil.startMiniCluster(NMasters, NRegionServers)
    println(s"# of region servers = ${cluster.countServedRegions}")
    @transient val conf = new SparkConf
    val SparkPort = 11223
    conf.set("spark.ui.port",SparkPort.toString)
    @transient val sc = new SparkContext(s"local[$NWorkers]", "HBaseTestsSparkContext", conf)
    hbContext = new HBaseSQLContext(sc, config)
    import java.io._
    val bos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(hbContext)
    println(new String(bos.toByteArray))

    catalog = hbContext.catalog
    hbaseAdmin = new HBaseAdmin(config)

    hbContext.sql(s"""CREATE TABLE $TabName(col1 STRING, col2 BYTE, col3 SHORT, col4 INTEGER,
      col5 LONG, col6 FLOAT, col7 DOUBLE)
      MAPPED BY ($HbaseTabName KEYS=[col7, col1, col3], COLS=[col2=cf1.cq11,
      col4=cf1.cq12, col5=cf2.cq21, col6=cf2.cq22])"""
      .stripMargin)


    val hdesc = new HTableDescriptor(TableName.valueOf(HbaseTabName))
    Array(new HColumnDescriptor("cf1"), new HColumnDescriptor("cf2")).foreach{ f =>
      hdesc.addFamily(f)
    }
    hbaseAdmin.createTable(hdesc)

    if (!hbaseAdmin.tableExists(HbaseTabName)) {
      throw new IllegalArgumentException("where is our table?")
    }

    def makeRowKey(col7 : Double, col1: String, col3: Short) = {
      val size = 1+8+col1.size+2+3*2+1
//      val barr = new Array[Byte](size)
      val bos = new ByteArrayOutputStream(size)
      val dos = new DataOutputStream(bos)
      dos.writeByte('1'.toByte)
      dos.writeDouble(col7)
      dos.writeBytes(col1)
      dos.writeShort(col3)
      dos.writeShort(1)
      dos.writeShort(1+8)
      dos.writeShort(1+8+col1.length)
      dos.writeByte(3.toByte)
      val s = bos.toString
      println(s"MakeRowKey: [${s}]")
      bos.toByteArray
    }
    def addRowVals(put: Put, col2 : Byte, col4: Int, col5: Long, col6: Float) = {
      //      val barr = new Array[Byte](size)
      var bos = new ByteArrayOutputStream()
      var dos = new DataOutputStream(bos)
      dos.writeByte(col2)
      put.add(s2b("cf1"), s2b("cq11"), bos.toByteArray)
      bos = new ByteArrayOutputStream()
      dos = new DataOutputStream(bos)
      dos.writeInt(col4)
      put.add(s2b("cf1"), s2b("cq12"), bos.toByteArray)
      bos = new ByteArrayOutputStream()
      dos = new DataOutputStream(bos)
      dos.writeLong(col5)
      put.add(s2b("cf2"), s2b("cq21"), bos.toByteArray)
      bos = new ByteArrayOutputStream()
      dos = new DataOutputStream(bos)
      dos.writeFloat(col6)
      put.add(s2b("cf2"), s2b("cq22"), bos.toByteArray)
    }
//    val conn = hbaseAdmin.getConnection
//    val htable = conn.getTable(TableName.valueOf(DbName, TabName))
    val tname = TableName.valueOf(HbaseTabName)
    val htable = new HTable(config, tname)
    if (!hbaseAdmin.tableExists(tname)) {
      throw new IllegalStateException(s"Unable to find table ${tname.toString}")
    }
    hbaseAdmin.listTableNames.foreach{ t => println(s"table: $t")}

    var put = new Put(makeRowKey(12345.0,"Col1Value12345", 12345))
    addRowVals(put, (123).toByte, 12345678, 12345678901234L, 1234.5678F)
    htable.put(put)
    put = new Put(makeRowKey(456789.0,"Col1Value45678", 4567))
    addRowVals(put, (456).toByte, 456789012, 4567890123446789L, 456.78901F)
    htable.close

    val ctx = hbContext
    val results = ctx.sql(s"""SELECT col1, col3, col7 FROM $TabName
    WHERE col1 ='Michigan' and col7 >= 2500.0 and col3 >= 35 and col3 <= 50
    """.stripMargin)

    val data = results.collect

    System.exit(0)

    val results00 = ctx.sql(s"""SELECT col1, col3, col7 FROM $TabName
    WHERE col1 ='Michigan' and col7 >= 2500.0 and col3 >= 35 and col3 <= 50 and col3 != 7.0
    """.stripMargin)

    val results0 = ctx.sql(s"""SELECT col1, col2, col3, col7 FROM $TabName
    WHERE col1 ='Michigan' and col7 >= 2500.0 and col3 >= 35 and col3 <= 50 and col2 != 7.0
    """.stripMargin)

    val results1 = ctx.sql(s"""SELECT sum(col3) as col3sum, col1, col3 FROM $TabName
    WHERE col1 ='Michigan' and col7 >= 2500.0 and col3 >= 35 and col3 <= 50 and col2 != 7.0
    group by col1,  col3
    """.stripMargin)


    val results2 = ctx.sql(s"""SELECT sum(col2) as col2sum, col4, col1, col3, col2 FROM $TabName
    WHERE col1 ='Michigan' and col7 >= 2500.0 and col3 >= 35 and col3 <= 50
    group by col1, col2, col4, col3
    """.stripMargin)

    // Following fails with Unresolved:
    // Col1 Sort is unresolved
    // Col4 and col2 Aggregation are unresolved (interesting col3 IS resolved)
//    val results = ctx.sql(s"""SELECT col4, col1, col3, col2 FROM $TabName
//    WHERE col1 ='Michigan' and col7 >= 2500.0 and col3 >= 35 and col3 <= 50 group by col7, col1
//    ORDER BY col1 DESC"""
//      .stripMargin)

    hbContext.sql(s"""CREATE TABLE $DbName.$TabName(col1 STRING, col2 BYTE, col3 SHORT, col4 INTEGER,
      col5 LONG, col6 FLOAT, col7 DOUBLE)
      MAPPED BY ($HbaseTabName KEYS=[col7, col1, col3], COLS=[col2=cf1.cq11,
      col4=cf1.cq12, col5=cf2.cq21, col6=cf2.cq22])"""
      .stripMargin)

    val catTab = catalog.getTable(Some(DbName), TabName)
    assert(catTab.get.tablename == TabName)

    testGetTable


    import ctx.createSchemaRDD
    val myRows = ctx.sparkContext.parallelize(Range(1,21).map{ix =>
      MyTable(s"col1$ix", ix.toByte, (ix.toByte*256).asInstanceOf[Short],ix.toByte*65536, ix.toByte*65563L*65536L,
        (ix.toByte*65536.0).asInstanceOf[Float], ix.toByte*65536.0D*65563.0D)
    })

    //    import org.apache.spark.sql.execution.ExistingRdd
    //    val myRowsSchema = ExistingRdd.productToRowRdd(myRows)
    //    ctx.applySchema(myRowsSchema, schema)
    val TempTabName = "MyTempTab"
    myRows.registerTempTable(TempTabName)

    val localData = myRows.collect

    //    ctx.sql(
    //      s"""insert into $TabName select * from $TempTabName""".stripMargin)

    val hbRelation = catalog.lookupRelation(Some(DbName), TabName).asInstanceOf[HBaseRelation]


    val hbasePlanner = new SparkPlanner with HBaseStrategies {
      @transient override val hbaseContext: HBaseSQLContext = hbContext
    }

    val myRowsSchemaRdd = hbContext.createSchemaRDD(myRows)
    val insertPlan = hbasePlanner.InsertIntoHBaseTableFromRdd(hbRelation,
      myRowsSchemaRdd)(hbContext)

    var rowKeysWithRows = myRowsSchemaRdd.zip(
      HBaseStrategies.rowKeysFromRows(myRowsSchemaRdd,hbRelation))
//    var keysCollect = rowKeysWithRows.collect
    HBaseStrategies.putToHBaseLocal(myRows.schema, hbRelation, hbContext, rowKeysWithRows)


    val preparedInsertRdd = insertPlan.execute
    val executedInsertRdd = preparedInsertRdd.collect

    val rowsRdd = myRowsSchemaRdd
    val rowKeysWithRows2 = rowsRdd.zip(
      HBaseStrategies.rowKeysFromRows(rowsRdd,hbRelation))
    HBaseStrategies.putToHBaseLocal(rowsRdd.schema, hbRelation, hbContext, rowKeysWithRows2)


    cluster.shutdown
    hbContext.stop
  }
}