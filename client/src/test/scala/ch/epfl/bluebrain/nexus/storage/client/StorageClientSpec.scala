package ch.epfl.bluebrain.nexus.storage.client

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.test.io.IOOptionValues
import ch.epfl.bluebrain.nexus.commons.test.{Randomness, Resources}
import ch.epfl.bluebrain.nexus.iam.client.IamClientError
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient.AkkaSource
import ch.epfl.bluebrain.nexus.storage.client.StorageClientError._
import ch.epfl.bluebrain.nexus.storage.client.config.StorageClientConfig
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes, ServiceDescription}
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.Digest
import io.circe.Json
import org.mockito.Mockito
import org.mockito.integrations.scalatest.IdiomaticMockitoFixture
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection NameBooleanParameters
class StorageClientSpec
    extends TestKit(ActorSystem("StorageClientSpec"))
    with WordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfter
    with IdiomaticMockitoFixture
    with Randomness
    with IOOptionValues
    with EitherValues
    with Inspectors
    with Resources
    with Eventually {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5 seconds, 15 milliseconds)

  implicit val ec: ExecutionContext  = system.dispatcher
  implicit val mt: ActorMaterializer = ActorMaterializer()

  private val config = StorageClientConfig(url"https://nexus.example.com".value, "v1")
  private val token  = OAuth2BearerToken("token")

  private implicit val attributesClient: HttpClient[IO, FileAttributes]   = mock[HttpClient[IO, FileAttributes]]
  private implicit val sourceClient: HttpClient[IO, AkkaSource]           = mock[HttpClient[IO, AkkaSource]]
  private implicit val servDescClient: HttpClient[IO, ServiceDescription] = mock[HttpClient[IO, ServiceDescription]]
  private implicit val notUsed: HttpClient[IO, NotUsed]                   = mock[HttpClient[IO, NotUsed]]
  private implicit val tokenOpt: Option[AuthToken]                        = Option(AuthToken("token"))

  private val client = new StorageClient[IO](config, attributesClient, sourceClient, servDescClient, notUsed)

  private def exists(name: String) =
    Head(s"https://nexus.example.com/v1/buckets/$name").addCredentials(token)

  private def serviceDescription =
    Get(s"https://nexus.example.com")

  private def createFile(name: String, path: Uri.Path, source: AkkaSource, filename: String) = {
    val bodyPartEntity = HttpEntity.IndefiniteLength(ContentTypes.`application/octet-stream`, source)
    val multipartForm  = FormData(BodyPart("file", bodyPartEntity, Map("filename" -> filename))).toEntity()
    Put(s"https://nexus.example.com/v1/buckets/$name/files/$path", multipartForm).addCredentials(token)
  }

  private def getFile(name: String, path: Uri.Path) =
    Get(s"https://nexus.example.com/v1/buckets/$name/files/$path").addCredentials(token)

  private def moveFile(name: String, source: Uri.Path, dest: Uri.Path) = {
    val json = Json.obj("source" -> Json.fromString(source.toString()))
    Put(s"https://nexus.example.com/v1/buckets/$name/files/$dest", HttpEntity(`application/json`, json.noSpaces))
      .addCredentials(token)
  }

  private def sourceInChunks(input: String): AkkaSource =
    Source.fromIterator(() ⇒ input.grouped(10000).map(ByteString(_)))

  private def matchesArg[A](f: A => Boolean): A = argThat((argument: A) => f(argument))

  private def consume(source: AkkaSource): String =
    source.runFold("")(_ ++ _.utf8String).futureValue

  private def removeDelimiters(string: String): String = string.replaceAll("(?m)^--.*$", "")

  before {
    Mockito.reset(attributesClient, sourceClient, notUsed)
  }

  private val exs: List[Exception] = List(
    IamClientError.Unauthorized(""),
    IamClientError.Forbidden(""),
    StorageClientError.UnmarshallingError[FileAttributes](""),
    StorageClientError.UnmarshallingError[Unit](""),
    StorageClientError.UnmarshallingError[AkkaSource](""),
    StorageClientError.UnknownError(StatusCodes.InternalServerError, "")
  )

  sealed trait Ctx {
    val name     = genString()
    val path     = Uri.Path("one/two")
    val content  = genString()
    val filename = genString()
    val source   = sourceInChunks(content)
  }

  "The StorageClient" when {

    "requesting service information" should {

      "return service description" in new Ctx {
        val expected = ServiceDescription("storage", "1.0.0")
        servDescClient(serviceDescription) shouldReturn IO.pure(expected)
        client.serviceDescription.ioValue shouldEqual expected
      }

      "propagate the underlying exception" in new Ctx {
        forAll(exs) { ex =>
          servDescClient(serviceDescription) shouldReturn IO.raiseError(ex)
          client.serviceDescription.failed[Exception] shouldEqual ex
        }
      }
    }

    "checking storage bucket existence" should {

      "return true" in new Ctx {
        notUsed(exists(name)) shouldReturn IO.pure(NotUsed.notUsed())
        client.exists(name).ioValue shouldEqual true
      }

      "return false" in new Ctx {
        notUsed(exists(name)) shouldReturn IO.raiseError(NotFound(""))
        client.exists(name).ioValue shouldEqual false
      }

      "propagate the underlying exception" in new Ctx {
        forAll(exs) { ex =>
          notUsed(exists(name)) shouldReturn IO.raiseError(ex)
          client.exists(name).failed[Exception] shouldEqual ex
        }
      }
    }

    "creating a file" should {

      def matches(req: HttpRequest) = matchesArg[HttpRequest] { other =>
        other == null || (other.copy(entity = req.entity) == req &&
        removeDelimiters(consume(other.entity.dataBytes)) == removeDelimiters(consume(req.entity.dataBytes)))
      }

      "return the file attributes" in new Ctx {
        val fileAttr =
          FileAttributes(s"file:///root/one/two", 12L, Digest("SHA-256", genString()))
        attributesClient(matches(createFile(name, path, source, "two"))) shouldReturn
          IO.pure(fileAttr)
        client.createFile(name, path, source).ioValue shouldEqual fileAttr
      }

      "propagate the underlying exception" in new Ctx {
        forAll(exs) { ex =>
          attributesClient(matches(createFile(name, path, source, "two"))) shouldReturn
            IO.raiseError(ex)
          client.createFile(name, path, source).failed[Exception] shouldEqual ex
        }
      }
    }

    "getting a file" should {

      "return the source" in new Ctx {
        sourceClient(getFile(name, path)) shouldReturn IO.pure(source)
        client.getFile(name, path).ioValue shouldEqual source
      }

      "propagate the underlying exception" in new Ctx {
        forAll(exs) { ex =>
          sourceClient(getFile(name, path)) shouldReturn IO.raiseError(ex)
          client.getFile(name, path).failed[Exception] shouldEqual ex
        }
      }
    }

    "moving a file" should {

      "return the file attributes" in new Ctx {
        val sourcePath = Uri.Path("two/three")
        val fileAttr =
          FileAttributes(s"file:///root/one/two/$filename", 12L, Digest("SHA-256", genString()))
        attributesClient(moveFile(name, sourcePath, path)) shouldReturn IO.pure(fileAttr)
        client.moveFile(name, sourcePath, path).ioValue shouldEqual fileAttr
      }

      "propagate the underlying exception" in new Ctx {
        val sourcePath = Uri.Path("two/three")
        forAll(exs) { ex =>
          attributesClient(moveFile(name, sourcePath, path)) shouldReturn IO.raiseError(ex)
          client.moveFile(name, sourcePath, path).failed[Exception] shouldEqual ex
        }
      }
    }
  }
}
