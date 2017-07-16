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

import scala.collection.mutable

import IterativeViewshed._

/** Compute a viewshed over the DEM data.
  */
object ComputeViewshed {
  def main(args: Array[String]): Unit = {
    val numPartitions = 50
    val layerName = "levee-dem" // GeoTrellis Layer name
    val MAX_ZOOM = 18
    val viewshedLayerName = "levee-viewshed"

    // A point along the inside of the levee on the south west side.
    // Need to reproject to the CRS of the raster layer.
    val point =
      Point(-90.252800, 29.343032)
        .reproject(LatLng, WebMercator)

    // Transform to a coordinate, representing
    // viewing 1.5 meters above the surface.
    val coord =
      new Coordinate(point.x, point.y, 1.5)

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

      val layer =
        layerReader.read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](LayerId(layerName, MAX_ZOOM))

      val viewshed =
        layer.viewshed(Seq[Coordinate](coord))

      // Delete layer at zoom level 0 if it exists
      if(attributeStore.layerExists(LayerId(viewshedLayerName, 0))) {
        layerDeleter.delete(LayerId(viewshedLayerName, 0))
      }

      // Compute a histogram of the data, we will use that for visualization
      val histogram = viewshed.histogram(20)
      attributeStore.write(
        LayerId(viewshedLayerName, 0),
        "histogram",
        histogram
      )

      // Save off the viewshed, in a layer pyramid so we can see it on the map.
      Pyramid.upLevels(
        viewshed,
        ZoomedLayoutScheme(WebMercator),
        MAX_ZOOM,
        0,
        Pyramid.Options(resampleMethod = Bilinear)
      ) { (pyramidLayer, pyramidZoomLevel) =>
        val layerId = LayerId(viewshedLayerName, pyramidZoomLevel)
        if(attributeStore.layerExists(layerId)) { layerDeleter.delete(layerId) }
        layerWriter.write(layerId, pyramidLayer, ZCurveKeyIndexMethod)
      }

    } finally {
      sc.stop()
    }
  }
}
