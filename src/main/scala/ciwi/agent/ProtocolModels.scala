package ciwi.agent

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
