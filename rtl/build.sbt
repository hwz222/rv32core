// RV32IMA core. Standard Chisel project layout:
//   src/main/scala  RTL, src/test/scala  chiseltest unit tests.
// Chisel 3.5.6 on Scala 2.12.15 (the last Chisel 3 line with chiseltest 0.5).
ThisBuild / scalaVersion := "2.12.15"
ThisBuild / version      := "0.1.0"

val chiselVersion = "3.5.6"

lazy val root = (project in file("."))
  .settings(
    name := "rvsc-core",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3"    % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "0.5.6" % Test
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-P:chiselplugin:genBundleElements"
    ),
    // Chisel 3.5 needs its compiler plugin.
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
    // chiseltest backends share test_run_dir, so run tests one by one.
    Test / parallelExecution := false
  )
