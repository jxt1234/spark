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

import java.util.concurrent.atomic.AtomicLong

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.HTable
import org.apache.hadoop.hbase.filter.{Filter => HFilter}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{StructType, SchemaRDD, SQLContext}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{Filter, Join, LogicalPlan}
import org.apache.spark.sql.execution.{SparkPlan, SparkStrategies, UnaryNode}
import org.apache.spark.sql.hbase.HBaseCatalog.Columns


/**
 * HBaseStrategies
 * Created by sboesch on 8/22/14.
 */
private[hbase] trait HBaseStrategies extends SparkStrategies {
  // Possibly being too clever with types here... or not clever enough.
  self: SQLContext#SparkPlanner =>

  val hbaseContext: HBaseSQLContext

  /**
   * Retrieves data using a HBaseTableScan.  Partition pruning predicates are also detected and
   * applied.
   */
  object HBaseTableScans extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case PhysicalOperation(projectList, inPredicates, relation: HBaseRelation) =>

        val predicates = inPredicates.asInstanceOf[Seq[BinaryExpression]]
        // Filter out all predicates that only deal with partition keys, these are given to the
        // hive table scan operator to be used for partition pruning.

        val partitionKeys = relation.catalogTable.rowKey.columns.asAttributes()

        val partitionKeyIds = AttributeSet(partitionKeys)
        var (rowKeyPredicates, _ /*otherPredicates*/ ) = predicates.partition {
          _.references.subsetOf(partitionKeyIds)
        }

        val externalResource = relation.getExternalResource

        // Find and sort all of the rowKey dimension elements and stop as soon as one of the
        // composite elements is not found in any predicate
        val loopx = new AtomicLong
        val foundx = new AtomicLong
        val rowPrefixPredicates = for {pki <- partitionKeyIds
                                       if ((loopx.incrementAndGet >= 0)
                                         && rowKeyPredicates.flatMap {
                                         _.references
                                       }.contains(pki)
                                         && (foundx.incrementAndGet == loopx.get))
                                       attrib <- rowKeyPredicates.filter {
                                         _.references.contains(pki)
                                       }
        } yield attrib

        val otherPredicates = predicates.filterNot(rowPrefixPredicates.toList.contains)

        def rowKeyOrdinal(name: ColumnName) = relation.catalogTable.rowKey.columns(name).ordinal

        val catColumns: Columns = relation.catalogTable.columns
        val keyColumns: Columns = relation.catalogTable.rowKey.columns
        def catalystToHBaseColumnName(catColName: String) = {
          catColumns.findBySqlName(catColName)
        }

        // TODO(sboesch): uncertain if nodeName were canonical way to get correct sql column name
        def getName(expression: NamedExpression) = expression.asInstanceOf[NamedExpression].name

        val sortedRowPrefixPredicates = rowPrefixPredicates.toList.sortWith { (a, b) =>
          keyColumns(getName(a.left.asInstanceOf[NamedExpression])).
            get.ordinal <= keyColumns(getName(b.left.asInstanceOf[NamedExpression])).get.ordinal
        }

        // TODO(sboesch): complete the (start_key,end_key) calculations

        // We are only pushing down predicates in which one side is a column and the other is
        // a literal. Column to column comparisons are not initially supported. Therefore
        // check for each predicate containing only ONE reference
        //        val allPruningPredicateReferences = pruningPredicates.filter(pp =>
        //          pp.references.size == 1).flatMap(_.references)

        // Pushdown for RowKey filtering is only supported for prefixed rows so we
        // stop as soon as one component of the RowKey has no predicate
        //   val pruningPrefixIds = for {pki <- partitionKeyIds; pprid <-
        //     allPruningPredicateReferences.filter { pr : Attribute  => pr.exprId == pki.exprId}}
        //     yield pprid


        // If any predicates passed all restrictions then let us now build the RowKeyFilter
        var invalidRKPreds = false
        var rowKeyColumnPredicates: Option[Seq[ColumnPredicate]] =
          if (!sortedRowPrefixPredicates.isEmpty) {
            val bins = rowKeyPredicates.map {
              case pp: BinaryComparison =>
                Some(ColumnPredicate.catalystToHBase(pp))
              case s =>
                log.info(s"RowKeyPreds: Only BinaryComparison operators supported ${s.toString}")
                invalidRKPreds = true
                None
            }.flatten
            if (!bins.isEmpty) {
              Some(bins)
            } else {
              None
            }
          } else {
            None
          }
        if (invalidRKPreds) {
          rowKeyColumnPredicates = None
        }
        // TODO(sboesch): map the RowKey predicates to the Partitions
        // to achieve Partition Pruning.

        // Now process the projection predicates
        var invalidPreds = false
        var colPredicates = if (!predicates.isEmpty) {
          predicates.map {
            case pp: BinaryComparison =>
              Some(ColumnPredicate.catalystToHBase(pp))
            case s =>
              log.info(s"ColPreds: Only BinaryComparison operators supported ${s.toString}")
              invalidPreds = true
              None
          }
        } else {
          None
        }
        if (invalidPreds) {
          colPredicates = None
        }

        val emptyPredicate = ColumnPredicate.EmptyColumnPredicate
        // TODO(sboesch):  create multiple HBaseSQLTableScan's based on the calculated partitions
        def partitionRowKeyPredicatesByHBasePartition(rowKeyPredicates:
                                                      Option[Seq[ColumnPredicate]]):
                Seq[Seq[ColumnPredicate]] = {
          //TODO(sboesch): map the row key predicates to the
          // respective physical HBase Region server ranges
          //  and return those as a Sequence of ranges
          // First cut, just return a single range - thus we end up with a single HBaseSQLTableScan
          Seq(rowKeyPredicates.getOrElse(Seq(ColumnPredicate.EmptyColumnPredicate)))
        }

        val partitionRowKeyPredicates =
          partitionRowKeyPredicatesByHBasePartition(rowKeyColumnPredicates)

        partitionRowKeyPredicates.flatMap { partitionSpecificRowKeyPredicates =>
          def projectionToHBaseColumn(expr: NamedExpression,
                                      hbaseRelation: HBaseRelation): ColumnName = {
            hbaseRelation.catalogTable.columns.findBySqlName(expr.name).map(_.toColumnName).get
          }

          val columnNames = projectList.map(projectionToHBaseColumn(_, relation))

          val effectivePartitionSpecificRowKeyPredicates =
            if (rowKeyColumnPredicates == ColumnPredicate.EmptyColumnPredicate) {
              None
            } else {
              rowKeyColumnPredicates
            }

          val scanBuilder = HBaseSQLTableScan(partitionKeyIds.toSeq,
            relation,
            columnNames,
            predicates.reduceLeftOption(And),
            rowKeyPredicates.reduceLeftOption(And),
            effectivePartitionSpecificRowKeyPredicates,
            externalResource,
            plan)(hbaseContext).asInstanceOf[Seq[Expression] => SparkPlan]

          pruneFilterProject(
            projectList,
            otherPredicates,
            identity[Seq[Expression]], // removeRowKeyPredicates,
            scanBuilder) :: Nil
        }
      case _ =>
        Nil
    }
  }

  def getHTable(conf: Configuration, tname: String) = {
    val htable = new HTable(conf, tname)
    htable
  }

  def sparkFilterProjectJoinToHBaseScan(sFilter: Filter,
                                        sProject: Projection, sJoin: Join) = {
    //    if (sFilter.child.

  }

  object InsertIntoHBaseTable {
    def rowKeysFromRows(schemaRdd: SchemaRDD, relation: HBaseRelation) = schemaRdd.map { r : Row =>
      val rkey = relation.rowKeyParser.createKeyFromCatalystRow(schemaRdd.schema,
        relation.catalogTable.rowKeyColumns,r)
      rkey
    }
  }

  case class InsertIntoHBaseTable(
                                   relation: HBaseRelation,
                                   child: SparkPlan,
                                   bulk: Boolean = false,
                                   overwrite: Boolean = false)
                                 (hbContext: HBaseSQLContext)
    extends UnaryNode {
    import InsertIntoHBaseTable._
    override def execute() = {
      val childRdd = child.execute().asInstanceOf[SchemaRDD]
      assert(childRdd != null, "InsertIntoHBaseTable: the source RDD failed")
      // TODO: should we use compute with partitions instead here??
//      val rows = childRdd.collect

      val rowKeysWithRows = childRdd.zip(rowKeysFromRows(childRdd,relation))

      putToHBase(schema, relation, hbContext, rowKeysWithRows)

      // We return the child RDD to allow chaining (alternatively, one could return nothing).
      childRdd
    }

    override def output = child.output
  }

  object HBaseOperations extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case CreateHBaseTablePlan(nameSpace, tableName, hbaseTableName, keyCols, nonKeyCols) =>
        Seq(CreateHBaseTableCommand(nameSpace, tableName, hbaseTableName, keyCols, nonKeyCols)
          (hbaseContext))
      case InsertIntoHBaseTablePlan(table: HBaseRelation, partition, child, bulk, overwrite) =>
        new InsertIntoHBaseTable(table, planLater(child), bulk, overwrite)(hbaseContext) :: Nil
      case _ => Nil
    }
  }

  def putToHBase(rddSchema: StructType, relation: HBaseRelation,
                 hbContext: HBaseSQLContext, rowKeysWithRows: RDD[(Row, HBaseRawType)]) = {
    rowKeysWithRows.map{ case (row, rkey) =>
      // TODO(sboesch): below is v poor performance wise. Need to fix partitioning

      // Where do we put the tableIf? If we put inside the childRdd.map will a new tableIF
      // be instantiated for every row ??
      val tableIf = hbContext.hconnection.getTable(relation.catalogTable.hbaseTableName)
      val put = relation.rowToHBasePut(rddSchema, row)
      tableIf.put(put)
      tableIf.close
    }
  }

}
