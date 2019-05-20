package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, HttpEntity, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.tracing._
import ch.epfl.bluebrain.nexus.storage.routes.StorageDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile._
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, Storages}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class StorageRoutes(implicit storages: Storages[AkkaSource]) {

  def routes: Route =
    // Consume buckets/{name}/
    (pathPrefix("buckets") & pathPrefix(Segment)) { name =>
      bucketExists(name).apply { implicit bucketExistsEvidence =>
        concat(
          // Check bucket
          (head & trace("check bucket")) {
            complete(OK)
          },
          // Consume files
          pathPrefix("files") {
            concat(
              // consume path
              (put & extractRelativeFilePath(name)) { relativePath =>
                pathNotExists(name, relativePath).apply { implicit pathNotExistEvidence =>
                  // Upload file
                  (fileUpload("file") & trace("create file")) {
                    case (_, source) =>
                      complete(Created -> storages.createFile[Task](name, relativePath, source).runToFuture)
                  }
                }
              },
              // consume path
              (put & extractRelativePath(name)) { destRelativePath =>
                // Link file/dir
                (entity(as[LinkFile]) & trace("link file")) {
                  case LinkFile(source) =>
                    validatePath(name, source) {
                      complete(storages.moveFile[Task](name, source, destRelativePath).runWithStatus(OK))
                    }
                }
              },
              // Get file
              (get & extractRelativePath(name)) { relativePath =>
                (pathExists(name, relativePath) & trace("fetch file")) { implicit pathExistsEvidence =>
                  storages.getFile(name, relativePath) match {
                    case Right((source, Some(filename))) => sourceEntity(source, `application/octet-stream`, filename)
                    case Right((source, None))           => sourceEntity(source, `application/gnutar`, s"dir-$name.tgz")
                    case Left(err)                       => complete(err)
                  }
                }
              }
            )
          }
        )
      }
    }

  private def sourceEntity(source: AkkaSource, contentType: ContentType, filename: String): Route =
    (respondWithHeaders(RawHeader("Content-Disposition", s"attachment; filename*=UTF-8''$filename")) & encodeResponse) {
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

  final def apply(storages: Storages[AkkaSource]): StorageRoutes =
    new StorageRoutes()(storages)
}
