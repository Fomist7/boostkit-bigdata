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

package org.apache.spark.sql.catalyst.optimizer.rules

import com.fasterxml.jackson.annotation.JsonIgnore
import com.huawei.boostkit.spark.conf.OmniCachePluginConfig
import com.huawei.boostkit.spark.util.{RewriteHelper, RewriteLogger}
import scala.collection.mutable

import org.apache.spark.SparkContext
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.scheduler.{SparkListener, SparkListenerEvent}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.command.OmniCacheCreateMvCommand
import org.apache.spark.status.ElementTrackingStore
import org.apache.spark.util.kvstore.KVIndex

class MVRewriteRule(session: SparkSession) extends Rule[LogicalPlan] with RewriteLogger {
  val omniCacheConf: OmniCachePluginConfig = OmniCachePluginConfig.getConf

  val joinRule = new MaterializedViewJoinRule(session)
  val aggregateRule = new MaterializedViewAggregateRule(session)

  override def apply(logicalPlan: LogicalPlan): LogicalPlan = {
    if (!omniCacheConf.enableOmniCache) {
      return logicalPlan
    }
    try {
      logicalPlan match {
        case _: OmniCacheCreateMvCommand =>
          logicalPlan
        case _ =>
          tryRewritePlan(logicalPlan)
      }
    } catch {
      case e: Throwable =>
        logWarning(s"Failed to rewrite plan with mv,errmsg: ${e.getMessage}")
        logicalPlan
    }
  }

  def tryRewritePlan(plan: LogicalPlan): LogicalPlan = {
    val usingMvs = mutable.Set.empty[String]
    RewriteTime.clear()
    val rewriteStartSecond = System.currentTimeMillis()
    val res = plan.transformDown {
      case p: Project =>
        joinRule.perform(Some(p), p.child, usingMvs)
      case a: Aggregate =>
        var rewritedPlan = aggregateRule.perform(None, a, usingMvs)
        // below agg may be join/filter can be rewrite
        if (rewritedPlan == a && !a.child.isInstanceOf[Project]) {
          val child = Project(
            RewriteHelper.extractAllAttrsFromExpression(a.aggregateExpressions).toSeq, a.child)
          val rewritedChild = joinRule.perform(Some(child), child.child, usingMvs)
          if (rewritedChild != child) {
            val projectChild = rewritedChild.asInstanceOf[Project]
            rewritedPlan = a.copy(child = Project(
              projectChild.projectList ++ projectChild.child.output, projectChild.child))
          }
        }
        rewritedPlan
      case p => p
    }
    if (usingMvs.nonEmpty) {
      RewriteTime.withTimeStat("checkAttrsValid") {
        if (!RewriteHelper.checkAttrsValid(res)) {
          RewriteTime.statFromStartTime("total", rewriteStartSecond)
          logBasedOnLevel(RewriteTime.timeStat.toString())
          return plan
        }
      }
      val sql = session.sparkContext.getLocalProperty(SparkContext.SPARK_JOB_DESCRIPTION)
      val mvs = usingMvs.mkString(";").replaceAll("`", "")
      val costSecond = (System.currentTimeMillis() - rewriteStartSecond).toString
      val log = ("logicalPlan MVRewrite success," +
          "using materialized view:[%s],cost %s milliseconds,original sql:%s")
          .format(mvs, costSecond, sql)
      logBasedOnLevel(log)
      session.sparkContext.listenerBus.post(SparkListenerMVRewriteSuccess(sql, mvs))
    }
    RewriteTime.statFromStartTime("total", rewriteStartSecond)
    logBasedOnLevel(RewriteTime.timeStat.toString())
    res
  }
}

@DeveloperApi
case class SparkListenerMVRewriteSuccess(sql: String, usingMvs: String) extends SparkListenerEvent {
  @JsonIgnore
  @KVIndex
  def id: String = (System.currentTimeMillis() + "%s%s".format(sql, usingMvs).hashCode).toString
}

class MVRewriteSuccessListener(
    kvStore: ElementTrackingStore) extends SparkListener with RewriteLogger {

  override def onOtherEvent(event: SparkListenerEvent): Unit = {
    event match {
      case _: SparkListenerMVRewriteSuccess =>
        kvStore.write(event)
      case _ =>
    }
  }
}

object RewriteTime {
  val timeStat: mutable.Map[String, Long] = mutable.HashMap[String, Long]()

  def statFromStartTime(key: String, startTime: Long): Unit = {
    timeStat += (key -> (timeStat.getOrElse(key, 0L) + System.currentTimeMillis() - startTime))
  }

  def clear(): Unit = {
    timeStat.clear()
  }

  def withTimeStat[T](key: String)(f: => T): T = {
    val startTime = System.currentTimeMillis()
    try {
      f
    } finally {
      statFromStartTime(key, startTime)
    }
  }
}
