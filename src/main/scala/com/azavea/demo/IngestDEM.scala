package com.azavea.demo

import io.pdal._
import com.typesafe.config._
import com.vividsolutions.jts.geom.Coordinate
import geotrellis.pointcloud.pipeline.{Read, ReprojectionFilter}
import geotrellis.pointcloud.spark.io.hadoop._
import geotrellis.pointcloud.spark.triangulation._
import geotrellis.proj4.WebMercator
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
import geotrellis.vector._
import org.apache.hadoop.fs.Path
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import spire.syntax.cfor._

import scala.collection.mutable

/** Read in point cloud data, turn it into DEM via a TIN algorithm,
  * and save the resulting raster layer out into a GeoTrellis layer,
  * that we can then read and query through GeoTrellis functionality.
  */
object IngestDEM {
  def main(args: Array[String]): Unit = {
    val numPartitions = 500 // Can be tuned for performance.
    val layerName = "levee-dem" // GeoTrellis Layer name
    val MAX_ZOOM = 18

    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("LeveeTest")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)

    try {
      // Get test input file directory from configuration
      val (inputPath, catalogDir) = {
        val config = ConfigFactory.load()
        val dataDir = config.getString("test-data-dir")
        (new Path(s"file://$dataDir"), config.getString("catalog-dir"))
      }

      val pipeline =
        Read("") ~
          ReprojectionFilter("EPSG:3857") // Reproject to EPSG:3857 (WebMercator), for display.

      val inputRdd =
        HadoopPointCloudRDD(
          inputPath,
          HadoopPointCloudRDD.Options.DEFAULT.copy(pipeline = pipeline, dimTypes = Option(List("X", "Y", "Z")))
        )

      // Create the layout we want for our raster layer.
      // Hard coding the max zoom here, this could be computed otherwise.
      val layoutScheme = ZoomedLayoutScheme(WebMercator)
      val LayoutLevel(zoom, layout) = layoutScheme.levelForZoom(MAX_ZOOM)
      val mapTransform = layout.mapTransform

      // Cut the points into the SpatialKeys that represent the tiles
      // we will be rasterizing them to.
      // This technique should eventually become library functionality.
      val cut: RDD[(SpatialKey, Array[Coordinate])] =
        inputRdd
          .flatMap { case (header, pointClouds) =>
            var lastKey: SpatialKey = null
            val keysToPoints = mutable.Map[SpatialKey, mutable.ArrayBuffer[Coordinate]]()

            for (pointCloud <- pointClouds) {
              val len = pointCloud.length
              cfor(0)(_ < len, _ + 1) { i =>
                val x = pointCloud.getX(i)
                val y = pointCloud.getY(i)
                val z = pointCloud.getZ(i)
                val p = new Coordinate(x, y, z)
                val key = mapTransform(x, y)
                if (key == lastKey) {
                  keysToPoints(lastKey) += p
                } else if (keysToPoints.contains(key)) {
                  keysToPoints(key) += p
                  lastKey = key
                } else {
                  keysToPoints(key) = mutable.ArrayBuffer(p)
                  lastKey = key
                }
              }
            }

            keysToPoints.map { case (k, v) => (k, v.toArray) }
          }
          .reduceByKey({ (p1, p2) => p1 ++ p2 }, numPartitions)
          .filter { _._2.length > 2 }

      // BoundaryStitch solves the problem of triangulations-per-tile
      // not taking into account the the points that surround each tile
      // boundary.
      val tiles: RDD[(SpatialKey, Tile)] =
        TinToDem.boundaryStitch(cut, layout, Extent(0,0,0,0)) // Extent argument required but unused, bug

      val bounds = Bounds.fromRdd(tiles)
      val extent =
        bounds match {
          case kb: KeyBounds[SpatialKey] => mapTransform(kb.toGridBounds)
          case EmptyBounds => sys.error("empty rdd")
        }
      val md = TileLayerMetadata[SpatialKey](DoubleConstantNoDataCellType, layout, extent, WebMercator, bounds)

      val layer = ContextRDD(tiles, md)

      /**** Write the DEM raster layer to the GeoTrellis file backend *****/

      val writer = FileLayerWriter(catalogDir)
      val attributeStore = writer.attributeStore

      // Compute a histogram of the data, we will use that for visualization
      val histogram = layer.histogram(20)
      attributeStore.write(
        LayerId(layerName, 0),
        "histogram",
        histogram
      )

      Pyramid.upLevels(
        layer,
        layoutScheme,
        MAX_ZOOM,
        0,
        Pyramid.Options(resampleMethod = Bilinear)
      ) { (pyramidLayer, pyramidZoomLevel) =>
        writer.write(LayerId(layerName, pyramidZoomLevel), pyramidLayer, ZCurveKeyIndexMethod)
      }
    } finally {
      sc.stop()
    }
  }
}
