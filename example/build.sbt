course := "progfun1"
assignment := "example"
scalaVersion := "2.13.1"
scalacOptions ++= Seq("-language:implicitConversions", "-deprecation")
libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test

Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "-s")
