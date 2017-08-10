package com.azavea.demo

import com.typesafe.config._
import com.vividsolutions.jts.geom.Coordinate
import geotrellis.pointcloud.pipeline.{Read, ReprojectionFilter}
import geotrellis.pointcloud.spark.io.hadoop._
import geotrellis.pointcloud.spark.triangulation._
import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster._
import geotrellis.raster.io._
import geotrellis.raster.resample.Bilinear
import geotrellis.raster.reproject.Reproject
import geotrellis.spark._
import geotrellis.spark.tiling._
import geotrellis.spark.io._
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.file._
import geotrellis.spark.io.kryo.KryoRegistrator
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.viewshed._
import geotrellis.vector._
import org.apache.hadoop.fs.Path
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import spire.syntax.cfor._
import spray.json.DefaultJsonProtocol._
import scala.collection.mutable

import IterativeViewshed._

/** Compute a viewshed over the DEM data.
  */
object ComputeViewshed {
  def main(args: Array[String]): Unit = {
    val numPartitions = 50
    val layerName = "levee-dem" // GeoTrellis Layer name
    val MAX_ZOOM = 18
    val VIEWER_HEIGHT = 1.5 // Viewer height, in meters
    val viewshedLayerName = "levee-viewshed"


    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("LeveeTest")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)

    try {
      // Get test input file directory from configuration
      val catalogDir = {
        val config = ConfigFactory.load()
        config.getString("catalog-dir")
      }

      val layerReader = FileLayerReader(catalogDir)
      val layerWriter = FileLayerWriter(catalogDir)
      val layerDeleter = FileLayerDeleter(catalogDir)
      val attributeStore = layerWriter.attributeStore

      // input layer is not part of a pyramid
      val layer = layerReader.read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](LayerId(layerName, 0))

      // compute layout for the base of th visualization pyramid
      val layoutScheme = ZoomedLayoutScheme(WebMercator)
      val LayoutLevel(_, targetLayout) = layoutScheme.levelForZoom(MAX_ZOOM)

      // we must reproject the layer to 3857 to make a viz layer
      val (_, base) = layer.reproject(WebMercator, targetLayout, Reproject.Options(method = Bilinear))

      // A point along the inside of the levee on the south west side.
      // Need to reproject to the CRS of the raster layer.
      val point = Point(-90.24889290332794, 29.343082791891305)

        .reproject(LatLng, layer.metadata.crs)

      val viewPoint = IterativeViewshed.Point6D(
        point.x, point.y,
        viewHeight = 1.5,
        angle = 0,
        fieldOfView = 2*math.Pi ,
        altitude = Double.NegativeInfinity)

      val viewshed = layer.viewshed(Seq(viewPoint))

      // Delete layer at zoom level 0 if it exists
      if(attributeStore.layerExists(LayerId(viewshedLayerName, 0))) {
        layerDeleter.delete(LayerId(viewshedLayerName, 0))
      }

      viewshed.cache()

      // Compute a histogram of the data, we will use that for visualization
      val histogram = viewshed.histogram(20)
      println("MinMax: " + histogram.minMaxValues())
      attributeStore.write(
        LayerId(viewshedLayerName, 0),
        "histogram",
        histogram
      )

      val layerId = LayerId(viewshedLayerName, 0)
      if(attributeStore.layerExists(layerId)) { layerDeleter.delete(layerId) }
      layerWriter.write(layerId, viewshed, ZCurveKeyIndexMethod)

    } finally {
      sc.stop()
    }
  }
}
