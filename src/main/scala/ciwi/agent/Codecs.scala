package ciwi.agent

import io.circe._
import io.circe.syntax._

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
