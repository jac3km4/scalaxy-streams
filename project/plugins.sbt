// See: http://www.scala-sbt.org/0.13/docs/Resolvers.html

resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.2")

addSbtPlugin("org.ensime" % "ensime-sbt-cmd" % "0.1.4")
