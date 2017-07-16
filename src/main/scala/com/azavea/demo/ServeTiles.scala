package com.azavea.demo

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, MediaTypes}
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.typesafe.config._
import geotrellis.raster._
import geotrellis.raster.io._
import geotrellis.raster.render._
import geotrellis.raster.histogram.Histogram
import geotrellis.spark.{LayerId, SpatialKey}
import geotrellis.spark.io._
import geotrellis.spark.io.file._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object AkkaSystem {
  implicit val system = ActorSystem("levee-pointcloud-demo")
  implicit val materializer = ActorMaterializer()

  trait LoggerExecutor {
    protected implicit val log = Logging(system, "app")
  }
}

object ServeTiles extends Directives {
  import AkkaSystem._

  val catalogDir = {
    val config = ConfigFactory.load()
    config.getString("catalog-dir")
  }

  val attributeStore = FileAttributeStore(catalogDir)
  val tileReader = FileValueReader(attributeStore)

  // Functions for each layer that go from a color ramp to a color map
  val colorMaps: Map[String, ColorRamp => ColorMap] =
    attributeStore
      .layerIds
      .map { id => id.name }
      .distinct
      .map { layerName =>
        val histogram = attributeStore.read[Histogram[Double]](LayerId(layerName, 0), "histogram")
      val breaks = histogram.quantileBreaks(20)
      val colorMapFunc =
        { ramp: ColorRamp =>
          ramp
            .toColorMap(breaks, ColorMap.Options(fallbackColor = ramp.colors.last))
        }

        layerName -> colorMapFunc
      }
      .toMap

  def main(args: Array[String]): Unit = {
    Http().bindAndHandle(routes, "0.0.0.0", 8080)
  }

  def routes =
    pathPrefix("ping") {
      get {
        complete { "pong" }
      }
    } ~
    pathPrefix("tiles") {
      pathPrefix(Segment / IntNumber / IntNumber / IntNumber) { (layerName, zoom, x, y) =>
        val layerId = LayerId(layerName, zoom)
        val key = SpatialKey(x, y)
        val colorRamp =
          if(layerName.contains("viewshed")) { ColorRamps.Inferno }
          else { ColorRamps.Viridis }
        complete {
          Future {
            val tileOpt =
              try {
                Some(tileReader.reader[SpatialKey, Tile](layerId).read(key))
              } catch {
                case e: ValueNotFoundError =>
                  None
              }
            tileOpt.map { tile =>
              val png = tile.renderPng(colorMaps(layerName)(colorRamp))
              HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`image/png`), png.bytes))
            }
          }
        }
      }
    }
}
