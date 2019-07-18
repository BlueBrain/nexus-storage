package ch.epfl.bluebrain.nexus.storage.digest

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.storage.File.Digest
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import ch.epfl.bluebrain.nexus.storage.{fileSource, folderSource, AkkaSource}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

trait DigestComputation[F[_], Source] {

  /**
    * Given a path and an algorithm, generates a Digest
    *
    * @param path      the path to the file
    * @param algorithm the digest algorithm
    * @return a computed digest, wrapped on the effect type F
    */
  def apply(path: Path, algorithm: String): F[Digest]
}

object DigestComputation {

  /**
    * A digest for a source of type AkkaSource
    *
    * @tparam F the effect type
    * @return a DigestComputation implemented for a source of type AkkaSource
    */
  implicit def akkaDigest[F[_]](implicit ec: ExecutionContext,
                                mt: Materializer,
                                F: Effect[F]): DigestComputation[F, AkkaSource] =
    (path: Path, algorithm: String) => {
      if (!Files.exists(path)) F.raiseError(InternalError(s"Path not found '$path'"))
      else
        Try(MessageDigest.getInstance(algorithm)) match {
          case Success(msgDigest) =>
            val sink = Sink
              .fold(msgDigest)((digest, currentBytes: ByteString) => {
                digest.update(currentBytes.asByteBuffer)
                digest
              })
              .mapMaterializedValue(_.map(dig => Digest(dig.getAlgorithm, dig.digest().map("%02x".format(_)).mkString)))
            val source = if (Files.isDirectory(path)) folderSource(path) else fileSource(path)
            source.runWith(sink).to[F]
          case Failure(_) => F.raiseError(InternalError(s"Invalid algorithm '$algorithm'."))
        }

    }
}
