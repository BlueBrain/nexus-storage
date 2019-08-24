package ch.epfl.bluebrain.nexus.storage.digest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.test.io.IOValues
import ch.epfl.bluebrain.nexus.storage.File.Digest
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import org.scalatest.{Matchers, WordSpecLike}

class DigestComputationSpec
    extends TestKit(ActorSystem("DigestComputationSpec"))
    with WordSpecLike
    with Matchers
    with IOValues {

  private implicit val ec = system.dispatcher
  private implicit val mt = ActorMaterializer()

  private trait Ctx {
    val path           = Files.createTempFile("storage-test", "")
    val (text, digest) = "something" -> "3fc9b689459d738f8c88a3a48aa9e33542016b7a4052e001aaa536fca74813cb"
  }

  "A digest computation" should {
    val computation = DigestComputation.akkaDigest[IO]
    val alg         = "SHA-256"

    "compute the digest" in new Ctx {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      computation(path, alg).ioValue shouldEqual Digest(alg, digest)
      Files.deleteIfExists(path)
    }

    "fail when algorithm is wrong" in new Ctx {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      computation(path, "wrong-alg").failed[InternalError]
    }

    "fail when file does not exists" in new Ctx {
      computation(Paths.get("/tmp/non/existing"), alg).failed[InternalError]
    }
  }

}
