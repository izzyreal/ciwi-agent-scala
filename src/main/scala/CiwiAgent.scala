package ciwi.agent

import io.circe._
import io.circe.parser.decode
import io.circe.syntax._

import java.io.{ByteArrayOutputStream, IOException}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystems, FileVisitResult, Files, Path, Paths, SimpleFileVisitor}
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

final case class SourceSpec(repo: String, ref: Option[String])

final case class JobStepPlanItem(
  index: Int,
  total: Option[Int],
  name: Option[String],
  script: Option[String],
  kind: Option[String],
  testName: Option[String],
  testFormat: Option[String],
  testReport: Option[String],
  coverageFormat: Option[String],
  coverageReport: Option[String]
)

final case class JobExecution(
  id: String,
  script: String,
  env: Option[Map[String, String]],
  requiredCapabilities: Option[Map[String, String]],
  timeoutSeconds: Option[Int],
  artifactGlobs: Option[List[String]],
  source: Option[SourceSpec],
  metadata: Option[Map[String, String]],
  stepPlan: Option[List[JobStepPlanItem]]
)

final case class HeartbeatRequest(
  agentId: String,
  hostname: String,
  os: String,
  arch: String,
  version: String,
  capabilities: Map[String, String],
  updateFailure: Option[String],
  restartStatus: Option[String],
  timestampUtc: String
)

final case class HeartbeatResponse(
  accepted: Boolean,
  message: Option[String],
  updateRequested: Option[Boolean],
  updateTarget: Option[String],
  refreshToolsRequested: Option[Boolean],
  restartRequested: Option[Boolean]
)

final case class LeaseRequest(agentId: String, capabilities: Map[String, String])
final case class LeaseResponse(assigned: Boolean, jobExecution: Option[JobExecution], message: Option[String])

final case class JobStatusEventStep(
  index: Int,
  total: Option[Int],
  name: Option[String],
  kind: Option[String],
  testName: Option[String],
  testFormat: Option[String],
  testReport: Option[String],
  coverageFormat: Option[String],
  coverageReport: Option[String]
)

final case class JobStatusEvent(
  `type`: String,
  timestampUtc: String,
  step: Option[JobStatusEventStep]
)

final case class JobStatusUpdate(
  agentId: String,
  status: String,
  exitCode: Option[Int],
  error: Option[String],
  output: Option[String],
  currentStep: Option[String],
  timestampUtc: String,
  events: Option[List[JobStatusEvent]] = None
)

final case class UploadArtifact(path: String, dataBase64: String)
final case class UploadArtifactsRequest(agentId: String, artifacts: List[UploadArtifact])

final case class ArtifactEntry(path: String, url: String)
final case class JobArtifactsResponse(artifacts: List[ArtifactEntry])

final case class TestCase(
  packageName: Option[String],
  name: Option[String],
  status: String,
  durationSeconds: Option[Double],
  output: Option[String]
)

final case class TestSuiteReport(
  name: Option[String],
  format: String,
  total: Int,
  passed: Int,
  failed: Int,
  skipped: Int,
  cases: List[TestCase]
)

final case class CoverageFileReport(
  path: Option[String],
  totalLines: Option[Int],
  coveredLines: Option[Int],
  totalStatements: Option[Int],
  coveredStatements: Option[Int],
  percent: Option[Double]
)

final case class CoverageReport(
  format: String,
  totalLines: Option[Int],
  coveredLines: Option[Int],
  totalStatements: Option[Int],
  coveredStatements: Option[Int],
  percent: Option[Double],
  files: List[CoverageFileReport]
)

final case class JobExecutionTestReport(
  total: Int,
  passed: Int,
  failed: Int,
  skipped: Int,
  suites: List[TestSuiteReport],
  coverage: Option[CoverageReport]
)

final case class UploadTestReportRequest(agentId: String, report: JobExecutionTestReport)

object Codecs {
  private def fString(c: HCursor, name: String): Decoder.Result[String] = c.downField(name).as[String]

  given Decoder[SourceSpec] = Decoder.instance { c =>
    for {
      repo <- fString(c, "repo")
      ref <- c.downField("ref").as[Option[String]]
    } yield SourceSpec(repo, ref.map(_.trim).filter(_.nonEmpty))
  }

