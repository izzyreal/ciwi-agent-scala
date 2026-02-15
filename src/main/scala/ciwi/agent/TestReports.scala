package ciwi.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object TestReports {
  private final case class GoCase(
    packageName: String,
    name: String,
    var status: String,
    var durationSeconds: Option[Double],
    out: StringBuilder
  )

  def parseSuite(execDir: Path, step: JobStepPlanItem): Either[String, TestSuiteReport] = {
    val format = step.testFormat.map(_.trim).filter(_.nonEmpty).getOrElse("go-test-json")
    format match {
      case "go-test-json" => parseGoTestJsonSuite(execDir, step)
      case other => Left(s"unsupported test format $other")
    }
  }

  private def parseGoTestJsonSuite(execDir: Path, step: JobStepPlanItem): Either[String, TestSuiteReport] = {
    val rel = step.testReport.map(_.trim).getOrElse("")
    if (rel.isEmpty) return Left("test report path is empty")
    val file = execDir.resolve(Paths.get(rel))
    if (!Files.exists(file)) return Left(s"read test report $rel: file not found")
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList

    val map = mutable.LinkedHashMap[(String, String), GoCase]()

    lines.foreach { raw =>
      val line = raw.trim
      if (line.nonEmpty) {
        io.circe.parser.parse(line).toOption.foreach { json =>
          val c = json.hcursor
          val action = c.get[String]("Action").toOption.map(_.trim.toLowerCase).getOrElse("")
          val pkg = c.get[String]("Package").toOption.getOrElse("").trim
          val test = c.get[String]("Test").toOption.getOrElse("").trim
          val output = c.get[String]("Output").toOption.getOrElse("")
          val elapsed = c.get[Double]("Elapsed").toOption

          if (test.nonEmpty) {
            val key = (pkg, test)
            val gc = map.getOrElseUpdate(key, GoCase(pkg, test, "running", None, new StringBuilder))
            if (output.nonEmpty) gc.out.append(output)
            if (elapsed.nonEmpty) gc.durationSeconds = elapsed
            action match {
              case "pass" => gc.status = "passed"
              case "fail" => gc.status = "failed"
              case "skip" => gc.status = "skipped"
              case _ =>
            }
          }
        }
      }
    }

    val cases = map.values.toList.map { tc =>
      TestCase(
        packageName = Option(tc.packageName).filter(_.nonEmpty),
        name = Option(tc.name).filter(_.nonEmpty),
        status = tc.status,
        durationSeconds = tc.durationSeconds,
        output = Option(tc.out.toString).map(_.trim).filter(_.nonEmpty)
      )
    }

    val passed = cases.count(_.status == "passed")
    val failed = cases.count(_.status == "failed")
    val skipped = cases.count(_.status == "skipped")
    val total = cases.length

    Right(
      TestSuiteReport(
        name = step.testName.map(_.trim).filter(_.nonEmpty),
        format = step.testFormat.map(_.trim).filter(_.nonEmpty).getOrElse("go-test-json"),
        total = total,
        passed = passed,
        failed = failed,
        skipped = skipped,
        cases = cases
      )
    )
  }

  def parseCoverage(execDir: Path, step: JobStepPlanItem): Either[String, Option[CoverageReport]] = {
    val rel = step.coverageReport.map(_.trim).getOrElse("")
    if (rel.isEmpty) return Right(None)
    val format = step.coverageFormat.map(_.trim).filter(_.nonEmpty).getOrElse {
      if (rel.endsWith(".lcov") || rel.endsWith(".info")) "lcov" else "go-coverprofile"
    }

    val file = execDir.resolve(Paths.get(rel))
    if (!Files.exists(file)) return Left(s"read coverage report $rel: file not found")
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList

    format match {
      case "go-coverprofile" => parseGoCoverprofile(lines).map(r => Some(r))
      case "lcov" => parseLcov(lines).map(r => Some(r))
      case other => Left(s"unsupported coverage format $other")
    }
  }

  private def parseGoCoverprofile(lines: List[String]): Either[String, CoverageReport] = {
    val byFile = mutable.Map[String, (Int, Int)]() // total, covered statements
    var parseErr: Option[String] = None

    lines.zipWithIndex.foreach { case (raw, idx) =>
      if (parseErr.isEmpty) {
        val line = raw.trim
        if (line.nonEmpty && !line.startsWith("mode:")) {
          val colon = line.indexOf(':')
          if (colon <= 0) {
            parseErr = Some(s"invalid coverprofile line ${idx + 1}")
          } else {
            val path = line.substring(0, colon).trim
            val fields = line.substring(colon + 1).trim.split("\\s+")
            if (fields.length != 3) {
              parseErr = Some(s"invalid coverprofile payload line ${idx + 1}")
            } else {
              (fields(1).toIntOption, fields(2).toDoubleOption) match {
                case (Some(stmts), Some(hits)) =>
                  val (t, c) = byFile.getOrElse(path, (0, 0))
                  byFile.update(path, (t + stmts, c + (if (hits > 0) stmts else 0)))
                case (None, _) =>
                  parseErr = Some(s"invalid statement count line ${idx + 1}")
                case (_, None) =>
                  parseErr = Some(s"invalid hit count line ${idx + 1}")
              }
            }
          }
        }
      }
    }

    if (parseErr.nonEmpty) return Left(parseErr.get)

    val files = byFile.toList.sortBy(_._1).map { case (path, (t, c)) =>
      CoverageFileReport(
        path = Some(path),
        totalLines = None,
        coveredLines = None,
        totalStatements = Some(t),
        coveredStatements = Some(c),
        percent = Some(if (t > 0) 100.0 * c.toDouble / t.toDouble else 0.0)
      )
    }

    val totalStatements = files.flatMap(_.totalStatements).sum
    val coveredStatements = files.flatMap(_.coveredStatements).sum

    Right(
      CoverageReport(
        format = "go-coverprofile",
        totalLines = None,
        coveredLines = None,
        totalStatements = Some(totalStatements),
        coveredStatements = Some(coveredStatements),
        percent = Some(if (totalStatements > 0) 100.0 * coveredStatements.toDouble / totalStatements.toDouble else 0.0),
        files = files
      )
    )
  }

  private def parseLcov(lines: List[String]): Either[String, CoverageReport] = {
    final case class Stat(var total: Int, var covered: Int, var seenLf: Boolean, var seenLh: Boolean, var daTotal: Int, var daHits: Int)
    val stats = mutable.Map[String, Stat]()
    var current = ""
    var parseErr: Option[String] = None

    lines.zipWithIndex.foreach { case (raw, idx) =>
      if (parseErr.isEmpty) {
        val line = raw.trim
        if (line.nonEmpty) {
          if (line.startsWith("SF:")) {
            current = line.stripPrefix("SF:").trim
            if (current.isEmpty) {
              parseErr = Some(s"invalid lcov SF line ${idx + 1}")
            } else if (!stats.contains(current)) {
              stats.update(current, Stat(0, 0, false, false, 0, 0))
            }
          } else if (line == "end_of_record") {
            current = ""
          } else if (current.nonEmpty && line.startsWith("LF:")) {
            line.stripPrefix("LF:").trim.toIntOption match {
              case Some(v) =>
                val st = stats(current); st.total = v; st.seenLf = true
              case None =>
                parseErr = Some(s"invalid lcov LF line ${idx + 1}")
            }
          } else if (current.nonEmpty && line.startsWith("LH:")) {
            line.stripPrefix("LH:").trim.toIntOption match {
              case Some(v) =>
                val st = stats(current); st.covered = v; st.seenLh = true
              case None =>
                parseErr = Some(s"invalid lcov LH line ${idx + 1}")
            }
          } else if (current.nonEmpty && line.startsWith("DA:")) {
            val parts = line.stripPrefix("DA:").split(',').map(_.trim)
            if (parts.length < 2) {
              parseErr = Some(s"invalid lcov DA line ${idx + 1}")
            } else {
              parts(1).toIntOption match {
                case Some(hits) =>
                  val st = stats(current)
                  st.daTotal += 1
                  if (hits > 0) st.daHits += 1
                case None =>
                  parseErr = Some(s"invalid lcov DA hits line ${idx + 1}")
              }
            }
          }
        }
      }
    }

    if (parseErr.nonEmpty) return Left(parseErr.get)

    val files = stats.toList.sortBy(_._1).map { case (path, st) =>
      val total = if (st.seenLf) st.total else st.daTotal
      val covered = if (st.seenLh) st.covered else st.daHits
      CoverageFileReport(
        path = Some(path),
        totalLines = Some(total),
        coveredLines = Some(covered),
        totalStatements = None,
        coveredStatements = None,
        percent = Some(if (total > 0) 100.0 * covered.toDouble / total.toDouble else 0.0)
      )
    }

    val totalLines = files.flatMap(_.totalLines).sum
    val coveredLines = files.flatMap(_.coveredLines).sum

    Right(
      CoverageReport(
        format = "lcov",
        totalLines = Some(totalLines),
        coveredLines = Some(coveredLines),
        totalStatements = None,
        coveredStatements = None,
        percent = Some(if (totalLines > 0) 100.0 * coveredLines.toDouble / totalLines.toDouble else 0.0),
        files = files
      )
    )
  }

  def summary(report: JobExecutionTestReport): String = {
    if (report.total == 0 && report.coverage.isEmpty) return "[tests] none"
    if (report.total == 0) return f"[coverage] format=${report.coverage.get.format} coverage=${report.coverage.get.percent.getOrElse(0.0)}%.2f%%"
    val base = s"[tests] total=${report.total} passed=${report.passed} failed=${report.failed} skipped=${report.skipped}"
    report.coverage match {
      case Some(c) => base + f" | coverage=${c.format} ${c.percent.getOrElse(0.0)}%.2f%%"
      case None => base
    }
  }
}
