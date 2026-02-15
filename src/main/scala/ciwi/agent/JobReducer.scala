package ciwi.agent

object JobReducer {
  final case class State(
    timedOut: Boolean,
    runExitCode: Int,
    runError: Option[String],
    suites: List[TestSuiteReport],
    coverage: Option[CoverageReport]
  )

  def initial: State = State(
    timedOut = false,
    runExitCode = 0,
    runError = None,
    suites = Nil,
    coverage = None
  )

  def applyCommandResult(state: State, currentStep: String, result: CommandRunner.Result): State = {
    if (result.timedOut) state.copy(timedOut = true)
    else if (result.exitCode != 0 && state.runError.isEmpty) {
      state.copy(runExitCode = result.exitCode, runError = Some(s"$currentStep failed with exit code ${result.exitCode}"))
    } else state
  }

  def addSuite(state: State, suite: TestSuiteReport): State =
    state.copy(suites = state.suites :+ suite)

  def addCoverage(state: State, coverage: CoverageReport): State =
    state.copy(coverage = Some(coverage))

  def failIfNotSet(state: State, error: String, exitCodeIfUnset: Int = 1): State = {
    if (state.runError.nonEmpty) state
    else state.copy(runExitCode = if (state.runExitCode == 0) exitCodeIfUnset else state.runExitCode, runError = Some(error))
  }

  def toReport(state: State): JobExecutionTestReport = JobExecutionTestReport(
    total = state.suites.map(_.total).sum,
    passed = state.suites.map(_.passed).sum,
    failed = state.suites.map(_.failed).sum,
    skipped = state.suites.map(_.skipped).sum,
    suites = state.suites,
    coverage = state.coverage
  )
}
