package ch.epfl.bluebrain.nexus.storage.config

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.syntax._

object Contexts {

  val base = url"https://bluebrain.github.io/nexus/contexts/".value

  val errorCtxUri: AbsoluteIri    = base + "error.json"
  val resourceCtxUri: AbsoluteIri = base + "resource.json"

}
