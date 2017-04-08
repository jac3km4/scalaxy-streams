package scalaxy_streams_build
import cbt._
class Build(val context: Context) extends BaseBuild {
  override def defaultScalaVersion = "2.12.1"

  override def sources = Seq(
    projectDirectory ++ "/cbt-src"
  )

  override def dependencies = (
    super.dependencies ++ // don't forget super.dependencies here for scala-library, etc.
    Seq(
      // source dependency
      // DirectoryDependency( projectDirectory ++ "/subProject" )
    ) ++
    // pick resolvers explicitly for individual dependencies (and their transitive dependencies)
    Resolver( mavenCentral, sonatypeReleases ).bind(
      // CBT-style Scala dependencies
      // ScalaDependency( "com.lihaoyi", "ammonite-ops", "0.5.5" )
      MavenDependency( "org.scala-lang", "scala-compiler", defaultScalaVersion ),
      MavenDependency( "org.scala-lang", "scala-reflect", defaultScalaVersion )

      // SBT-style dependencies
      // "com.lihaoyi" %% "ammonite-ops" % "0.5.5"
      // "com.lihaoyi" % "ammonite-ops_2.11" % "0.5.5"
    )
  )
}
