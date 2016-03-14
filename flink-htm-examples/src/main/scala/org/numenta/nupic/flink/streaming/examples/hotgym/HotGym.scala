package org.numenta.nupic.flink.streaming.examples.hotgym

import de.javakaffee.kryoserializers.jodatime.JodaDateTimeSerializer
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.core.fs.FileSystem.WriteMode
import org.apache.flink.streaming.api.scala._
import org.joda.time.DateTime
import org.numenta.nupic.Parameters.KEY
import org.numenta.nupic.algorithms.{Anomaly, SpatialPooler, TemporalMemory}
import org.numenta.nupic.encoders.DateEncoder._
import org.numenta.nupic.encoders.scala._
import org.numenta.nupic.flink.streaming.examples.common.NetworkDemoParameters
import org.numenta.nupic.network.Network
import org.numenta.nupic.flink.streaming.api.scala._

trait HotGymModel {
  case class Consumption(timestamp: DateTime, consumption: Double)

  case class Prediction(timestamp: String, actual: Double, predicted: Double, anomalyScore: Double)

  val network = (key: AnyRef) => {
    val encoder = MultiEncoder(
      DateEncoder().name("timestamp").timeOfDay(21, 9.5),
      ScalarEncoder().name("consumption").n(50).w(21).maxVal(100.0).resolution(0.1).clipInput(true)
    )

    val params = NetworkDemoParameters.params

    val network = Network.create("HotGym Network", params)
      .add(Network.createRegion("Region 1")
        .add(Network.createLayer("Layer 2/3", params)
          .alterParameter(KEY.AUTO_CLASSIFY, true)
          .add(encoder)
          .add(Anomaly.create())
          .add(new TemporalMemory())
          .add(new SpatialPooler())))

    network.setEncoder(encoder)
    network
  }
}

/**
  * Demonstrate the hotgym "basic network" as a Flink job.
  */
object Demo extends App with HotGymModel {

  /**
    * Parse the hotgym csv file as a datastream of tuples.
    * @param path
    */
  def readCsvFile(path: String): DataStream[Tuple2[DateTime, Double]] = {
    env.readTextFile(appArgs.getRequired("input"))
      .map {
        _.split(",") match {
          case Array(timestamp, consumption) => (LOOSE_DATE_TIME.parseDateTime(timestamp), consumption.toDouble)
        }
      }
  }

  val appArgs = ParameterTool.fromArgs(this.args)

  val env = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.getConfig.setGlobalJobParameters(appArgs)
    env.setParallelism(1) // TODO: don't assume local mode
    env.addDefaultKryoSerializer(classOf[DateTime], classOf[JodaDateTimeSerializer])
    env
  }

  val hotGymConsumption: DataStream[Consumption] = readCsvFile(appArgs.getRequired("input"))
    .map { input => Consumption(input._1, input._2) }

  val inferences = hotGymConsumption
    .learn(network)
    .select { inference => inference }
    .keyBy { _ => None }
    .mapWithState { (inference, state: Option[Double]) =>

      (Prediction(
        inference.input.timestamp.toString(LOOSE_DATE_TIME),
        inference.input.consumption,
        state match {
          case Some(prediction) => prediction
          case None => 0.0
        },
        inference.anomalyScore),

        // store the current prediction for the next iteration
        /*inference.getClassification("consumption").getMostProbableValue(1).asInstanceOf[Double] match {
          case value if value != 0.0 => Some(value)
          case _ => Some(state.getOrElse(0.0))
        }*/
        Some(inference.input.consumption))
    }

  if (appArgs.has("output")) {
    inferences.writeAsCsv(appArgs.getRequired("output"), writeMode = WriteMode.OVERWRITE)
  }
  else {
    inferences.print()
  }

  env.execute("Streaming HotGym")
}
