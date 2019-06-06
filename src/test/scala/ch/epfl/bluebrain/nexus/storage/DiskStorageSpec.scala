package ch.epfl.bluebrain.nexus.storage

import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Compression, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.test.Randomness
import ch.epfl.bluebrain.nexus.commons.test.io.IOEitherValues
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.PathInvalid
import ch.epfl.bluebrain.nexus.storage.Storages.DiskStorage
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence.{PathDoesNotExist, PathExists}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence.{BucketDoesNotExist, BucketExists}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.StorageConfig
import org.apache.commons.io.FileUtils
import org.scalatest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class DiskStorageSpec
    extends TestKit(ActorSystem("DiskStorageSpec"))
    with WordSpecLike
    with Matchers
    with Randomness
    with IOEitherValues
    with BeforeAndAfterAll
    with EitherValues
    with OptionValues
    with Inspectors {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(3 second, 15 milliseconds)

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mt: Materializer     = ActorMaterializer()

  val rootPath = Files.createTempDirectory("storage-test")
  val config   = StorageConfig(rootPath, Paths.get("nexus"), "SHA-256")
  val storage  = new DiskStorage(config)

  override def afterAll(): Unit = {
    FileUtils.deleteDirectory(rootPath.toFile)
  }

  trait AbsoluteDirectoryCreated {
    val name         = genString()
    val baseRootPath = rootPath.resolve(name)
    val basePath     = baseRootPath.resolve(config.protectedDirectory)
    Files.createDirectories(rootPath.resolve(name).resolve(config.protectedDirectory))
  }

  trait RelativeDirectoryCreated extends AbsoluteDirectoryCreated {
    val relativeDir        = s"some/${genString()}"
    val relativeFileString = s"$relativeDir/file.txt"
    val relativeFilePath   = Uri.Path(relativeFileString)
    val absoluteFilePath   = basePath.resolve(Paths.get(relativeFilePath.toString()))
    Files.createDirectories(absoluteFilePath.getParent)
    implicit val bucketExistsEvidence = BucketExists
  }

  "A disk storage bundle" when {

    "checking storage" should {

      "fail when bucket directory does not exists" in {
        val name = genString()
        storage.exists(name) shouldBe a[BucketDoesNotExist]
      }

      "fail when bucket is not a directory" in {
        val name      = genString()
        val directory = Files.createDirectories(rootPath.resolve(name))
        Files.createFile(directory.resolve(config.protectedDirectory))
        storage.exists(name) shouldBe a[BucketDoesNotExist]
      }

      "fail when bucket directory does not have the write access" in {
        val name      = genString()
        val directory = rootPath.resolve(name).resolve(config.protectedDirectory)
        Files.createDirectories(directory)
        directory.toFile.setWritable(false)
        storage.exists(name) shouldBe a[BucketDoesNotExist]
      }

      "fail when the bucket goes out of the scope" in new AbsoluteDirectoryCreated {
        val invalid = List("one/two", "../other", "..", "one/two/three")
        forAll(invalid) {
          storage.exists(_) shouldBe a[BucketDoesNotExist]
        }
      }

      "pass" in new AbsoluteDirectoryCreated {
        storage.exists(name) shouldBe a[BucketExists]
      }
    }

    "checking path existence" should {

      "exists" in new AbsoluteDirectoryCreated {
        val relativeFileString = "some/file.txt"
        val relativeFilePath   = Uri.Path(relativeFileString)
        Files.createDirectories(basePath.resolve("some"))
        val filePath = basePath.resolve(relativeFileString)
        Files.createFile(filePath)
        storage.pathExists(name, relativeFilePath) shouldBe a[PathExists]
      }

      "exists outside scope" in new AbsoluteDirectoryCreated {
        val relativeFileString = "../some/file.txt"
        val relativeFilePath   = Uri.Path(relativeFileString)
        Files.createDirectories(basePath.resolve("..").resolve("some").normalize())
        val filePath = basePath.resolve(relativeFileString).normalize()
        Files.createFile(filePath)
        storage.pathExists(name, relativeFilePath) shouldBe a[PathDoesNotExist]
      }

      "not exists" in new RelativeDirectoryCreated {
        storage.pathExists(name, relativeFilePath) shouldBe a[PathDoesNotExist]
      }
    }

    "creating a file" should {

      "fail when destination is out of bucket scope" in new RelativeDirectoryCreated {
        val content                   = "some content"
        val source: AkkaSource        = Source.single(ByteString(content))
        implicit val pathDoesNotExist = PathDoesNotExist
        val relativePath              = Uri.Path("some/../../path")
        storage.createFile[IO](name, relativePath, source).unsafeToFuture().failed.futureValue shouldEqual
          PathInvalid(name, relativePath)
      }

      "pass" in new RelativeDirectoryCreated {
        val content                   = "some content"
        val source: AkkaSource        = Source.single(ByteString(content))
        val digest                    = Digest("SHA-256", "290f493c44f5d63d06b374d0a5abd292fae38b92cab2fae5efefe1b0e9347f56")
        implicit val pathDoesNotExist = PathDoesNotExist
        storage.createFile[IO](name, relativeFilePath, source).ioValue shouldEqual
          FileAttributes(s"file://${absoluteFilePath.toString}", 12L, digest)
      }
    }

    "linking" should {
      implicit val bucketExistsEvidence = BucketExists

      "fail when source does not exists" in new AbsoluteDirectoryCreated {
        val source = genString()
        storage.moveFile[IO](name, Uri.Path(source), Uri.Path(genString())).rejected[PathNotFound] shouldEqual
          PathNotFound(name, Uri.Path(source))
      }

      "fail when source is inside protected directory" in new AbsoluteDirectoryCreated {
        val file         = config.protectedDirectory + "/other.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)
        Files.write(absoluteFile, "something".getBytes(StandardCharsets.UTF_8))

        storage.moveFile[IO](name, Uri.Path(file), Uri.Path(genString())).rejected[PathNotFound] shouldEqual
          PathNotFound(name, Uri.Path(file))
      }

      "fail when destination already exists" in new AbsoluteDirectoryCreated {
        val file         = s"some/folder/myfile.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)
        Files.write(absoluteFile, "something".getBytes(StandardCharsets.UTF_8))

        val fileDest = basePath.resolve(Paths.get("myfile.txt"))
        Files.write(fileDest, "something".getBytes(StandardCharsets.UTF_8))
        storage
          .moveFile[IO](name, Uri.Path(file), Uri.Path("myfile.txt"))
          .rejected[PathAlreadyExists] shouldEqual
          PathAlreadyExists(name, Uri.Path("myfile.txt"))
      }

      "fail when destination is out of bucket scope" in new AbsoluteDirectoryCreated {
        val file         = s"some/folder/myfile.txt"
        val dest         = Uri.Path("../some/other.txt")
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)

        val content = "some content"
        Files.write(absoluteFile, content.getBytes(StandardCharsets.UTF_8))

        storage.moveFile[IO](name, Uri.Path(file), dest).unsafeToFuture().failed.futureValue shouldEqual
          PathInvalid(name, dest)
        Files.exists(absoluteFile) shouldEqual true
      }

      "pass on file" in new AbsoluteDirectoryCreated {
        val file         = s"some/folder/myfile.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)

        val content = "some content"
        Files.write(absoluteFile, content.getBytes(StandardCharsets.UTF_8))

        val digest = Digest("SHA-256", "290f493c44f5d63d06b374d0a5abd292fae38b92cab2fae5efefe1b0e9347f56")
        storage.moveFile[IO](name, Uri.Path(file), Uri.Path("some/other.txt")).accepted shouldEqual
          FileAttributes(s"file://${basePath.resolve("some/other.txt")}", 12L, digest)
        Files.exists(absoluteFile) shouldEqual false
        Files.exists(basePath.resolve("some/other.txt")) shouldEqual true
      }

      "pass on directory" in new AbsoluteDirectoryCreated {
        val dir         = s"some/folder"
        val absoluteDir = baseRootPath.resolve(Paths.get(dir.toString))
        Files.createDirectories(absoluteDir)

        val absoluteFile = absoluteDir.resolve(Paths.get("myfile.txt"))
        val content      = "some content"
        Files.write(absoluteFile, content.getBytes(StandardCharsets.UTF_8))

        val result      = storage.moveFile[IO](name, Uri.Path(dir), Uri.Path("some/other")).accepted
        val resolvedDir = basePath.resolve("some/other")
        result shouldEqual FileAttributes(s"file://$resolvedDir", Files.size(resolvedDir), result.digest)
        Files.exists(absoluteDir) shouldEqual false
        Files.exists(absoluteFile) shouldEqual false
        Files.exists(resolvedDir) shouldEqual true
        Files.exists(basePath.resolve("some/other/myfile.txt")) shouldEqual true
      }
    }

    "fetching" should {

      implicit val pathExistsEvidence = PathExists

      "fail when it does not exists" in new RelativeDirectoryCreated {
        storage.getFile(name, relativeFilePath).left.value shouldEqual
          PathNotFound(name, relativeFilePath)
      }

      "pass with file" in new RelativeDirectoryCreated {
        val content = "some content"
        Files.write(absoluteFilePath, content.getBytes(StandardCharsets.UTF_8))
        val (resultSource, resultFilename) = storage.getFile(name, relativeFilePath).right.value
        resultFilename.value shouldEqual "file.txt"
        resultSource.runWith(Sink.head).futureValue.decodeString(UTF_8) shouldEqual content
      }

      "pass with directory" in new RelativeDirectoryCreated {
        val content = "some content"
        Files.write(absoluteFilePath, content.getBytes(StandardCharsets.UTF_8))
        val (resultSource, resultFilename) = storage.getFile(name, Uri.Path(relativeDir)).right.value
        resultFilename shouldEqual None
        resultSource.via(Compression.gunzip()).runFold("")(_ ++ _.utf8String).futureValue should include(content)
      }
    }
  }

}
