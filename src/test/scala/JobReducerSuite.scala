package ciwi.agent

import munit.FunSuite

final class JobReducerSuite extends FunSuite {

  test("initial state is clean") {
    val s = JobReducer.initial
    assertEquals(s.timedOut, false)
    assertEquals(s.runExitCode, 0)
    assertEquals(s.runError, None)
    assertEquals(s.suites, Nil)
    assertEquals(s.coverage, None)
  }

  test("applyCommandResult marks timeout") {
    val s0 = JobReducer.initial
    val s1 = JobReducer.applyCommandResult(
      s0,
      currentStep = "[1/1] test",
      result = CommandRunner.Result(exitCode = -1, output = "", timedOut = true)
    )
    assertEquals(s1.timedOut, true)
    assertEquals(s1.runError, None)
  }

  test("applyCommandResult captures first non-zero exit as error") {
    val s0 = JobReducer.initial
    val s1 = JobReducer.applyCommandResult(
      s0,
      currentStep = "[2/3] build",
      result = CommandRunner.Result(exitCode = 7, output = "", timedOut = false)
    )
    assertEquals(s1.runExitCode, 7)
    assertEquals(s1.runError, Some("[2/3] build failed with exit code 7"))
  }

  test("applyCommandResult does not overwrite existing error") {
    val s0 = JobReducer.initial.copy(runExitCode = 2, runError = Some("already failed"))
    val s1 = JobReducer.applyCommandResult(
      s0,
      currentStep = "[3/3] test",
      result = CommandRunner.Result(exitCode = 9, output = "", timedOut = false)
    )
    assertEquals(s1.runExitCode, 2)
    assertEquals(s1.runError, Some("already failed"))
  }

  test("suite and coverage aggregation produce expected report") {
    val suiteA = TestSuiteReport(
      name = Some("a"),
      format = "go-test-json",
      total = 3,
      passed = 2,
      failed = 1,
      skipped = 0,
      cases = Nil
    )
    val suiteB = TestSuiteReport(
      name = Some("b"),
      format = "go-test-json",
      total = 2,
      passed = 2,
      failed = 0,
      skipped = 0,
      cases = Nil
    )
    val cov = CoverageReport(
      format = "go-coverprofile",
      totalLines = None,
      coveredLines = None,
      totalStatements = Some(10),
      coveredStatements = Some(8),
      percent = Some(80.0),
      files = Nil
    )

    val s0 = JobReducer.initial
    val s1 = JobReducer.addSuite(s0, suiteA)
    val s2 = JobReducer.addSuite(s1, suiteB)
    val s3 = JobReducer.addCoverage(s2, cov)
    val report = JobReducer.toReport(s3)

    assertEquals(report.total, 5)
    assertEquals(report.passed, 4)
    assertEquals(report.failed, 1)
    assertEquals(report.skipped, 0)
    assertEquals(report.suites.length, 2)
    assertEquals(report.coverage.map(_.format), Some("go-coverprofile"))
  }

  test("failIfNotSet sets error once and preserves exit code when already non-zero") {
    val s0 = JobReducer.initial
    val s1 = JobReducer.failIfNotSet(s0, "broken")
    assertEquals(s1.runError, Some("broken"))
    assertEquals(s1.runExitCode, 1)

    val s2 = JobReducer.failIfNotSet(s1.copy(runExitCode = 7), "other")
    assertEquals(s2.runError, Some("broken"))
    assertEquals(s2.runExitCode, 7)
  }
}