  given Decoder[JobStepPlanItem] = Decoder.instance { c =>
    for {
      index <- c.downField("index").as[Int]
      total <- c.downField("total").as[Option[Int]]
      name <- c.downField("name").as[Option[String]]
      script <- c.downField("script").as[Option[String]]
      kind <- c.downField("kind").as[Option[String]]
      testName <- c.downField("test_name").as[Option[String]]
      testFormat <- c.downField("test_format").as[Option[String]]
      testReport <- c.downField("test_report").as[Option[String]]
      coverageFormat <- c.downField("coverage_format").as[Option[String]]
      coverageReport <- c.downField("coverage_report").as[Option[String]]
    } yield JobStepPlanItem(index, total, name, script, kind, testName, testFormat, testReport, coverageFormat, coverageReport)
  }

  given Decoder[JobExecution] = Decoder.instance { c =>
    for {
      id <- fString(c, "id")
      script <- fString(c, "script")
      env <- c.downField("env").as[Option[Map[String, String]]]
      requiredCapabilities <- c.downField("required_capabilities").as[Option[Map[String, String]]]
      timeoutSeconds <- c.downField("timeout_seconds").as[Option[Int]]
      artifactGlobs <- c.downField("artifact_globs").as[Option[List[String]]]
      source <- c.downField("source").as[Option[SourceSpec]]
      metadata <- c.downField("metadata").as[Option[Map[String, String]]]
      stepPlan <- c.downField("step_plan").as[Option[List[JobStepPlanItem]]]
    } yield JobExecution(id, script, env, requiredCapabilities, timeoutSeconds, artifactGlobs, source, metadata, stepPlan)
  }

  given Encoder[HeartbeatRequest] = Encoder.instance { v =>
    Json.obj(
      "agent_id" -> Json.fromString(v.agentId),
      "hostname" -> Json.fromString(v.hostname),
      "os" -> Json.fromString(v.os),
      "arch" -> Json.fromString(v.arch),
      "version" -> Json.fromString(v.version),
      "capabilities" -> v.capabilities.asJson,
      "update_failure" -> v.updateFailure.asJson,
      "restart_status" -> v.restartStatus.asJson,
      "timestamp_utc" -> Json.fromString(v.timestampUtc)
    )
  }

  given Decoder[HeartbeatResponse] = Decoder.instance { c =>
    for {
      accepted <- c.downField("accepted").as[Boolean]
      message <- c.downField("message").as[Option[String]]
      updateRequested <- c.downField("update_requested").as[Option[Boolean]]
      updateTarget <- c.downField("update_target").as[Option[String]]
      refreshToolsRequested <- c.downField("refresh_tools_requested").as[Option[Boolean]]
      restartRequested <- c.downField("restart_requested").as[Option[Boolean]]
    } yield HeartbeatResponse(accepted, message, updateRequested, updateTarget, refreshToolsRequested, restartRequested)
  }

  given Encoder[LeaseRequest] = Encoder.instance { v =>
    Json.obj(
      "agent_id" -> Json.fromString(v.agentId),
      "capabilities" -> v.capabilities.asJson
    )
  }

  given Decoder[LeaseResponse] = Decoder.instance { c =>
    for {
      assigned <- c.downField("assigned").as[Boolean]
      jobExecution <- c.downField("job_execution").as[Option[JobExecution]]
      message <- c.downField("message").as[Option[String]]
    } yield LeaseResponse(assigned, jobExecution, message)
  }

  given Encoder[JobStatusEventStep] = Encoder.instance { v =>
    Json.obj(
      "index" -> Json.fromInt(v.index),
      "total" -> v.total.asJson,
      "name" -> v.name.asJson,
      "kind" -> v.kind.asJson,
      "test_name" -> v.testName.asJson,
      "test_format" -> v.testFormat.asJson,
      "test_report" -> v.testReport.asJson,
      "coverage_format" -> v.coverageFormat.asJson,
      "coverage_report" -> v.coverageReport.asJson
    )
  }

  given Encoder[JobStatusEvent] = Encoder.instance { v =>
    Json.obj(
      "type" -> Json.fromString(v.`type`),
      "timestamp_utc" -> Json.fromString(v.timestampUtc),
      "step" -> v.step.asJson
    )
  }

