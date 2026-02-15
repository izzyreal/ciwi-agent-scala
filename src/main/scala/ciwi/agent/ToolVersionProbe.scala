package ciwi.agent

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

trait ToolVersionProbe {
  def detectVersion(cmd: String, args: List[String]): String
}

object DefaultToolVersionProbe extends ToolVersionProbe {
  override def detectVersion(cmd: String, args: List[String]): String = {
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
      AgentCore.parseToolVersionFromOutput(text)
    } catch {
      case NonFatal(_) => ""
    }
  }
}
