resolvers += Resolver.bintrayRepo("bbp", "nexus-releases")

addSbtPlugin("ch.epfl.bluebrain.nexus" % "sbt-nexus"     % "0.11.0")
addSbtPlugin("com.eed3si9n"            % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("com.eed3si9n"            % "sbt-assembly"  % "0.14.10")
