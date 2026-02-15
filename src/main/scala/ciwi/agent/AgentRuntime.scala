package ciwi.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters._

trait AgentRuntime {
  def envOpt(name: String): Option[String]
  def env(name: String, default: String): String = envOpt(name).getOrElse(default)
  def nowInstant(): Instant
  def nowIso(): String = nowInstant().toString
  def sleepMillis(ms: Long): Unit
  def hostname(): String
  def osNameLower(): String
  def archLower(): String
  def mergedEnv(jobEnv: Map[String, String]): Map[String, String]

  def loadOrCreateAgentId(workDir: Path): String = {
    val base = s"scala-${hostname()}-${osNameLower()}-${archLower()}"
      .replaceAll("[^a-zA-Z0-9._-]+", "-")
      .replaceAll("-+", "-")
    val path = workDir.resolve("agent-id")
    if (Files.exists(path)) {
      val existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim
      if (existing.nonEmpty) return existing
    }
    Files.write(path, (base + "\n").getBytes(StandardCharsets.UTF_8))
    base
  }
}

object SystemAgentRuntime extends AgentRuntime {
  override def envOpt(name: String): Option[String] =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)

  override def nowInstant(): Instant = Instant.now()
  override def sleepMillis(ms: Long): Unit = Thread.sleep(ms)
  override def hostname(): String = java.net.InetAddress.getLocalHost.getHostName
  override def osNameLower(): String = System.getProperty("os.name").toLowerCase
  override def archLower(): String = System.getProperty("os.arch").toLowerCase
  override def mergedEnv(jobEnv: Map[String, String]): Map[String, String] = System.getenv().asScala.toMap ++ jobEnv
}
