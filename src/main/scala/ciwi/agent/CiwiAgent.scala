package ciwi.agent

import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.util.matching.Regex

object CiwiAgent {
  private val heartbeatInterval = 10.seconds
  private val leaseInterval = 3.seconds
  private val versionPattern: Regex = raw"([0-9]+(?:\.[0-9]+){1,3})".r
  private val restartRequestedStatusMessage = "restart requested but not implemented in scala agent"

  private def env(name: String, default: String): String = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse(default)
  private def envOpt(name: String): Option[String] = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)
  private def boolEnv(name: String, default: Boolean): Boolean = {
    envOpt(name)
      .map(_.toLowerCase)
      .map {
        case "1" | "true" | "yes" | "on" => true
        case "0" | "false" | "no" | "off" => false
        case _ => default
      }
      .getOrElse(default)
  }

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

  private[agent] def detectToolVersion(cmd: String, args: List[String]): String = {
    try {
      val pb = new ProcessBuilder((cmd :: args).asJava)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val done = proc.waitFor(2, TimeUnit.SECONDS)
      if (!done) {
        proc.destroyForcibly()
        proc.waitFor(1, TimeUnit.SECONDS)
        return ""
      }
      val text = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      if (text.isEmpty) return ""
      val normalized =
        if (text.contains("go version go")) text.replace("go version go", "go version ")
        else text
      versionPattern.findFirstMatchIn(normalized).map(_.group(1)).getOrElse("")
    } catch {
      case NonFatal(_) => ""
    }
  }

  private[agent] def detectToolVersions(): Map[String, String] = {
    val tools = List(
      ("git", "git", List("--version")),
      ("go", "go", List("version")),
      ("gh", "gh", List("--version")),
      ("cmake", "cmake", List("--version")),
      ("ninja", "ninja", List("--version")),
      ("docker", "docker", List("--version")),
      ("gcc", "gcc", List("--version")),
      ("clang", "clang", List("--version")),
      ("xcodebuild", "xcodebuild", List("-version"))
    )
    tools.flatMap { case (name, cmd, args) =>
      val v = detectToolVersion(cmd, args)
      if (v.nonEmpty) Some(name -> v) else None
    }.toMap
  }

  private[agent] def detectCapabilities(): Map[String, String] = {
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    val shells = if (os.contains("win")) "cmd,powershell" else "posix"
    val base = Map(
      "executor" -> "script",
      "shells" -> shells,
      "os" -> (if (os.contains("mac")) "darwin" else if (os.contains("win")) "windows" else "linux"),
      "arch" -> arch
    )
    val toolCaps = detectToolVersions().map { case (name, version) => s"tool.$name" -> version }
    base ++ toolCaps
  }

  private[agent] def applyHeartbeatDirectives(
    hb: HeartbeatResponse,
    currentCapabilities: Map[String, String],
    capabilityDetector: () => Map[String, String]
  ): (Map[String, String], Option[String], Boolean) = {
    var capabilities = currentCapabilities
    var refreshed = false
    var restartStatus: Option[String] = None
    if (hb.refreshToolsRequested.getOrElse(false)) {
      capabilities = capabilityDetector()
      refreshed = true
    }
    if (hb.restartRequested.getOrElse(false)) {
      restartStatus = Some(restartRequestedStatusMessage)
    }
    (capabilities, restartStatus, refreshed)
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

  private def checkoutSource(source: SourceSpec, targetDir: Path): Either[String, String] = {
    def runCapture(cmd: List[String]): (Int, String) = {
      val pb = new ProcessBuilder(cmd.asJava)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val text = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val exit = proc.waitFor()
      (exit, text)
    }

    val out = new StringBuilder
    try {
      Files.createDirectories(targetDir.getParent)
      val cloneCmd = List("git", "clone", "--depth", "1", source.repo, targetDir.toString)
      val (cloneExit, cloneOut) = runCapture(cloneCmd)
      out.append(cloneOut)
      if (cloneExit != 0) return Left(out.toString.trim)

      val ref = source.ref.map(_.trim).filter(_.nonEmpty)
      ref match {
        case None => Right(out.toString)
        case Some(r) =>
          val fetchCmd = List("git", "-C", targetDir.toString, "fetch", "--depth", "1", "origin", r)
          val (fetchExit, fetchOut) = runCapture(fetchCmd)
          out.append(fetchOut)
          if (fetchExit != 0) return Left(out.toString.trim)

          val checkoutCmd = List("git", "-C", targetDir.toString, "checkout", "--force", "FETCH_HEAD")
          val (checkoutExit, checkoutOut) = runCapture(checkoutCmd)
          out.append(checkoutOut)
          if (checkoutExit != 0) return Left(out.toString.trim)

          Right(out.toString)
      }
    } catch {
      case NonFatal(e) =>
        out.append(e.getMessage)
        Left(out.toString.trim)
    }
  }

  private def nowIso: String = Instant.now().toString

  private[agent] def runJob(api: ApiClient, agentId: String, workDir: Path, job: JobExecution): Unit = {
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
          case Right(checkoutOutput) =>
            if (checkoutOutput.nonEmpty) output.append(checkoutOutput)
            sourceDir
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
    val traceShell = boolEnv("CIWI_AGENT_TRACE_SHELL", default = true)

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
          val effectiveScript =
            if (shell == "posix" && traceShell) "set -x\n" + script
            else script
          val result = try {
            CommandRunner.run(shell, effectiveScript, baseExecDir, env, timeout)
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

  def runLoop(): Int = runLoop(RuntimeCapabilityProvider)

  private[agent] def runLoop(capabilityProvider: CapabilityProvider): Int = {
    val serverUrl = env("CIWI_SERVER_URL", "http://127.0.0.1:8112")
    val hostname = java.net.InetAddress.getLocalHost.getHostName
    val workDir = Paths.get(env("CIWI_AGENT_WORKDIR", ".ciwi-agent"))
    Files.createDirectories(workDir)
    val agentId = envOpt("CIWI_AGENT_ID").getOrElse(loadOrCreateAgentId(workDir))

    val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build()
    val api = new ApiClient(serverUrl, agentId, http)
    var capabilities = capabilityProvider.detectCapabilities()
    var pendingUpdateFailure: Option[String] = None
    var pendingRestartStatus: Option[String] = None

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
        api.sendHeartbeat(hostname, capabilities, pendingUpdateFailure, pendingRestartStatus) match {
          case Left(err) => println(s"[agent] heartbeat failed: $err")
          case Right(hb) =>
            pendingUpdateFailure = None
            pendingRestartStatus = None
            val (updatedCapabilities, restartStatus, refreshed) =
              applyHeartbeatDirectives(hb, capabilities, () => capabilityProvider.detectCapabilities())
            capabilities = updatedCapabilities
            if (refreshed) {
              println("[agent] server requested tools refresh")
              api.sendHeartbeat(hostname, capabilities, pendingUpdateFailure, pendingRestartStatus) match {
                case Left(err) => println(s"[agent] heartbeat failed: $err")
                case Right(_) =>
                  pendingUpdateFailure = None
                  pendingRestartStatus = None
              }
            }
            if (restartStatus.nonEmpty) {
              pendingRestartStatus = restartStatus
              println(s"[agent] ${restartStatus.get}")
              api.sendHeartbeat(hostname, capabilities, pendingUpdateFailure, pendingRestartStatus) match {
                case Left(err) => println(s"[agent] heartbeat failed while reporting restart status: $err")
                case Right(_) =>
                  pendingUpdateFailure = None
                  pendingRestartStatus = None
              }
            }
        }
        nextHeartbeat = Instant.now().plusMillis(heartbeatInterval.toMillis)
      }

      Thread.sleep(250)
    }

    println(s"[agent] stopped agent_id=$agentId")
    0
  }
}