  given Encoder[JobStatusUpdate] = Encoder.instance { v =>
    Json.obj(
      "agent_id" -> Json.fromString(v.agentId),
      "status" -> Json.fromString(v.status),
      "exit_code" -> v.exitCode.asJson,
      "error" -> v.error.asJson,
      "output" -> v.output.asJson,
      "current_step" -> v.currentStep.asJson,
      "timestamp_utc" -> Json.fromString(v.timestampUtc),
      "events" -> v.events.asJson
    )
  }

  given Encoder[UploadArtifact] = Encoder.instance { v =>
    Json.obj(
      "path" -> Json.fromString(v.path),
      "data_base64" -> Json.fromString(v.dataBase64)
    )
  }

  given Encoder[UploadArtifactsRequest] = Encoder.instance { v =>
    Json.obj(
      "agent_id" -> Json.fromString(v.agentId),
      "artifacts" -> v.artifacts.asJson
    )
  }

  given Decoder[ArtifactEntry] = Decoder.instance { c =>
    for {
      path <- fString(c, "path")
      url <- fString(c, "url")
    } yield ArtifactEntry(path, url)
  }

  given Decoder[JobArtifactsResponse] = Decoder.instance { c =>
    c.downField("artifacts").as[List[ArtifactEntry]].map(JobArtifactsResponse.apply)
  }

  given Encoder[TestCase] = Encoder.instance { v =>
    Json.obj(
      "package" -> v.packageName.asJson,
      "name" -> v.name.asJson,
      "status" -> Json.fromString(v.status),
      "duration_seconds" -> v.durationSeconds.asJson,
      "output" -> v.output.asJson
    )
  }

  given Encoder[TestSuiteReport] = Encoder.instance { v =>
    Json.obj(
      "name" -> v.name.asJson,
      "format" -> Json.fromString(v.format),
      "total" -> Json.fromInt(v.total),
      "passed" -> Json.fromInt(v.passed),
      "failed" -> Json.fromInt(v.failed),
      "skipped" -> Json.fromInt(v.skipped),
      "cases" -> v.cases.asJson
    )
  }

  given Encoder[CoverageFileReport] = Encoder.instance { v =>
    Json.obj(
      "path" -> v.path.asJson,
      "total_lines" -> v.totalLines.asJson,
      "covered_lines" -> v.coveredLines.asJson,
      "total_statements" -> v.totalStatements.asJson,
      "covered_statements" -> v.coveredStatements.asJson,
      "percent" -> v.percent.asJson
    )
  }

  given Encoder[CoverageReport] = Encoder.instance { v =>
    Json.obj(
      "format" -> Json.fromString(v.format),
      "total_lines" -> v.totalLines.asJson,
      "covered_lines" -> v.coveredLines.asJson,
      "total_statements" -> v.totalStatements.asJson,
      "covered_statements" -> v.coveredStatements.asJson,
      "percent" -> v.percent.asJson,
      "files" -> v.files.asJson
    )
  }

  given Encoder[JobExecutionTestReport] = Encoder.instance { v =>
    Json.obj(
      "total" -> Json.fromInt(v.total),
      "passed" -> Json.fromInt(v.passed),
      "failed" -> Json.fromInt(v.failed),
      "skipped" -> Json.fromInt(v.skipped),
      "suites" -> v.suites.asJson,
      "coverage" -> v.coverage.asJson
    )
  }

  given Encoder[UploadTestReportRequest] = Encoder.instance { v =>
    Json.obj(
      "agent_id" -> Json.fromString(v.agentId),
      "report" -> v.report.asJson
    )
  }
}


final class ApiClient(serverUrl: String, agentId: String, http: HttpClient) {
  import Codecs.given

  private def trimSlash(s: String): String = s.stripSuffix("/")
  private val base = trimSlash(serverUrl)

