package ch.epfl.bluebrain.nexus.storage.digest

import java.nio.file.Path
import java.time.Clock

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, Props}
import akka.stream.javadsl.Sink
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import cats.effect.Effect
import cats.effect.implicits._
import ch.epfl.bluebrain.nexus.storage.File.Digest
import ch.epfl.bluebrain.nexus.storage.digest.DigestCacheActor.Protocol._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Actor that stores a map with the digests (value) for each path (key). The map also contains timestamps of the digests being computed.
  * The digest computation is executed using a SourceQueue with parallelism defined by the ''concurrentComputations'' configuration flag.
  * Once computed, a new message is sent back to the actor with the digest to be stored in the map.
  *
  * @param computation the storage computation
  * @tparam F the effect type
  * @tparam S the source of the storage computation
  */
class DigestCacheActor[F[_]: Effect, S](computation: DigestComputation[F, S])(implicit config: DigestConfig,
                                                                              clock: Clock)
    extends Actor
    with ActorLogging {

  import context.dispatcher
  private val map         = mutable.LinkedHashMap.empty[String, Either[Long, Digest]]
  private val selfRef     = self
  private implicit val mt = ActorMaterializer()

  private val digestComputation: Flow[Compute, Option[Put], NotUsed] =
    Flow[Compute].mapAsyncUnordered(config.concurrentComputations) {
      case Compute(filePath) =>
        log.debug("Computing digest for file '{}'.", filePath)
        val future = computation(filePath, config.algorithm).toIO.unsafeToFuture()
        future.map(digest => Option(Put(filePath, digest))).recover(logAndSkip(filePath))
    }

  private val sendMessage = Sink.foreach[Put](putMsg => selfRef ! putMsg)

  private val queue =
    Source
      .queue[Compute](config.maxInQueue, OverflowStrategy.dropHead)
      .via(digestComputation)
      .collect { case Some(putMsg) => putMsg }
      .toMat(sendMessage)(Keep.left)
      .run()

  private def logAndSkip(filePath: Path): PartialFunction[Throwable, Option[Put]] = {
    case e =>
      log.error(e, "Digest computation for file '{}' failed", filePath)
      None
  }

  override def receive: Receive = {
    case Get(filePath) =>
      map.get(filePath.toString) match {
        case Some(Right(digest)) =>
          log.debug("Digest for file '{}' fetched from the cache.", filePath)
          sender() ! digest

        case Some(Left(time)) if !needsReTrigger(time) =>
          log.debug("Digest for file '{}' is being computed. Computation started {} ms ago.", filePath, now() - time)
          sender() ! Digest.empty

        case Some(Left(_)) =>
          log.warning("Digest for file '{}' is being computed but the elapsed time of '{}' expired.",
                      filePath,
                      config.retriggerAfter)
          sender() ! Digest.empty
          self ! Compute(filePath)

        case _ =>
          log.debug("Digest for file '{}' not found in the cache.", filePath)
          sender() ! Digest.empty
          self ! Compute(filePath)
      }

    case Put(filePath, digest) =>
      map += filePath.toString -> Right(digest)

      val diff = Math.max((map.size - config.maxInMemory).toInt, 0)
      removeOldest(diff)
      log.debug("Add computed digest '{}' for file '{}' to the cache.", digest, filePath)

    case compute @ Compute(filePath) =>
      if (map.contains(filePath.toString))
        log.debug("Digest for file '{}' already computed. Do nothing.", filePath)
      else {
        map += filePath.toString -> Left(now())
        val _ = queue.offer(compute).map(logQueue(compute, _))
      }

    case msg =>
      log.error("Received a message '{}' incompatible with the expected", msg)

  }

  private def removeOldest(n: Int) =
    map --= map.take(n).keySet

  private def now(): Long = clock.instant().toEpochMilli

  private def needsReTrigger(time: Long): Boolean = {
    val elapsed: FiniteDuration = (now() - time) millis

    elapsed > config.retriggerAfter
  }

  private def logQueue(compute: Compute, result: QueueOfferResult): Unit =
    result match {
      case QueueOfferResult.Dropped =>
        log.error("The computation for the file '{}' was dropped from the queue.", compute.filePath)
      case QueueOfferResult.Failure(ex) =>
        log.error(ex, "The computation for the file '{}' failed to be enqueued.", compute.filePath)
      case _ => ()
    }

}

object DigestCacheActor {

  def props[F[_]: Effect, S](computation: DigestComputation[F, S])(implicit config: DigestConfig, clock: Clock): Props =
    Props(new DigestCacheActor(computation))

  private[digest] sealed trait Protocol extends Product with Serializable
  private[digest] object Protocol {
    final case class Get(filePath: Path)                 extends Protocol
    final case class Put(filePath: Path, digest: Digest) extends Protocol
    final case class Compute(filePath: Path)             extends Protocol
  }

}
