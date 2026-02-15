package ciwi.agent

import io.circe.Encoder
import io.circe.parser.decode
import io.circe.syntax._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant

final class ApiClient(
  serverUrl: String,
  agentId: String,
  http: HttpClient,
  retryBackoffBaseMillis: Long = 1000L
) {
  import Codecs.given

  private[agent] def sleepMillis(ms: Long): Unit = Thread.sleep(ms)

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

  def sendHeartbeat(
    hostname: String,
    capabilities: Map[String, String],
    updateFailure: Option[String] = None,
    restartStatus: Option[String] = None
  ): Either[String, HeartbeatResponse] = {
    val req = HeartbeatRequest(
      agentId = agentId,
      hostname = hostname,
      os = System.getProperty("os.name").toLowerCase,
      arch = System.getProperty("os.arch").toLowerCase,
      version = "scala-agent-dev",
      capabilities = capabilities,
      updateFailure = updateFailure.map(_.trim).filter(_.nonEmpty),
      restartStatus = restartStatus.map(_.trim).filter(_.nonEmpty),
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
            val waitMillis = retryBackoffBaseMillis * Math.pow(2.0, (attempt - 1).toDouble).toLong
            sleepMillis(waitMillis)
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
