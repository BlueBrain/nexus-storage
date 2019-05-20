package ch.epfl.bluebrain.nexus.storage.routes

import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.storage.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, Storages}
import io.circe.Json
import monix.eval.Task
import org.mockito.IdiomaticMockito
import org.scalatest.{Matchers, WordSpecLike}

class AppInfoRoutesSpec
    extends WordSpecLike
    with Matchers
    with ScalatestRouteTest
    with IdiomaticMockito
    with Resources {

  "the app info routes" should {

    implicit val config: AppConfig          = Settings(system).appConfig
    implicit val iamClient: IamClient[Task] = mock[IamClient[Task]]
    val route: Route                        = Routes(mock[Storages[AkkaSource]])

    "return application information" in {
      Get("/") ~> route ~> check {
        status shouldEqual OK
        responseAs[Json] shouldEqual
          jsonContentOf("/app-info.json", Map(quote("{version}") -> config.description.version))
      }
    }
  }
}
