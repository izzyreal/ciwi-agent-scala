package ciwi.agent

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import munit.FunSuite

import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

final class CiwiAgentSuite extends FunSuite {

  private def withServer(handler: HttpExchange => Unit)(body: String => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = handler(exchange)
    })
    server.start()
    val base = s"http://127.0.0.1:${server.getAddress.getPort}"
    try body(base)
    finally server.stop(0)
  }

  private def readBody(ex: HttpExchange): String = {
    val bytes = ex.getRequestBody.readAllBytes()
    new String(bytes, StandardCharsets.UTF_8)
  }

  private def respond(ex: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val out = ex.getResponseBody
    out.write(bytes)
    out.close()
    ex.close()
  }

  test("ApiClient.sendHeartbeat posts expected payload and decodes response") {
    val seenBody = new AtomicReference[String]("")
    withServer { ex =>
      assertEquals(ex.getRequestMethod, "POST")
      assertEquals(ex.getRequestURI.getPath, "/api/v1/heartbeat")
      seenBody.set(readBody(ex))
      respond(ex, 200, """{"accepted":true,"update_requested":true,"update_target":"v2.0.0"}""")
    } { base =>
      val api = new ApiClient(base, "agent-scala", HttpClient.newHttpClient())
      val resp = api.sendHeartbeat("host-a", Map("executor" -> "script"))
      assert(resp.isRight, clues(resp))
      val r = resp.toOption.get
      assertEquals(r.accepted, true)
      assertEquals(r.updateRequested, Some(true))
      assertEquals(r.updateTarget, Some("v2.0.0"))

      val raw = seenBody.get()
      assert(raw.contains("\"agent_id\":\"agent-scala\""))
      assert(raw.contains("\"hostname\":\"host-a\""))
    }
  }

  test("ApiClient.lease returns Some(job) when assigned") {
    withServer { ex =>
      assertEquals(ex.getRequestMethod, "POST")
      assertEquals(ex.getRequestURI.getPath, "/api/v1/agent/lease")
      val payload =
        """{
          |  "assigned": true,
          |  "job_execution": {
          |    "id": "job-1",
          |    "script": "echo hi",
          |    "required_capabilities": {},
          |    "timeout_seconds": 30,
          |    "status": "leased",
          |    "created_utc": "2026-02-15T00:00:00Z"
          |  }
          |}""".stripMargin
      respond(ex, 200, payload)
    } { base =>
      val api = new ApiClient(base, "agent-scala", HttpClient.newHttpClient())
      val leased = api.lease(Map("executor" -> "script"))
      assert(leased.isRight, clues(leased))
      assertEquals(leased.toOption.flatten.map(_.id), Some("job-1"))
    }
  }

  test("ApiClient.lease returns None when not assigned") {
    withServer(ex => respond(ex, 200, """{"assigned":false}""")) { base =>
      val api = new ApiClient(base, "agent-scala", HttpClient.newHttpClient())
      val leased = api.lease(Map("executor" -> "script"))
      assert(leased.isRight, clues(leased))
      assertEquals(leased.toOption.flatten, None)
    }
  }

  test("ApiClient.reportStatus returns Left on non-200") {
    withServer(ex => respond(ex, 409, "conflict")) { base =>
      val api = new ApiClient(base, "agent-scala", HttpClient.newHttpClient())
      val status = JobStatusUpdate("agent-scala", "running", None, None, Some("log"), Some("step"), "2026-02-15T00:00:00Z")
      val res = api.reportStatus("job-1", status)
      assert(res.isLeft, clues(res))
      assert(res.left.toOption.get.contains("status=409"))
    }
  }

  test("ApiClient.uploadTestReport posts expected payload") {
    val seenBody = new AtomicReference[String]("")
    withServer { ex =>
      assertEquals(ex.getRequestMethod, "POST")
      assertEquals(ex.getRequestURI.getPath, "/api/v1/jobs/job-1/tests")
      seenBody.set(readBody(ex))
      respond(ex, 200, "{}")
    } { base =>
      val api = new ApiClient(base, "agent-scala", HttpClient.newHttpClient())
      val report = JobExecutionTestReport(
        total = 1,
        passed = 1,
        failed = 0,
        skipped = 0,
        suites = List(
          TestSuiteReport(
            name = Some("unit"),
            format = "go-test-json",
            total = 1,
            passed = 1,
            failed = 0,
            skipped = 0,
            cases = List(TestCase(Some("pkg"), Some("TestA"), "passed", Some(0.01), None))
          )
        ),
        coverage = Some(CoverageReport("go-coverprofile", None, None, Some(10), Some(8), Some(80.0), Nil))
      )
      val res = api.uploadTestReport("job-1", report)
      assert(res.isRight, clues(res))
      val raw = seenBody.get()
      assert(raw.contains("\"agent_id\":\"agent-scala\""))
      assert(raw.contains("\"report\""))
      assert(raw.contains("\"coverage\""))
      assert(raw.contains("\"format\":\"go-coverprofile\""))
    }
  }

  test("ArtifactCollector.collect picks matching files and encodes base64") {
    val root = Files.createTempDirectory("ciwi-artifacts-")
    try {
      Files.createDirectories(root.resolve("dist/nested"))
      Files.writeString(root.resolve("dist/a.txt"), "A")
      Files.writeString(root.resolve("dist/nested/b.txt"), "B")

      val (uploads, summary) = ArtifactCollector.collect(root, List("dist/**/*.txt"))
      assertEquals(uploads.map(_.path).sorted, List("dist/a.txt", "dist/nested/b.txt"))
      assert(summary.contains("[artifacts] include=dist/a.txt"))

      val decoded = uploads.map(a => a.path -> new String(Base64.getDecoder.decode(a.dataBase64), StandardCharsets.UTF_8)).toMap
      assertEquals(decoded("dist/a.txt"), "A")
      assertEquals(decoded("dist/nested/b.txt"), "B")
    } finally {
      deleteRecursively(root)
    }
  }

  test("DepArtifacts.dependencyJobIds deduplicates and preserves order") {
    val env = Map(
      "CIWI_DEP_ARTIFACT_JOB_IDS" -> "job-a, job-b ,job-a",
      "CIWI_DEP_ARTIFACT_JOB_ID" -> "job-c"
    )
    assertEquals(DepArtifacts.dependencyJobIds(env), List("job-a", "job-b", "job-c"))
  }

  test("DepArtifacts.restore downloads and writes dependency artifacts") {
    val root = Files.createTempDirectory("ciwi-dep-artifacts-")
    try {
      withServer { ex =>
        val path = ex.getRequestURI.getPath
        path match {
          case "/api/v1/jobs/job-build-1/artifacts" =>
            val payload = """{"artifacts":[{"path":"dist/a.bin","url":"/artifacts/job-build-1/dist/a.bin"},{"path":"dist/b.txt","url":"/artifacts/job-build-1/dist/b.txt"}]}"""
            respond(ex, 200, payload)
          case "/artifacts/job-build-1/dist/a.bin" =>
            val bytes = "AAA".getBytes(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.length.toLong)
            val out = ex.getResponseBody
            out.write(bytes)
            out.close()
            ex.close()
          case "/artifacts/job-build-1/dist/b.txt" =>
            val bytes = "BBB".getBytes(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.length.toLong)
            val out = ex.getResponseBody
            out.write(bytes)
            out.close()
            ex.close()
          case _ =>
            respond(ex, 404, "not found")
        }
      } { base =>
        val api = new ApiClient(base, "agent-scala", HttpClient.newHttpClient())
        val env = Map("CIWI_DEP_ARTIFACT_JOB_ID" -> "job-build-1")
        val res = DepArtifacts.restore(api, env, root)
        assert(res.isRight, clues(res))

        assertEquals(Files.readString(root.resolve("dist/a.bin")), "AAA")
        assertEquals(Files.readString(root.resolve("dist/b.txt")), "BBB")
      }
    } finally {
      deleteRecursively(root)
    }
  }

  test("TestReports.parseSuite parses go test json report") {
    val root = Files.createTempDirectory("ciwi-test-report-")
    try {
      val report =
        """{"Action":"run","Package":"pkg/a","Test":"TestPass"}
          |{"Action":"pass","Package":"pkg/a","Test":"TestPass","Elapsed":0.12}
          |{"Action":"skip","Package":"pkg/a","Test":"TestSkip","Elapsed":0.01}
          |{"Action":"fail","Package":"pkg/a","Test":"TestFail","Elapsed":0.05}
          |""".stripMargin
      Files.writeString(root.resolve("report.jsonl"), report)

      val step = JobStepPlanItem(
        index = 1,
        total = Some(1),
        name = Some("unit"),
        script = None,
        kind = Some("test"),
        testName = Some("unit"),
        testFormat = Some("go-test-json"),
        testReport = Some("report.jsonl"),
        coverageFormat = None,
        coverageReport = None
      )

      val suite = TestReports.parseSuite(root, step)
      assert(suite.isRight, clues(suite))
      val s = suite.toOption.get
      assertEquals(s.total, 3)
      assertEquals(s.passed, 1)
      assertEquals(s.failed, 1)
      assertEquals(s.skipped, 1)
      assertEquals(s.cases.map(_.name.getOrElse("")).toSet, Set("TestPass", "TestSkip", "TestFail"))
    } finally {
      deleteRecursively(root)
    }
  }

  test("TestReports.parseCoverage parses go coverprofile report") {
    val root = Files.createTempDirectory("ciwi-coverprofile-")
    try {
      val report =
        """mode: set
          |pkg/a.go:1.1,2.1 2 1
          |pkg/a.go:3.1,4.1 1 0
          |pkg/b.go:1.1,1.2 1 2
          |""".stripMargin
      Files.writeString(root.resolve("coverage.out"), report)

      val step = JobStepPlanItem(
        index = 1,
        total = Some(1),
        name = Some("unit"),
        script = None,
        kind = Some("test"),
        testName = Some("unit"),
        testFormat = Some("go-test-json"),
        testReport = Some("report.jsonl"),
        coverageFormat = Some("go-coverprofile"),
        coverageReport = Some("coverage.out")
      )

      val cov = TestReports.parseCoverage(root, step)
      assert(cov.isRight, clues(cov))
      val c = cov.toOption.flatten.get
      assertEquals(c.format, "go-coverprofile")
      assertEquals(c.totalStatements, Some(4))
      assertEquals(c.coveredStatements, Some(3))
      assertEquals(c.files.length, 2)
      assert(math.abs(c.percent.getOrElse(0.0) - 75.0) < 0.0001, clues(c.percent))
    } finally {
      deleteRecursively(root)
    }
  }

  test("TestReports.parseCoverage parses lcov report") {
    val root = Files.createTempDirectory("ciwi-lcov-")
    try {
      val report =
        """SF:pkg/a.go
          |DA:1,1
          |DA:2,0
          |end_of_record
          |SF:pkg/b.go
          |LF:2
          |LH:2
          |end_of_record
          |""".stripMargin
      Files.writeString(root.resolve("coverage.lcov"), report)

      val step = JobStepPlanItem(
        index = 1,
        total = Some(1),
        name = Some("unit"),
        script = None,
        kind = Some("test"),
        testName = Some("unit"),
        testFormat = Some("go-test-json"),
        testReport = Some("report.jsonl"),
        coverageFormat = Some("lcov"),
        coverageReport = Some("coverage.lcov")
      )

      val cov = TestReports.parseCoverage(root, step)
      assert(cov.isRight, clues(cov))
      val c = cov.toOption.flatten.get
      assertEquals(c.format, "lcov")
      assertEquals(c.totalLines, Some(4))
      assertEquals(c.coveredLines, Some(3))
      assertEquals(c.files.length, 2)
      assert(math.abs(c.percent.getOrElse(0.0) - 75.0) < 0.0001, clues(c.percent))
    } finally {
      deleteRecursively(root)
    }
  }

  test("CommandRunner.run executes posix scripts") {
    val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
    if (!isWindows) {
      val root = Files.createTempDirectory("ciwi-runner-")
      try {
        val result = CommandRunner.run("posix", "echo hello", root, Map.empty, timeoutSeconds = 10)
        assertEquals(result.exitCode, 0)
        assert(result.output.contains("hello"), clues(result.output))
        assertEquals(result.timedOut, false)
      } finally {
        deleteRecursively(root)
      }
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.notExists(path)) return
    Files.walk(path).iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
  }
}
