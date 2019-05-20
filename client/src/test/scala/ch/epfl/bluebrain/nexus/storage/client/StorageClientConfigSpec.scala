package ch.epfl.bluebrain.nexus.storage.client

import ch.epfl.bluebrain.nexus.rdf.Iri.Urn
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.storage.client.config.StorageClientConfig
import org.scalatest.{EitherValues, Matchers, WordSpecLike}

class StorageClientConfigSpec extends WordSpecLike with Matchers with EitherValues {

  "A StorageClientConfig" should {

    "build from Url" in {
      StorageClientConfig(url"http://example.com/subresource/v1".value) shouldEqual
        StorageClientConfig(url"http://example.com/subresource".value, "v1")

      StorageClientConfig(url"http://example.com/v1".value) shouldEqual
        StorageClientConfig(url"http://example.com".value, "v1")
    }

    "build from Urn" in {
      StorageClientConfig(Urn("urn:ietf:rfc:2648/subresource/v1").right.value) shouldEqual
        (StorageClientConfig(Urn("urn:ietf:rfc:2648/subresource").right.value, "v1"))
    }
  }
}
