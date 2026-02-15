package ciwi.agent

import scala.util.matching.Regex

object AgentCore {
  private val VersionPattern: Regex = raw"([0-9]+(?:\.[0-9]+){1,3})".r
  val RestartRequestedStatusMessage: String = "restart requested but not implemented in scala agent"

  def parseBoolean(raw: Option[String], default: Boolean): Boolean = {
    raw.map(_.trim.toLowerCase).filter(_.nonEmpty).map {
      case "1" | "true" | "yes" | "on" => true
      case "0" | "false" | "no" | "off" => false
      case _ => default
    }.getOrElse(default)
  }

  def normalizeOs(rawOs: String): String = {
    val os = Option(rawOs).getOrElse("").toLowerCase
    if (os.contains("mac")) "darwin"
    else if (os.contains("win")) "windows"
    else "linux"
  }

  def baseCapabilities(rawOs: String, arch: String): Map[String, String] = {
    val os = Option(rawOs).getOrElse("").toLowerCase
    val shells = if (os.contains("win")) "cmd,powershell" else "posix"
    Map(
      "executor" -> "script",
      "shells" -> shells,
      "os" -> normalizeOs(os),
      "arch" -> Option(arch).getOrElse("").toLowerCase
    )
  }

  def withToolCapabilities(base: Map[String, String], toolVersions: Map[String, String]): Map[String, String] = {
    val toolCaps = toolVersions.map { case (name, version) => s"tool.$name" -> version }
    base ++ toolCaps
  }

  def parseToolVersionFromOutput(raw: String): String = {
    val text = Option(raw).getOrElse("").trim
    if (text.isEmpty) return ""
    val normalized =
      if (text.contains("go version go")) text.replace("go version go", "go version ")
      else text
    VersionPattern.findFirstMatchIn(normalized).map(_.group(1)).getOrElse("")
  }

  def applyHeartbeatDirectives(
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
      restartStatus = Some(RestartRequestedStatusMessage)
    }
    (capabilities, restartStatus, refreshed)
  }
}
