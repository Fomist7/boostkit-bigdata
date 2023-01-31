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

package org.apache.spark.deploy.history

import com.huawei.boostkit.spark.util.{KerberosUtil, RewriteLogger}
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.ServiceLoader
import java.util.regex.Pattern
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.json4s.DefaultFormats
import org.json4s.jackson.Json
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.mutable
import scala.util.control.Breaks

import org.apache.spark.{JobExecutionStatus, SparkConf}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.config.Status.ASYNC_TRACKING_ENABLED
import org.apache.spark.scheduler.ReplayListenerBus
import org.apache.spark.scheduler.ReplayListenerBus.{ReplayEventsFilter, SELECT_ALL_FILTER}
import org.apache.spark.sql.catalyst.optimizer.rules._
import org.apache.spark.sql.execution.ui._
import org.apache.spark.status._
import org.apache.spark.util.Utils
import org.apache.spark.util.kvstore.{InMemoryStore, KVStore}

class LogsParser(conf: SparkConf, eventLogDir: String, outPutDir: String) extends RewriteLogger {

  private val LINE_SEPARATOR = "\n"
  private val hadoopConf = confLoad()
  // Visible for testing
  private[history] val fs: FileSystem = new Path(eventLogDir).getFileSystem(hadoopConf)

  def confLoad(): Configuration = {
    val configuration: Configuration = SparkHadoopUtil.get.newConfiguration(conf)
    KerberosUtil.newConfiguration(configuration)
  }

  /**
   * parseAppHistoryLog
   *
   * @param appId appId
   */
  def parseAppHistoryLog(appId: String): Seq[Map[String, String]] = {
    var jsons = Seq.empty[Map[String, String]]

    try {
      val inMemoryStore = new InMemoryStore()
      val reader = EventLogFileReader(fs, new Path(eventLogDir, appId))
      rebuildAppStore(inMemoryStore, reader.get)

      val sqlStatusStore = new SQLAppStatusStore(inMemoryStore)
      val mvRewriteSuccessInfo = getMVRewriteSuccessInfo(inMemoryStore)

      // create OutputDir
      if (!fs.exists(new Path(outPutDir))) {
        fs.mkdirs(new Path(outPutDir))
      }

      // continue for curLoop
      val curLoop = new Breaks

      sqlStatusStore.executionsList().foreach { execution =>
        curLoop.breakable {
          // skip unNormal execution
          val isRunning = execution.completionTime.isEmpty ||
              execution.jobs.exists { case (_, status) => status == JobExecutionStatus.RUNNING }
          val isFailed = execution
              .jobs.exists { case (_, status) => status == JobExecutionStatus.FAILED }
          if (isRunning || isFailed) {
            curLoop.break()
          }

          val uiData = execution
          val executionId = uiData.executionId
          val planDesc = uiData.physicalPlanDescription
          val query = uiData.description
          var mvs = mvRewriteSuccessInfo.getOrElse(query, "")
          if (mvs.nonEmpty) {
            mvs.split(";").foreach { mv =>
              if (!planDesc.contains(mv)) {
                mvs = ""
              }
            }
          }
          val duration = if (uiData.completionTime.isDefined) {
            (uiData.completionTime.get.getTime - uiData.submissionTime) + "ms"
          } else {
            "Unfinished"
          }

          // write dot
          val graph: SparkPlanGraph = sqlStatusStore.planGraph(executionId)
          sqlStatusStore.planGraph(executionId)
          val metrics = sqlStatusStore.executionMetrics(executionId)
          val node = getNodeInfo(graph)

          val jsonMap = Map(
            "logName" -> appId,
            "executionId" -> executionId.toString,
            "original query" -> query,
            "materialized views" -> mvs,
            "physical plan" -> planDesc,
            "dot metrics" -> graph.makeDotFile(metrics),
            "node metrics" -> node,
            "duration" -> duration)
          jsons :+= jsonMap
        }
      }
    } catch {
      case e: FileNotFoundException =>
        throw e
      case e: Throwable =>
        logWarning(s"Failed to parseAppHistoryLog $appId for ${e.getMessage}")
    }
    jsons
  }

  def parseAppHistoryLogs(appIds: Seq[String], fileName: String): Unit = {
    var jsons = Seq.empty[Map[String, String]]
    appIds.foreach { appId =>
      jsons ++= parseAppHistoryLog(appId)
    }
    // write json file into hdfs
    val jsonFile: String = Json(DefaultFormats).write(jsons)
    writeFile(fs, outPutDir + "/" + fileName + ".json", jsonFile)
  }