  private def request(path: String, method: String, body: Option[String]): HttpRequest = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(base + path))
      .header("Content-Type", "application/json")
      .timeout(java.time.Duration.ofMinutes(10))

    method match {
      case "GET" => builder.GET().build()
      case _      => builder.method(method, HttpRequest.BodyPublishers.ofString(body.getOrElse(""))).build()
    }
  }

  private def sendJson[A: Encoder](path: String, payload: A): Either[String, String] = {
    val body = payload.asJson.noSpaces
    val req = request(path, "POST", Some(body))
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() == 200) Right(resp.body())
    else Left(s"status=${resp.statusCode()} body=${resp.body().trim}")
  }

  private def sendGet(path: String): Either[String, String] = {
    val req = request(path, "GET", None)
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() == 200) Right(resp.body())
    else Left(s"status=${resp.statusCode()} body=${resp.body().trim}")
  }

  def sendHeartbeat(hostname: String, capabilities: Map[String, String]): Either[String, HeartbeatResponse] = {
    val req = HeartbeatRequest(
      agentId = agentId,
      hostname = hostname,
      os = System.getProperty("os.name").toLowerCase,
      arch = System.getProperty("os.arch").toLowerCase,
      version = "scala-agent-dev",
      capabilities = capabilities,
      updateFailure = None,
      restartStatus = None,
      timestampUtc = Instant.now().toString
    )
    sendJson("/api/v1/heartbeat", req).flatMap { raw =>
      decode[HeartbeatResponse](raw).left.map(e => s"decode heartbeat response: $e")
    }
  }

  def lease(capabilities: Map[String, String]): Either[String, Option[JobExecution]] = {
    val req = LeaseRequest(agentId, capabilities)
    sendJson("/api/v1/agent/lease", req).flatMap { raw =>
      decode[LeaseResponse](raw)
        .left
        .map(e => s"decode lease response: $e")
        .map(r => if (r.assigned) r.jobExecution else None)
    }
  }

  def reportStatus(jobId: String, status: JobStatusUpdate): Either[String, Unit] = {
    sendJson(s"/api/v1/jobs/$jobId/status", status).map(_ => ())
  }

  def reportTerminalStatusWithRetry(jobId: String, status: JobStatusUpdate): Either[String, Unit] = {
    var attempt = 1
    var lastErr = ""
    while (attempt <= 5) {
      reportStatus(jobId, status) match {
        case Right(_) => return Right(())
        case Left(err) =>
          lastErr = err
          if (attempt < 5) {
            val waitSecs = Math.pow(2.0, (attempt - 1).toDouble).toLong
            Thread.sleep(waitSecs * 1000)
          }
      }
      attempt += 1
    }
    Left(s"terminal status report failed after 5 attempts: $lastErr")
  }

  def uploadArtifacts(jobId: String, artifacts: List[UploadArtifact]): Either[String, Unit] = {
    val req = UploadArtifactsRequest(agentId, artifacts)
    sendJson(s"/api/v1/jobs/$jobId/artifacts", req).map(_ => ())
  }

  def uploadTestReport(jobId: String, report: JobExecutionTestReport): Either[String, Unit] = {
    val req = UploadTestReportRequest(agentId, report)
    sendJson(s"/api/v1/jobs/$jobId/tests", req).map(_ => ())
  }

  def listArtifacts(jobId: String): Either[String, List[ArtifactEntry]] = {
    sendGet(s"/api/v1/jobs/$jobId/artifacts").flatMap { raw =>
      decode[JobArtifactsResponse](raw).left.map(e => s"decode artifacts response: $e").map(_.artifacts)
    }
  }

  def downloadArtifact(url: String): Either[String, Array[Byte]] = {
    val targetUri = if (url.startsWith("http://") || url.startsWith("https://")) URI.create(url) else URI.create(base + (if (url.startsWith("/")) url else s"/$url"))
    val req = HttpRequest
      .newBuilder()
      .uri(targetUri)
      .GET()
      .timeout(java.time.Duration.ofMinutes(10))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
    if (resp.statusCode() == 200) Right(resp.body())
    else Left(s"status=${resp.statusCode()} body=${new String(resp.body(), StandardCharsets.UTF_8).trim}")
  }
}

object FileUtil {
  def deleteRecursively(path: Path): Unit = {
    if (!Files.exists(path)) return
    Files.walkFileTree(path, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.deleteIfExists(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Files.deleteIfExists(dir)
        FileVisitResult.CONTINUE
      }
    })
  }
}

object ArtifactCollector {
  private val maxArtifactsPerJob = 200
  private val maxArtifactFileBytes = 20L * 1024L * 1024L

  def collect(execDir: Path, globs: List[String]): (List[UploadArtifact], String) = {
    if (globs.isEmpty) return (Nil, "")

    val matchers = globs
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.replace('\\', '/'))
      .flatMap { pattern =>
        val collapsed = pattern.replace("/**/", "/")
        if (collapsed != pattern) List(pattern, collapsed) else List(pattern)
      }
      .distinct
      .map(pattern => FileSystems.getDefault.getPathMatcher("glob:" + pattern))

