package akka.projection.testing

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config

object EventProcessorSettings {

  def apply(system: ActorSystem[_]): EventProcessorSettings = {
    apply(system.settings.config.getConfig("event-processor"))
  }

  def apply(config: Config): EventProcessorSettings = {
    val parallelism: Int = config.getInt("parallelism")
    val nrProjections: Int = config.getInt("nr-projections")
    EventProcessorSettings(parallelism, nrProjections)
  }
}

final case class EventProcessorSettings(parallelism: Int, nrProjections: Int)
