package ciwi.agent

trait CapabilityProvider {
  def detectCapabilities(): Map[String, String]
}

object RuntimeCapabilityProvider extends CapabilityProvider {
  override def detectCapabilities(): Map[String, String] = CiwiAgent.detectCapabilities()
}
