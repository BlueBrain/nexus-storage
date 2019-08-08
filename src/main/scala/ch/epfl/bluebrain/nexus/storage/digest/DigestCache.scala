package ch.epfl.bluebrain.nexus.storage.digest

import java.nio.file.Path
import java.time.Clock

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask, AskTimeoutException}
import akka.util.Timeout
import cats.effect.{Effect, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File.Digest
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, OperationTimedOut}
import ch.epfl.bluebrain.nexus.storage.digest.DigestCacheActor.Protocol._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig
import journal.Logger

import scala.util.control.NonFatal

trait DigestCache[F[_]] {

  /**
    * Fetches the digest for the provided absFilePath.
    * If the digest is being computed or is going to be computed, a Digest.empty is returned
    *
    * @param filePath the absolute file path
    * @return the digest wrapped in the effect type F
    */
  def get(filePath: Path): F[Digest]

  /**
    * Computes the digest and stores it asynchronously on the cache
    *
    * @param filePath  the absolute file path
    * @param algorithm the digest algorithm
    */
  def asyncComputePut(filePath: Path, algorithm: String): Unit
}

object DigestCache {
  private[this] val logger = Logger[this.type]

  def apply[F[_], Source](
      implicit system: ActorSystem,
      clock: Clock,
      tm: Timeout,
      F: Effect[F],
      computation: DigestComputation[F, Source],
      config: DigestConfig
  ): DigestCache[F] =
    apply(system.actorOf(DigestCacheActor.props(computation)))

  private[digest] def apply[F[_]](
      underlying: ActorRef
  )(implicit system: ActorSystem, tm: Timeout, F: Effect[F]): DigestCache[F] =
    new DigestCache[F] {

      override def get(filePath: Path): F[Digest] =
        IO.fromFuture(IO.shift(system.dispatcher) >> IO(underlying ? Get(filePath)))
          .to[F]
          .flatMap[Digest] {
            case digest: Digest => F.pure(digest)
            case other =>
              logger.error(s"Received unexpected reply from the digest cache: '$other'")
              F.raiseError(InternalError("Unexpected reply from the digest cache"))
          }
          .recoverWith {
            case _: AskTimeoutException => F.raiseError(OperationTimedOut("reply from the digest cache timed out"))
            case NonFatal(th) =>
              logger.error("Exception caught while exchanging messages with the digest cache", th)
              F.raiseError(InternalError("Exception caught while exchanging messages with the digest cache"))
          }

      override def asyncComputePut(filePath: Path, algorithm: String): Unit =
        underlying ! Compute(filePath)

    }
}
