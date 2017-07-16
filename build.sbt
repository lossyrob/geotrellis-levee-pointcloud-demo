organization := "com.azavea"
name := "levee-pointcloud-demo"
version := "0.0.1"
scalaVersion := "2.11.8"
scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-Yinline-warnings",
  "-language:implicitConversions",
  "-language:reflectiveCalls",
  "-language:higherKinds",
  "-language:postfixOps",
  "-language:existentials",
  "-feature")

libraryDependencies ++= Seq(
  "org.locationtech.geotrellis" %% "geotrellis-spark"      % "1.2.0-PC-SNAPSHOT",
  "org.locationtech.geotrellis" %% "geotrellis-pointcloud" % "1.2.0-PC-SNAPSHOT",
  "org.apache.spark" %% "spark-core"    % "2.2.0" % "provided",
  "org.apache.hadoop" % "hadoop-client" % "2.8.0" % "provided",

  // For server
  "com.typesafe.akka" %% "akka-actor"           % "2.4.16" % "provided",
  "com.typesafe.akka" %% "akka-http-core"       % "10.0.3" % "provided",
  "com.typesafe.akka" %% "akka-http"            % "10.0.3" % "provided",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.3" % "provided"
)

// fullClasspath in run := (fullClasspath in (Compile, run)).value
// fullClasspath in runMain := (fullClasspath in (Compile, run)).value
run in Compile := Defaults.runTask(fullClasspath in Compile, mainClass in (Compile, run), runner in (Compile, run)).evaluated

runMain in Compile := Defaults.runMainTask(fullClasspath in Compile, runner in(Compile, run)).evaluated

//classpathConfiguration in run := Configurations.CompileInternal

outputStrategy := Some(StdoutOutput)
fork := true

// Important for allowing GeoTrellis to find the PDAL JNI bindings.
// Required if using spark-submit as well
javaOptions += s"-Djava.library.path=/usr/local/lib"

javaOptions += "-Xmx3G"

test in assembly := {}

assemblyJarName in assembly := "levee-pointcloud-demo.jar"
assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)

assemblyMergeStrategy in assembly := {
  case m if m.toLowerCase.endsWith("manifest.mf") => MergeStrategy.discard
  case m if m.toLowerCase.matches("meta-inf.*\\.sf$") => MergeStrategy.discard
  case "reference.conf" | "application.conf" => MergeStrategy.concat
  case _ => MergeStrategy.first
}
