package com.azavea.demo

import io.pdal._
import com.typesafe.config._
import com.vividsolutions.jts.geom.Coordinate
import geotrellis.pointcloud.pipeline._
import geotrellis.pointcloud.spark.io.hadoop._
import geotrellis.pointcloud.spark.triangulation._
import geotrellis.proj4._
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

import java.io.File
import scala.collection.mutable

case class IngestConfig(
  inputDir: String = "",
  catalogDir: String = "",
  layerName: String = "ingested-dsm",
  cellSize: Double = Double.NaN,
  tileSize: Int = 512,
  extent: Option[Extent] = None,
  crs: Option[String] = None,
  numPartitions: Option[Int] = None
)

/** Read in point cloud data, turn it into DEM via a TIN algorithm,
  * and save the resulting raster layer out into a GeoTrellis layer,
  * that we can then read and query through GeoTrellis functionality.
  */
object IngestDEM {
  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[IngestConfig]("ingest-dem") {
      head("spark-submit ... {JARNAME}.jar")

      opt[String]('i', "input")
        .action( (x, c) => c.copy(inputDir = x) )
        .text("Absolute path to the directory containing the point cloud files to be ingested ($WORK)")
        .validate { s =>
          val f = new File(s)
          if(!f.exists) { Left(s"Directory $f does not exist.") }
          if(!f.isDirectory) { Left(s"$f is not a directory.") }
          Right(Unit)
      }

      opt[String]('c', "catalog")
        .action( (x, c) => c.copy(catalogDir = x) )
        .text("Absolute path to the output catalog directory ($RESULT/catalog)")
        .validate { s =>
          val f = new File(s)
          if(!f.exists) {
            f.mkdirs()
          }
          if(!f.isDirectory) { Left(s"$f is not a directory.") }
          Right(Unit)
        }

      opt[Seq[Double]]('e', "extent")
        .action { (x, c) =>
          val arr = x.toArray
          c.copy(extent = Some(Extent(arr(0), arr(1), arr(2), arr(3))))
        }
        .validate { x =>
          val arr = x.toArray
          if(arr.size != 4) { failure("Extent must be 4 elements long of xmin,ymin,xmax,ymax") }
          if(arr(0) >= arr(2)) { failure("xmax must be larger than xmin") }
          if(arr(1) >= arr(3)) { failure("ymax must be larger than ymin") }
          success
        }
        .optional

      opt[Double]('s', "cellSize")
        .action( (x, c) => c.copy(cellSize = x))
        .text("""Cell size in map units of resulting raster layer.""")

      opt[Int]('t', "tileSize")
        .action( (x, c) => c.copy(tileSize = x))
        .text("""Number of cols and rows per tile of result raster. Default is 512""")
        .optional

      opt[String]('n', "layername")
        .action( (x, c) => c.copy(layerName = x) )
        .text("Output GeoTrellis layer name.")
        .optional

      opt[String]("crs")
        .action( (x, c) => c.copy(crs = Some(x)) )
        .text("""CRS for result layer (e.g. "EPSG:3857")""")
        .optional

      opt[String]("numPartitions")
        .action( (x, c) => c.copy(crs = Some(x)) )
        .text("""Number of Partitions to use (tweak for performance)""")
        .optional
    }

    parser.parse(args, IngestConfig()) match {
      case Some(config) =>
        execute(config)
      case None =>
        sys.exit(2)
    }
  }

  def execute(config: IngestConfig): Unit = {
    val IngestConfig(
      dataDir,
      catalogDir,
      layerName,
      cellSize,
      tileSize,
      _,
      _,
      _
    ) = config

    val numPartitions =
      config.numPartitions match {
        case Some(n) => n
        case None => 500
      }

    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("Ingest DEM")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)

    try {
      val pipeline: PipelineConstructor =
        config.crs match {
          case Some(name) =>
            Read("") ~ ReprojectionFilter("EPSG:32615")
          case None => Read("")
        }

      val inputRdd =
        HadoopPointCloudRDD(
          new Path(s"file://$dataDir"),
          HadoopPointCloudRDD.Options.DEFAULT.copy(pipeline = pipeline, dimTypes = Option(List("X", "Y", "Z")))
        )

      // If we passed in an extent, set the layout to that extent.
      // If not, calculate the extent from the points.
      val layout =
        config.extent match {
          case Some(e) =>
            LayoutDefinition(GridExtent(e, CellSize(cellSize, cellSize)), tileSize, tileSize)
          case None =>
            val (globalXmin, globalYmin, globalXmax, globalYmax) =
              inputRdd
                .map { case (_, pointClouds) =>
                  var (xmin, ymin, xmax, ymax) = (Double.MaxValue, Double.MaxValue, Double.MinValue, Double.MinValue)
                  pointClouds.foreach { pointCloud =>
                    val len = pointCloud.length
                    cfor(0)(_ < len, _ + 1) { i =>
                      val x = pointCloud.getX(i)
                      val y = pointCloud.getY(i)
                      if(x < xmin) { xmin = x }
                      if(x > xmax) { xmax = x }
                      if(y < ymin) { ymin = y }
                      if(y > ymax) { ymax = y }
                    }
                  }
                  (xmin, ymin, xmax, ymax)
                }
                .reduce { (a, b) =>
                  (math.min(a._1, b._1),
                   math.min(a._2, b._2),
                   math.max(a._3, b._3),
                   math.max(a._4, b._4))
                }
            val (dx, dy) = (globalXmax - globalXmin, globalYmax - globalYmin)
            val e = Extent(globalXmin - dx / 8.0, globalYmin - dy / 8.0, globalXmax + dx / 8.0, globalYmax + dy / 8.0)
            LayoutDefinition(GridExtent(e, CellSize(cellSize, cellSize)), tileSize, tileSize)
        }

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

      writer.write(LayerId(layerName, 0), layer, ZCurveKeyIndexMethod)
    } finally {
      sc.stop()
    }
  }
}
