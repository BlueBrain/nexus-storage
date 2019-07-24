package ch.epfl.bluebrain.nexus.storage

import org.scalatest._

import scala.sys.process._

class StringProcessLoggerSpec extends WordSpecLike with Matchers {
  "A StringProcessLogger" should {
    "log stdout" in {
      val process  = Process(List("echo", "-n", "Hello", "world!"))
      val logger   = new StringProcessLogger("echo")
      val exitCode = process ! logger
      exitCode shouldEqual 0
      logger.toString shouldEqual "Hello world!"
    }

    "log stderr" in {
      val process  = Process(List("cat", "/"))
      val logger   = new StringProcessLogger("cat")
      val exitCode = process ! logger
      exitCode should not be 0
      logger.toString shouldEqual "cat: /: Is a directory"
    }
  }
}
