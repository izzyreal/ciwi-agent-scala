package ciwi.agent

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

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
