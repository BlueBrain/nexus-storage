package ch.epfl.bluebrain.nexus.storage.routes

//import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, HttpEntity, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.storage.config.AppConfig
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.storage.routes.StorageDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile._
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, Storages}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class StorageRoutes()(implicit storages: Storages[Task, AkkaSource], hc: HttpConfig) {

  def routes: Route =
    // Consume buckets/{name}/
    pathPrefix("buckets" / Segment) { name =>
      concat(
        // Check bucket
        (head & pathEndOrSingleSlash) {
          operationName(s"/${hc.prefix}/buckets/{}") {
            bucketExists(name).apply { _ =>
              complete(OK)
            }
          }
        },
        // Consume files
        (pathPrefix("files") & extractRelativePath(name)) { relativePath =>
          operationName(s"/${hc.prefix}/buckets/{}/files/{}") {
            bucketExists(name).apply {
              implicit bucketExistsEvidence =>
                concat(
                  put {
                    pathNotExists(name, relativePath).apply { implicit pathNotExistEvidence =>
                      // Upload file
                      fileUpload("file") {
                        case (_, source) =>
                          complete(Created -> storages.createFile(name, relativePath, source).runToFuture)
                      }
                    }
                  },
                  put {
                    // Link file/dir
                    entity(as[LinkFile]) {
                      case LinkFile(source) =>
                        validatePath(name, source) {
                          complete(storages.moveFile(name, source, relativePath).runWithStatus(OK))
                        }
                    }
                  },
                  // Get file
                  get {
                    pathExists(name, relativePath).apply { implicit pathExistsEvidence =>
                      storages.getFile(name, relativePath) match {
                        case Right((source, Some(filename))) =>
                          sourceEntity(source, `application/octet-stream`, filename)
                        case Right((source, None)) => sourceEntity(source, `application/gnutar`, s"dir-$name.tgz")
                        case Left(err)             => complete(err)
                      }
                    }
                  }
                )
            }
          }
        },
        // Consume digests
        (pathPrefix("digests") & extractRelativePath(name)) { relativePath =>
          operationName(s"/${hc.prefix}/buckets/{}/digests/{}") {
            bucketExists(name).apply { implicit bucketExistsEvidence =>
              // Get file digest
              get {
                pathExists(name, relativePath).apply { implicit pathExistsEvidence =>
                  complete(OK -> storages.getDigest(name, relativePath).runToFuture)
                }
              }
            }
          }
        }
      )
    }

  private def sourceEntity(source: AkkaSource, contentType: ContentType, filename: String): Route =
    (respondWithHeaders(RawHeader("Content-Disposition", s"attachment; filename*=UTF-8''$filename"))) {
      complete(HttpEntity(contentType, source))
    }
}

object StorageRoutes {

  /**
    * Link file request.
    *
    * @param source    the relative location of the file/dir
    */
  private[routes] final case class LinkFile(source: Uri.Path)

  private[routes] object LinkFile {
    import ch.epfl.bluebrain.nexus.storage._
    implicit val linkFileDec: Decoder[LinkFile] = deriveDecoder[LinkFile]
    implicit val linkFileEnc: Encoder[LinkFile] = deriveEncoder[LinkFile]
  }

  final def apply(storages: Storages[Task, AkkaSource])(implicit cfg: AppConfig): StorageRoutes = {
    implicit val s = storages
    new StorageRoutes()
  }
}