    val found = mutable.LinkedHashSet[String]()
    Files.walk(execDir).iterator().asScala.foreach { p =>
      if (Files.isRegularFile(p)) {
        val rel = execDir.relativize(p).toString.replace('\\', '/')
        if (matchers.exists(_.matches(Paths.get(rel)))) found += rel
      }
    }

    val sb = new StringBuilder
    sb.append("[artifacts] globs=").append(globs.mkString(", ")).append("\n")

    val artifacts = found.toList.sorted.take(maxArtifactsPerJob).flatMap { rel =>
      val file = execDir.resolve(rel)
      val size = Files.size(file)
      if (size > maxArtifactFileBytes) {
        sb.append(s"[artifacts] skip=$rel reason=size($size>$maxArtifactFileBytes)\n")
        None
      } else {
        val bytes = Files.readAllBytes(file)
        sb.append(s"[artifacts] include=$rel size=${bytes.length}\n")
        Some(UploadArtifact(rel, Base64.getEncoder.encodeToString(bytes)))
      }
    }

    if (artifacts.isEmpty) sb.append("[artifacts] none\n")
    (artifacts, sb.toString)
  }
}

object DepArtifacts {
  def dependencyJobIds(env: Map[String, String]): List[String] = {
    val ids = mutable.LinkedHashSet[String]()
    env.get("CIWI_DEP_ARTIFACT_JOB_IDS").toList.flatMap(_.split(",").toList).map(_.trim).filter(_.nonEmpty).foreach(ids += _)
    env.get("CIWI_DEP_ARTIFACT_JOB_ID").map(_.trim).filter(_.nonEmpty).foreach(ids += _)
    ids.toList
  }

  def restore(api: ApiClient, env: Map[String, String], execDir: Path): Either[String, String] = {
    val ids = dependencyJobIds(env)
    if (ids.isEmpty) return Right("")
    val sb = new StringBuilder
    sb.append("[dep-artifacts] source_jobs=").append(ids.mkString(",")).append('\n')

    var failure: Option[String] = None

    for (id <- ids if failure.isEmpty) {
      api.listArtifacts(id) match {
        case Left(err) =>
          failure = Some(s"list dependency artifacts failed for $id: $err")
        case Right(entries) =>
          for (e <- entries if failure.isEmpty) {
            val rel = e.path.replace('\\', '/')
            if (rel.startsWith("/") || rel.contains("..")) {
              sb.append(s"[dep-artifacts] skip=$rel reason=unsafe_path\n")
            } else {
              api.downloadArtifact(e.url) match {
                case Left(err) =>
                  failure = Some(s"download dependency artifact failed for $rel: $err")
                case Right(bytes) =>
                  val target = execDir.resolve(Paths.get(rel))
                  Files.createDirectories(target.getParent)
                  Files.write(target, bytes)
                  sb.append(s"[dep-artifacts] restored=$rel bytes=${bytes.length}\n")
              }
            }
          }
      }
    }

    failure match {
      case Some(err) => Left(err)
      case None => Right(sb.toString)
    }
  }

}

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

object CommandRunner {
  final case class Result(exitCode: Int, output: String, timedOut: Boolean)

  private def shellCommand(shell: String, script: String): List[String] = shell match {
    case "posix" => List("sh", "-lc", script)
    case "cmd" => List("cmd", "/d", "/c", script)
    case "powershell" => List("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
    case other => throw new IllegalArgumentException(s"unsupported shell: $other")
  }

  def run(shell: String, script: String, cwd: Path, env: Map[String, String], timeoutSeconds: Int): Result = {
    val cmd = shellCommand(shell, script)
    val pb = new ProcessBuilder(cmd.asJava)
    pb.directory(cwd.toFile)
    pb.redirectErrorStream(true)
    val pEnv = pb.environment()
    env.foreach { case (k, v) => pEnv.put(k, v) }

    val proc = pb.start()
    val out = new ByteArrayOutputStream()
    val streamThread = Thread(() => {
      val in = proc.getInputStream
      val buffer = new Array[Byte](8192)
      var n = in.read(buffer)
      while (n >= 0) {
        out.write(buffer, 0, n)
        n = in.read(buffer)
      }
      in.close()
    })
    streamThread.setDaemon(true)
    streamThread.start()

    val done = if (timeoutSeconds > 0) proc.waitFor(timeoutSeconds.toLong, TimeUnit.SECONDS) else {
      proc.waitFor()
      true
    }

    if (!done) {
      proc.destroyForcibly()
      proc.waitFor(5, TimeUnit.SECONDS)
    }
    streamThread.join(2000)

    val raw = out.toString(StandardCharsets.UTF_8)
    val trimmed = if (raw.length <= 1024 * 1024) raw else raw.takeRight(1024 * 1024)
    Result(if (done) proc.exitValue() else -1, trimmed, !done)
  }
}

object CiwiAgent {
  private val heartbeatInterval = 10.seconds
  private val leaseInterval = 3.seconds