  /**
   * getMVRewriteSuccessInfo
   *
   * @param store KVStore
   * @return {sql:mvs}
   */
  def getMVRewriteSuccessInfo(store: KVStore): mutable.Map[String, String] = {
    val infos = mutable.Map.empty[String, String]
    try {
      // The ApplicationInfo may not be available when Spark is starting up.
      Utils.tryWithResource(
        store.view(classOf[SparkListenerMVRewriteSuccess])
            .closeableIterator()
      ) { it =>
        while (it.hasNext) {
          val info = it.next()
          infos += (info.sql -> info.usingMvs)
        }
      }
    } catch {
      case e: NoSuchElementException =>
        logWarning("getMVRewriteSuccessInfo is failed for ", e)
    }
    infos
  }

  /**
   * getNodeInfo from graph
   *
   * @param graph SparkPlanGraph
   * @return NodeInfo
   */
  def getNodeInfo(graph: SparkPlanGraph): String = {
    // write node
    val tmpContext = new mutable.StringBuilder
    tmpContext.append("[PlanMetric]")
    nextLine(tmpContext)
    graph.allNodes.foreach { node =>
      tmpContext.append(s"id:${node.id} name:${node.name} desc:${node.desc}")
      nextLine(tmpContext)
      node.metrics.foreach { metric =>
        metric.toString
        tmpContext.append("SQLPlanMetric(")
        tmpContext.append(metric.name)
        tmpContext.append(",")
        if (metric.metricType == "timing") {
          tmpContext.append(s"${metric.accumulatorId * 1000000} ns, ")
        } else if (metric.metricType == "nsTiming") {
          tmpContext.append(s"${metric.accumulatorId} ns, ")
        } else {
          tmpContext.append(s"${metric.accumulatorId}, ")
        }
        tmpContext.append(metric.metricType)
        tmpContext.append(")")
        nextLine(tmpContext)
      }
      nextLine(tmpContext)
      nextLine(tmpContext)
      nextLine(tmpContext)
    }

    graph.edges.foreach { edges =>
      tmpContext.append(edges.makeDotEdge)
      nextLine(tmpContext)
    }

    tmpContext.append("[SubGraph]")
    nextLine(tmpContext)
    graph.allNodes.foreach {
      case cluster: SparkPlanGraphCluster =>
        tmpContext.append(s"cluster ${cluster.id} : ")
        for (i <- cluster.nodes.indices) {
          tmpContext.append(s"${cluster.nodes(i).id} ")
        }
        nextLine(tmpContext)
      case _ =>
    }
    nextLine(tmpContext)
    tmpContext.toString()
  }

  def nextLine(context: mutable.StringBuilder): Unit = {
    context.append(LINE_SEPARATOR)
  }

  /**
   * rebuildAppStore
   *
   * @param store  KVStore
   * @param reader EventLogFileReader
   */
  private[spark] def rebuildAppStore(store: KVStore, reader: EventLogFileReader): Unit = {
    // Disable async updates, since they cause higher memory usage, and it's ok to take longer
    // to parse the event logs in the SHS.
    val replayConf = conf.clone().set(ASYNC_TRACKING_ENABLED, false)
    val trackingStore = new ElementTrackingStore(store, replayConf)
    val replayBus = new ReplayListenerBus()
    val listener = new AppStatusListener(trackingStore, replayConf, false)
    replayBus.addListener(listener)
    replayBus.addListener(new MVRewriteSuccessListener(trackingStore))

    for {
      plugin <- loadPlugins()
      listener <- plugin.createListeners(conf, trackingStore)
    } replayBus.addListener(listener)

    try {
      val eventLogFiles = reader.listEventLogFiles

      // parse event log
      parseAppEventLogs(eventLogFiles, replayBus, !reader.completed)
      trackingStore.close(false)
    } catch {
      case e: Exception =>
        Utils.tryLogNonFatalError {
          trackingStore.close()
        }
        throw e
    }
  }

  /**
   * loadPlugins
   *
   * @return Plugins
   */
  private def loadPlugins(): Iterable[AppHistoryServerPlugin] = {
    val plugins = ServiceLoader.load(classOf[AppHistoryServerPlugin],
      Utils.getContextOrSparkClassLoader).asScala
    plugins
  }

  /**
   * parseAppEventLogs
   *
   * @param logFiles       Seq[FileStatus]
   * @param replayBus      ReplayListenerBus
   * @param maybeTruncated Boolean
   * @param eventsFilter   ReplayEventsFilter
   */
  private def parseAppEventLogs(logFiles: Seq[FileStatus],
      replayBus: ReplayListenerBus,
      maybeTruncated: Boolean,
      eventsFilter: ReplayEventsFilter = SELECT_ALL_FILTER): Unit = {
    // stop replaying next log files if ReplayListenerBus indicates some error or halt
    var continueReplay = true
    logFiles.foreach { file =>
      if (continueReplay) {
        Utils.tryWithResource(EventLogFileReader.openEventLog(file.getPath, fs)) { in =>
          continueReplay = replayBus.replay(in, file.getPath.toString,
            maybeTruncated = maybeTruncated, eventsFilter = eventsFilter)
        }
      }
    }
  }

