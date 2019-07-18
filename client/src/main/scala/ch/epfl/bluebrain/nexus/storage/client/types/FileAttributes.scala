package ch.epfl.bluebrain.nexus.storage.client.types

import akka.http.scaladsl.model.Uri
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._

import scala.util.Try

// $COVERAGE-OFF$
/**
  * Holds all the metadata information related to the file.
  *
  * @param location  the file location
  * @param bytes     the size of the file file in bytes
  */
final case class FileAttributes(location: Uri, bytes: Long)
object FileAttributes {

  private implicit val config: Configuration =
    Configuration.default
      .copy(transformMemberNames = {
        case "@context" => "@context"
        case key        => s"_$key"
      })

  private implicit val decUri: Decoder[Uri] =
    Decoder.decodeString.emapTry(s => Try(Uri(s)))

  implicit val fileAttrDecoder: Decoder[FileAttributes] = deriveDecoder[FileAttributes]

  /**
    * Digest related information of the file
    *
    * @param algorithm the algorithm used in order to compute the digest
    * @param value     the actual value of the digest of the file
    */
  final case class Digest(algorithm: String, value: String)

  object Digest {
    val empty: Digest = Digest("", "")

    implicit val digestDecoder: Decoder[Digest] = deriveDecoder[Digest]
  }
}
// $COVERAGE-ON$