  private def env(name: String, default: String): String = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse(default)
  private def envOpt(name: String): Option[String] = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)

  private def loadOrCreateAgentId(workDir: Path): String = {
    val host = java.net.InetAddress.getLocalHost.getHostName
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    val base = s"scala-$host-$os-$arch".replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-")
    val path = workDir.resolve("agent-id")
    if (Files.exists(path)) {
      val existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim
      if (existing.nonEmpty) return existing
    }
    Files.write(path, (base + "\n").getBytes(StandardCharsets.UTF_8))
    base
  }

  private def detectCapabilities(): Map[String, String] = {
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    val shells = if (os.contains("win")) "cmd,powershell" else "posix"
    Map(
      "executor" -> "script",
      "shells" -> shells,
      "os" -> (if (os.contains("mac")) "darwin" else if (os.contains("win")) "windows" else "linux"),
      "arch" -> arch
    )
  }

  private def leaseShell(required: Map[String, String]): String = {
    val asked = required.getOrElse("shell", "").trim.toLowerCase
    if (asked.nonEmpty) asked
    else {
      val os = System.getProperty("os.name").toLowerCase
      if (os.contains("win")) "powershell" else "posix"
    }
  }

  private def mergeEnv(jobEnv: Map[String, String]): Map[String, String] = {
    System.getenv().asScala.toMap ++ jobEnv
  }

  private def checkoutSource(source: SourceSpec, targetDir: Path): Either[String, Unit] = {
    val ref = source.ref.map(_.trim).filter(_.nonEmpty)
    val cmd = ref match {
      case Some(r) => List("git", "clone", "--depth", "1", "--branch", r, source.repo, targetDir.toString)
      case None => List("git", "clone", "--depth", "1", source.repo, targetDir.toString)
    }
    try {
      val pb = new ProcessBuilder(cmd.asJava)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val output = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val exit = proc.waitFor()
      if (exit == 0) Right(()) else Left(output.trim)
    } catch {
      case NonFatal(e) => Left(e.getMessage)
    }
  }

  private def nowIso: String = Instant.now().toString

