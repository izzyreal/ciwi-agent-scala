package ciwi.agent

import java.net.http.HttpClient
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.concurrent.duration._
import scala.util.control.NonFatal

object CiwiAgent {
  private val heartbeatInterval = 10.seconds
  private val leaseInterval = 3.seconds

  private val toolCommands: List[(String, String, List[String])] = List(
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

  private[agent] def detectToolVersion(cmd: String, args: List[String]): String =
    DefaultToolVersionProbe.detectVersion(cmd, args)

  private[agent] def detectToolVersions(probe: ToolVersionProbe = DefaultToolVersionProbe): Map[String, String] = {
    toolCommands.flatMap { case (name, cmd, args) =>
      val v = probe.detectVersion(cmd, args)
      if (v.nonEmpty) Some(name -> v) else None
    }.toMap
  }

  private[agent] def detectCapabilities(
    runtime: AgentRuntime = SystemAgentRuntime,
    probe: ToolVersionProbe = DefaultToolVersionProbe
  ): Map[String, String] = {
    val base = AgentCore.baseCapabilities(runtime.osNameLower(), runtime.archLower())
    AgentCore.withToolCapabilities(base, detectToolVersions(probe))
  }

  private[agent] def applyHeartbeatDirectives(
    hb: HeartbeatResponse,
    currentCapabilities: Map[String, String],
    capabilityDetector: () => Map[String, String]
  ): (Map[String, String], Option[String], Boolean) =
    AgentCore.applyHeartbeatDirectives(hb, currentCapabilities, capabilityDetector)

  private def boolEnv(runtime: AgentRuntime, name: String, default: Boolean): Boolean =
    AgentCore.parseBoolean(runtime.envOpt(name), default)

  private def nowIso(runtime: AgentRuntime): String = runtime.nowIso()

  private def leaseShell(required: Map[String, String], runtime: AgentRuntime): String = {
    val asked = required.getOrElse("shell", "").trim.toLowerCase
    if (asked.nonEmpty) asked
    else if (runtime.osNameLower().contains("win")) "powershell" else "posix"
  }

  private[agent] def runJob(api: ApiClient, agentId: String, workDir: Path, job: JobExecution): Unit =
    runJob(api, agentId, workDir, job, SystemAgentRuntime, GitCheckoutRunner, StdoutAgentLogger)

  private[agent] def runJob(api: ApiClient, agentId: String, workDir: Path, job: JobExecution, runtime: AgentRuntime): Unit =
    runJob(api, agentId, workDir, job, runtime, GitCheckoutRunner, StdoutAgentLogger)

  private[agent] def runJob(
    api: ApiClient,
    agentId: String,
    workDir: Path,
    job: JobExecution,
    runtime: AgentRuntime,
    checkoutRunner: CheckoutRunner,
    logger: AgentLogger
  ): Unit = {
    val running = JobStatusUpdate(
      agentId = agentId,
      status = "running",
      exitCode = None,
      error = None,
      output = None,
      currentStep = Some("Preparing execution"),
      timestampUtc = nowIso(runtime)
    )
    api.reportStatus(job.id, running) match {
      case Left(err) => logger.warn(s"[agent] failed to report running status job=${job.id}: $err")
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
        checkoutRunner.checkout(src, sourceDir) match {
          case Left(err) =>
            val finalStatus = JobStatusUpdate(agentId, "failed", None, Some(s"checkout failed: $err"), Some(output.toString), None, nowIso(runtime))
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
        val finalStatus = JobStatusUpdate(agentId, "failed", None, Some(s"dependency artifact download failed: $err"), Some(output.toString), None, nowIso(runtime))
        api.reportTerminalStatusWithRetry(job.id, finalStatus)
        return
      case Right(note) => if (note.nonEmpty) output.append(note)
    }

    val timeout = job.timeoutSeconds.getOrElse(0)
    val shell = leaseShell(job.requiredCapabilities.getOrElse(Map.empty), runtime)
    val env = runtime.mergedEnv(jobEnv)
    val traceShell = boolEnv(runtime, "CIWI_AGENT_TRACE_SHELL", default = true)

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
        timestampUtc = nowIso(runtime),
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

    var state = JobReducer.initial

    for (step <- steps if !state.timedOut && state.runError.isEmpty) {
      val current = stepLabel(step)
      val runningStep = JobStatusUpdate(
        agentId = agentId,
        status = "running",
        exitCode = None,
        error = None,
        output = Some(trimOutput(output.toString)),
        currentStep = Some(current),
        timestampUtc = nowIso(runtime),
        events = Some(List(stepEvent(step)))
      )
      api.reportStatus(job.id, runningStep) match {
        case Left(err) => logger.warn(s"[agent] failed to report step status job=${job.id} step=$current err=$err")
        case Right(_) =>
      }

      if (step.kind.exists(_.trim == "dryrun_skip")) {
        output.append(s"[dry-run] skipped step: ${step.name.getOrElse(current)}\n")
      } else {
        val script = step.script.map(_.trim).getOrElse("")
        if (script.nonEmpty) {
          val effectiveScript = if (shell == "posix" && traceShell) "set -x\n" + script else script
          val result = try {
            CommandRunner.run(shell, effectiveScript, baseExecDir, env, timeout)
          } catch {
            case NonFatal(e) => CommandRunner.Result(-1, s"[run-error] ${e.getMessage}\n", timedOut = false)
          }
          output.append(result.output)
          state = JobReducer.applyCommandResult(state, current, result)

          if (step.kind.exists(_.trim == "test") && step.testReport.exists(_.trim.nonEmpty)) {
            TestReports.parseSuite(baseExecDir, step) match {
              case Right(suite) =>
                state = JobReducer.addSuite(state, suite)
              case Left(err) =>
                output.append(s"[tests] parse_failed suite=${step.testName.getOrElse("")} path=${step.testReport.getOrElse("")} err=$err\n")
                state = JobReducer.failIfNotSet(state, err)
            }
          }

          if (step.kind.exists(_.trim == "test") && step.coverageReport.exists(_.trim.nonEmpty)) {
            TestReports.parseCoverage(baseExecDir, step) match {
              case Right(Some(c)) =>
                state = JobReducer.addCoverage(state, c)
                output.append(f"[coverage] format=${c.format} coverage=${c.percent.getOrElse(0.0)}%.2f%%\n")
              case Right(None) =>
              case Left(err) =>
                output.append(s"[coverage] parse_failed suite=${step.testName.getOrElse("")} path=${step.coverageReport.getOrElse("")} err=$err\n")
                state = JobReducer.failIfNotSet(state, err)
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

    val report = JobReducer.toReport(state)
    if (report.total > 0 || report.coverage.nonEmpty) {
      api.uploadTestReport(job.id, report) match {
        case Left(err) => output.append(s"[tests] upload_failed=$err\n")
        case Right(_) => output.append(TestReports.summary(report)).append("\n")
      }
      if (state.runError.isEmpty && report.failed > 0) {
        state = JobReducer.failIfNotSet(state, s"test report contains failures: failed=${report.failed}")
      }
    }

    val finalText = trimOutput(output.toString)

    if (state.timedOut) {
      val failed = JobStatusUpdate(agentId, "failed", None, Some("job timed out"), Some(finalText), None, nowIso(runtime))
      api.reportTerminalStatusWithRetry(job.id, failed)
    } else if (state.runError.isEmpty) {
      val ok = JobStatusUpdate(agentId, "succeeded", Some(0), None, Some(finalText), None, nowIso(runtime))
      api.reportTerminalStatusWithRetry(job.id, ok)
    } else {
      val failed = JobStatusUpdate(agentId, "failed", Some(state.runExitCode), state.runError, Some(finalText), None, nowIso(runtime))
      api.reportTerminalStatusWithRetry(job.id, failed)
    }
  }

  def runLoop(): Int =
    runLoop(RuntimeCapabilityProvider, SystemAgentRuntime, StdoutAgentLogger, GitCheckoutRunner)

  private[agent] def runLoop(capabilityProvider: CapabilityProvider): Int =
    runLoop(capabilityProvider, SystemAgentRuntime, StdoutAgentLogger, GitCheckoutRunner)

  private[agent] def runLoop(
    capabilityProvider: CapabilityProvider,
    runtime: AgentRuntime,
    logger: AgentLogger,
    checkoutRunner: CheckoutRunner
  ): Int = {
    val serverUrl = runtime.env("CIWI_SERVER_URL", "http://127.0.0.1:8112")
    val hostname = runtime.hostname()
    val workDir = Paths.get(runtime.env("CIWI_AGENT_WORKDIR", ".ciwi-agent"))
    Files.createDirectories(workDir)
    val agentId = runtime.envOpt("CIWI_AGENT_ID").getOrElse(runtime.loadOrCreateAgentId(workDir))

    val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build()
    val api = new ApiClient(serverUrl, agentId, http)
    var capabilities = capabilityProvider.detectCapabilities()
    var pendingUpdateFailure: Option[String] = None
    var pendingRestartStatus: Option[String] = None

    @volatile var running = true
    Runtime.getRuntime.addShutdownHook(Thread(() => running = false))

    logger.info(s"[agent] started agent_id=$agentId server_url=$serverUrl")

    var nextHeartbeat = Instant.EPOCH
    var nextLease = Instant.EPOCH
    var busy = false

    while (running) {
      val now = runtime.nowInstant()

      if (!busy && now.isAfter(nextLease)) {
        api.lease(capabilities) match {
          case Left(err) =>
            logger.error(s"[agent] lease failed: $err")
          case Right(None) =>
          case Right(Some(job)) =>
            busy = true
            logger.info(s"[agent] leased job ${job.id}")
            try runJob(api, agentId, workDir, job, runtime, checkoutRunner, logger)
            catch {
              case NonFatal(e) => logger.error(s"[agent] execute job failed job=${job.id} err=${e.getMessage}")
            }
            busy = false
        }
        nextLease = runtime.nowInstant().plusMillis(leaseInterval.toMillis)
      }

      if (now.isAfter(nextHeartbeat)) {
        api.sendHeartbeat(hostname, capabilities, pendingUpdateFailure, pendingRestartStatus) match {
          case Left(err) => logger.error(s"[agent] heartbeat failed: $err")
          case Right(hb) =>
            pendingUpdateFailure = None
            pendingRestartStatus = None
            val (updatedCapabilities, restartStatus, refreshed) =
              applyHeartbeatDirectives(hb, capabilities, () => capabilityProvider.detectCapabilities())
            capabilities = updatedCapabilities
            if (refreshed) {
              logger.info("[agent] server requested tools refresh")
              api.sendHeartbeat(hostname, capabilities, pendingUpdateFailure, pendingRestartStatus) match {
                case Left(err) => logger.error(s"[agent] heartbeat failed: $err")
                case Right(_) =>
                  pendingUpdateFailure = None
                  pendingRestartStatus = None
              }
            }
            if (restartStatus.nonEmpty) {
              pendingRestartStatus = restartStatus
              logger.info(s"[agent] ${restartStatus.get}")
              api.sendHeartbeat(hostname, capabilities, pendingUpdateFailure, pendingRestartStatus) match {
                case Left(err) => logger.error(s"[agent] heartbeat failed while reporting restart status: $err")
                case Right(_) =>
                  pendingUpdateFailure = None
                  pendingRestartStatus = None
              }
            }
        }
        nextHeartbeat = runtime.nowInstant().plusMillis(heartbeatInterval.toMillis)
      }

      runtime.sleepMillis(250)
    }

    logger.info(s"[agent] stopped agent_id=$agentId")
    0
  }
}
