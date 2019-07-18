package ch.epfl.bluebrain.nexus.storage.digest

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.commons.test.Randomness
import ch.epfl.bluebrain.nexus.storage.File.Digest
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig
import monix.eval.Task
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfter, Inspectors, Matchers, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global

class DigestCacheSpec
    extends TestKit(ActorSystem("DigestCacheSpec"))
    with WordSpecLike
    with Matchers
    with IdiomaticMockito
    with BeforeAndAfter
    with Inspectors
    with Randomness
    with Eventually
    with ScalaFutures {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(12 second, 100 milliseconds)

  implicit val config                                       = DigestConfig("SHA-256", maxInMemory = 10, concurrentComputations = 3, 20, 5 seconds)
  implicit val computation: DigestComputation[Task, String] = mock[DigestComputation[Task, String]]
  implicit val timeout                                      = Timeout(1 minute)

  before {
    Mockito.reset(computation)
  }

  trait Ctx {
    val digestCache = DigestCache[Task, String]
    val path: Path  = Paths.get(genString())
    val digest      = Digest(config.algorithm, genString())
  }

  "A DigestCache" should {

    "trigger a computation and fetch file after" in new Ctx {
      computation(path, config.algorithm) shouldReturn Task(digest)
      digestCache.asyncComputePut(path, config.algorithm)
      eventually(computation(path, config.algorithm) wasCalled once)
      digestCache.get(path).runToFuture.futureValue shouldEqual digest
      computation(path, config.algorithm) wasCalled once
    }

    "get file that triggers digest computation" in new Ctx {
      computation(path, config.algorithm) shouldReturn Task(digest)
      digestCache.get(path).runToFuture.futureValue shouldEqual Digest.empty
      computation(path, config.algorithm) wasCalled once
      digestCache.get(path).runToFuture.futureValue shouldEqual digest
      computation(path, config.algorithm) wasCalled once
    }

    "verify 2 concurrent computations" in new Ctx {
      val list = List.tabulate(10) { i =>
        Paths.get(i.toString) -> Digest(config.algorithm, i.toString)
      }
      val time = System.currentTimeMillis()

      val counter = new AtomicInteger(0)
      forAll(list) {
        case (path, digest) =>
          computation(path, config.algorithm) shouldReturn
            Task.deferFuture(Future {
              Thread.sleep(1000)
              counter.incrementAndGet()
              digest
            })
          digestCache.get(path).runToFuture.futureValue shouldEqual Digest.empty
      }

      forAll(list) {
        case (path, _) =>
          eventually(computation(path, config.algorithm) wasCalled once)
      }
      eventually(counter.get() shouldEqual 10)
      val diff = System.currentTimeMillis() - time
      diff should be > 4000L
      diff should be < 5000L

      forAll(list) {
        case (path, digest) =>
          digestCache.get(path).runToFuture.futureValue shouldEqual digest
      }
    }

    "verify remove oldest" in new Ctx {
      val list = List.tabulate(20) { i =>
        Paths.get(i.toString) -> Digest(config.algorithm, i.toString)
      }
      val counter = new AtomicInteger(0)

      forAll(list) {
        case (path, digest) =>
          computation(path, config.algorithm) shouldReturn Task {
            counter.incrementAndGet()
            digest
          }
          digestCache.get(path).runToFuture.futureValue shouldEqual Digest.empty
      }

      eventually(counter.get() shouldEqual 20)

      forAll(list.takeRight(10)) {
        case (path, digest) =>
          digestCache.get(path).runToFuture.futureValue shouldEqual digest
      }

      forAll(list.take(10)) {
        case (path, _) =>
          digestCache.get(path).runToFuture.futureValue shouldEqual Digest.empty
      }
    }

    "verify failure" in new Ctx {
      val list = List.tabulate(5) { i =>
        Paths.get(i.toString) -> Digest(config.algorithm, i.toString)
      }

      forAll(list) {
        case (path, digest) =>
          if (digest.value == "0")
            computation(path, config.algorithm) shouldReturn Task.raiseError(new RuntimeException)
          else
            computation(path, config.algorithm) shouldReturn Task(digest)

          digestCache.get(path).runToFuture.futureValue shouldEqual Digest.empty
      }

      forAll(list.drop(1)) {
        case (path, digest) =>
          eventually(digestCache.get(path).runToFuture.futureValue shouldEqual digest)
      }
    }
  }
}