  private def runJob(api: ApiClient, agentId: String, workDir: Path, job: JobExecution): Unit = {
    val running = JobStatusUpdate(
      agentId = agentId,
      status = "running",
      exitCode = None,
      error = None,
      output = None,
      currentStep = Some("Preparing execution"),
      timestampUtc = nowIso
    )
    api.reportStatus(job.id, running) match {
      case Left(err) =>
        println(s"[agent] failed to report running status job=${job.id}: $err")
      case Right(_) =>
    }

    val jobDir = workDir.resolve(job.id)
    FileUtil.deleteRecursively(jobDir)
    Files.createDirectories(jobDir)

    val output = new StringBuilder
    output.append(s"[meta] job_execution_id=${job.id}\n")

    val baseExecDir = job.source match {
      case Some(src) if src.repo.trim.nonEmpty =>
        val sourceDir = jobDir.resolve("src")
        output.append(s"[checkout] repo=${src.repo} ref=${src.ref.getOrElse("")}\n")
        checkoutSource(src, sourceDir) match {
          case Left(err) =>
            val finalStatus = JobStatusUpdate(agentId, "failed", None, Some(s"checkout failed: $err"), Some(output.toString), None, nowIso)
            api.reportTerminalStatusWithRetry(job.id, finalStatus)
            return
          case Right(_) => sourceDir
        }
      case _ => jobDir
    }

    val jobEnv = job.env.getOrElse(Map.empty)
    DepArtifacts.restore(api, jobEnv, baseExecDir) match {
      case Left(err) =>
        val finalStatus = JobStatusUpdate(agentId, "failed", None, Some(s"dependency artifact download failed: $err"), Some(output.toString), None, nowIso)
        api.reportTerminalStatusWithRetry(job.id, finalStatus)
        return
      case Right(note) => if (note.nonEmpty) output.append(note)
    }

    val timeout = job.timeoutSeconds.getOrElse(0)
    val shell = leaseShell(job.requiredCapabilities.getOrElse(Map.empty))
    val env = mergeEnv(jobEnv)

    def trimOutput(raw: String): String = if (raw.length <= 1024 * 1024) raw else raw.takeRight(1024 * 1024)
    def stepLabel(step: JobStepPlanItem): String = {
      val idx = step.index
      val total = step.total.getOrElse(Math.max(idx, 1))
      val name = step.name.map(_.trim).filter(_.nonEmpty).getOrElse(s"step $idx")
      s"[$idx/$total] $name"
    }
    def stepEvent(step: JobStepPlanItem): JobStatusEvent = {
      JobStatusEvent(
        `type` = "step.started",
        timestampUtc = nowIso,
        step = Some(
          JobStatusEventStep(
            index = step.index,
            total = step.total,
            name = step.name,
            kind = step.kind,
            testName = step.testName,
            testFormat = step.testFormat,
            testReport = step.testReport,
            coverageFormat = step.coverageFormat,
            coverageReport = step.coverageReport
          )
        )
      )
    }

    val rawSteps = job.stepPlan.getOrElse(Nil).sortBy(_.index)
    val steps = if (rawSteps.nonEmpty) rawSteps else List(JobStepPlanItem(1, Some(1), Some("script"), Some(job.script), Some("run"), None, None, None, None, None))

    var timedOut = false
    var runExitCode = 0
    var runError: Option[String] = None
    val collectedSuites = mutable.ListBuffer[TestSuiteReport]()
    var collectedCoverage: Option[CoverageReport] = None

    for (step <- steps if !timedOut && runError.isEmpty) {
      val current = stepLabel(step)
      val runningStep = JobStatusUpdate(
        agentId = agentId,
        status = "running",
        exitCode = None,
        error = None,
        output = Some(trimOutput(output.toString)),
        currentStep = Some(current),
        timestampUtc = nowIso,
        events = Some(List(stepEvent(step)))
      )
      api.reportStatus(job.id, runningStep) match {
        case Left(err) => println(s"[agent] failed to report step status job=${job.id} step=$current err=$err")
        case Right(_) =>
      }

      if (step.kind.exists(_.trim == "dryrun_skip")) {
        output.append(s"[dry-run] skipped step: ${step.name.getOrElse(current)}\n")
      } else {
        val script = step.script.map(_.trim).getOrElse("")
        if (script.nonEmpty) {
          val result = try {
            CommandRunner.run(shell, script, baseExecDir, env, timeout)
          } catch {
            case NonFatal(e) => CommandRunner.Result(-1, s"[run-error] ${e.getMessage}\n", timedOut = false)
          }
          output.append(result.output)
          if (result.timedOut) {
            timedOut = true
          } else if (result.exitCode != 0) {
            runExitCode = result.exitCode
            runError = Some(s"$current failed with exit code ${result.exitCode}")
          }

          if (step.kind.exists(_.trim == "test") && step.testReport.exists(_.trim.nonEmpty)) {
            TestReports.parseSuite(baseExecDir, step) match {
              case Right(suite) =>
                collectedSuites += suite
              case Left(err) =>
                output.append(s"[tests] parse_failed suite=${step.testName.getOrElse("")} path=${step.testReport.getOrElse("")} err=$err\n")
                if (runError.isEmpty) runError = Some(err)
            }
          }

          if (step.kind.exists(_.trim == "test") && step.coverageReport.exists(_.trim.nonEmpty)) {
            TestReports.parseCoverage(baseExecDir, step) match {
              case Right(Some(c)) =>
                collectedCoverage = Some(c)
                output.append(f"[coverage] format=${c.format} coverage=${c.percent.getOrElse(0.0)}%.2f%%\n")
              case Right(None) =>
              case Left(err) =>
                output.append(s"[coverage] parse_failed suite=${step.testName.getOrElse("")} path=${step.coverageReport.getOrElse("")} err=$err\n")
                if (runError.isEmpty) runError = Some(err)
            }
          }
        }
      }
    }

    val artifacts = job.artifactGlobs.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
    if (artifacts.nonEmpty) {
      val (uploads, summary) = ArtifactCollector.collect(baseExecDir, artifacts)
      output.append(summary)
      if (uploads.nonEmpty) {
        api.uploadArtifacts(job.id, uploads) match {
          case Left(err) => output.append(s"\n[artifacts] upload_failed=$err\n")
          case Right(_)  => output.append("\n[artifacts] uploaded\n")
        }
      }
    }

    var report = JobExecutionTestReport(
      total = collectedSuites.map(_.total).sum,
      passed = collectedSuites.map(_.passed).sum,
      failed = collectedSuites.map(_.failed).sum,
      skipped = collectedSuites.map(_.skipped).sum,
      suites = collectedSuites.toList,
      coverage = collectedCoverage
    )
    if (report.total > 0 || report.coverage.nonEmpty) {
      api.uploadTestReport(job.id, report) match {
        case Left(err) => output.append(s"[tests] upload_failed=$err\n")
        case Right(_) => output.append(TestReports.summary(report)).append("\n")
      }
      if (runError.isEmpty && report.failed > 0) {
        runExitCode = if (runExitCode == 0) 1 else runExitCode
        runError = Some(s"test report contains failures: failed=${report.failed}")
      }
    }

    val finalText = trimOutput(output.toString)

    if (timedOut) {
      val failed = JobStatusUpdate(agentId, "failed", None, Some("job timed out"), Some(finalText), None, nowIso)
      api.reportTerminalStatusWithRetry(job.id, failed)
    } else if (runError.isEmpty) {
      val ok = JobStatusUpdate(agentId, "succeeded", Some(0), None, Some(finalText), None, nowIso)
      api.reportTerminalStatusWithRetry(job.id, ok)
    } else {
      val failed = JobStatusUpdate(agentId, "failed", Some(runExitCode), runError, Some(finalText), None, nowIso)
      api.reportTerminalStatusWithRetry(job.id, failed)
    }

  }