  /**
   * write parsed logInfo to logPath
   *
   * @param fs      FileSystem
   * @param logPath logPath
   * @param context logInfo
   */
  private def writeFile(fs: FileSystem, logPath: String, context: String): Unit = {
    val os = fs.create(new Path(logPath))
    os.write(context.getBytes())
    os.close()
  }

  def listAppHistoryLogs(pattern: Pattern, startTime: Long, endTime: Long): Seq[String] = {
    var appHistoryLogs = Seq.empty[String]
    fs.listStatus(new Path(eventLogDir)).foreach { log =>
      val logTime = log.getModificationTime
      if (startTime <= logTime && logTime <= endTime) {
        val name = log.getPath.getName
        val matcher = pattern.matcher(name)
        if (matcher.find) {
          val appId = matcher.group
          appHistoryLogs +:= appId
        } else {
          logDebug(name + " is illegal")
        }
      }
    }
    appHistoryLogs
  }
}

/*
arg0: spark.eventLog.dir, eg. hdfs://server1:9000/spark2-history
arg1: output dir in hdfs, eg. hdfs://server1:9000/logParser
arg2: log file to be parsed, eg. application_1646816941391_0115.lz4
 */
object ParseLog extends RewriteLogger {
  val regex = ".*application_[0-9]+_[0-9]+.*(\\.lz4)?$"

  def main(args: Array[String]): Unit = {
    if (args == null || args.length != 3) {
      throw new RuntimeException("input params is invalid,such as below\n" +
          "arg0: spark.eventLog.dir, eg. hdfs://server1:9000/spark2-history\n" +
          "arg1: output dir in hdfs, eg. hdfs://server1:9000/logParser\n" +
          "arg2: log file to be parsed, eg. application_1646816941391_0115.lz4\n")
    }
    val sparkEventLogDir = args(0)
    val outputDir = args(1)
    val logName = args(2)

    val conf = new SparkConf
    // spark.eventLog.dir
    conf.set("spark.eventLog.dir", sparkEventLogDir)
    // spark.eventLog.compress
    conf.set("spark.eventLog.compress", "true")
    // fs.hdfs.impl
    conf.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem")
    val logParser = new LogsParser(conf, sparkEventLogDir, outputDir)

    // file pattern
    val pattern = Pattern.compile(regex)
    val matcher = pattern.matcher(logName)
    if (matcher.find) {
      val appId = matcher.group
      logParser.parseAppHistoryLogs(Seq(appId), logName)
    } else {
      throw new RuntimeException(logName + " is illegal")
    }
  }
}

/*
arg0: spark.eventLog.dir, eg. hdfs://server1:9000/spark2-history
arg1: output dir in hdfs, eg. hdfs://server1:9000/logParser
arg2: outFileName, eg.  log_parse_1646816941391
arg3: startTime, eg. 2022-09-15 11:00
arg4: endTime, eg. 2022-09-25 11:00
 */
object ParseLogs extends RewriteLogger {
  def main(args: Array[String]): Unit = {
    if (args == null || args.length != 5) {
      throw new RuntimeException("input params is invalid,such as below\n" +
          "arg0: spark.eventLog.dir, eg. hdfs://server1:9000/spark2-history\n" +
          "arg1: output dir in hdfs, eg. hdfs://server1:9000/logParser\n" +
          "arg2: outFileName, eg.  log_parse_1646816941391\n" +
          "arg3: startTime, eg.  2022-09-15 11:00\n" +
          "arg4: endTime, eg.  2022-09-25 11:00\n")
    }
    val sparkEventLogDir = args(0)
    val outputDir = args(1)
    val logName = args(2)
    val startTime = args(3)
    val endTime = args(4)

    val conf = new SparkConf
    // spark.eventLog.dir
    conf.set("spark.eventLog.dir", sparkEventLogDir)
    // spark.eventLog.compress
    conf.set("spark.eventLog.compress", "true")
    // fs.hdfs.impl
    conf.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem")
    val logParser = new LogsParser(conf, sparkEventLogDir, outputDir)

    // file pattern
    val regex = ParseLog.regex
    val pattern = Pattern.compile(regex)
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
    val appIds = logParser.listAppHistoryLogs(pattern,
      dateFormat.parse(startTime).getTime, dateFormat.parse(endTime).getTime)
    logParser.parseAppHistoryLogs(appIds, logName)
  }
}
