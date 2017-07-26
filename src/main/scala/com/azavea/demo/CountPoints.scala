package com.azavea.demo

import io.pdal._
import com.typesafe.config._
import geotrellis.pointcloud.spark.io.hadoop._
import geotrellis.spark.io.kryo.KryoRegistrator
import org.apache.hadoop.fs.Path
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.serializer.KryoSerializer

/** Tests whether PDAL, GeoTrellis and Spark are set up correctly.
  * Grabs the point count from the test files.
  */
object CountPoints {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("LeveeTest")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)

    try {
      // Get test input file directory from configuration
      val testDir = {
        val dataDir = args(0)
        // Convert it to hadoop URI format
        new Path(s"file://$dataDir")
      }

      val numPoints =
        HadoopPointCloudRDD(testDir)
          .map { case (header, pointClouds) =>
            pointClouds.map { pc => pc.length }.sum
          }
          .reduce(_ + _)
      println(s"THERE ARE ${numPoints} points in the test set.")
    } finally {
      sc.stop()
    }
  }
}