  def runLoop(): Int = {
    val serverUrl = env("CIWI_SERVER_URL", "http://127.0.0.1:8112")
    val hostname = java.net.InetAddress.getLocalHost.getHostName
    val workDir = Paths.get(env("CIWI_AGENT_WORKDIR", ".ciwi-agent"))
    Files.createDirectories(workDir)
    val agentId = envOpt("CIWI_AGENT_ID").getOrElse(loadOrCreateAgentId(workDir))

    val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build()
    val api = new ApiClient(serverUrl, agentId, http)
    val capabilities = detectCapabilities()

    @volatile var running = true
    Runtime.getRuntime.addShutdownHook(Thread(() => running = false))

    println(s"[agent] started agent_id=$agentId server_url=$serverUrl")

    var nextHeartbeat = Instant.EPOCH
    var nextLease = Instant.EPOCH
    var busy = false

    while (running) {
      val now = Instant.now()

      if (!busy && now.isAfter(nextLease)) {
        api.lease(capabilities) match {
          case Left(err) =>
            println(s"[agent] lease failed: $err")
          case Right(None) =>
          case Right(Some(job)) =>
            busy = true
            println(s"[agent] leased job ${job.id}")
            try runJob(api, agentId, workDir, job)
            catch {
              case NonFatal(e) => println(s"[agent] execute job failed job=${job.id} err=${e.getMessage}")
            }
            busy = false
        }
        nextLease = Instant.now().plusMillis(leaseInterval.toMillis)
      }

      if (now.isAfter(nextHeartbeat)) {
        api.sendHeartbeat(hostname, capabilities) match {
          case Left(err) => println(s"[agent] heartbeat failed: $err")
          case Right(_)  =>
        }
        nextHeartbeat = Instant.now().plusMillis(heartbeatInterval.toMillis)
      }

      Thread.sleep(250)
    }

    println(s"[agent] stopped agent_id=$agentId")
    0
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val code = CiwiAgent.runLoop()
    sys.exit(code)
  }
}
