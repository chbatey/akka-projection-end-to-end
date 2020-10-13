package akka.projection.testing

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import javax.sql.DataSource

import scala.concurrent.duration.FiniteDuration

object TestValidation {
  def apply(testName: String, nrProjections: Int, expectedNrEvents: Long, timeout: FiniteDuration, source: DataSource): Behavior[String] = {
    import scala.concurrent.duration._
    Behaviors.setup { ctx =>
      def validate(): Boolean = {
        val results: Seq[Boolean] = (0 until nrProjections).map { projectionId =>
          val connection = source.getConnection
          try {
            val resultSet = connection.createStatement().executeQuery(s"select count(*) from events where name = '$testName' and projection_id = $projectionId")
            if (resultSet.next()) {
              val count = resultSet.getInt("count")
              ctx.log.info("Expected {} got {}!", expectedNrEvents, count)
              expectedNrEvents == count
            } else {
              throw new RuntimeException("Expected single row")
            }
          } finally {
            connection.close()
          }
        }

        results.forall(identity)
      }

      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate("test", 2.seconds)
        timers.startSingleTimer("timeout", timeout)
        Behaviors.receiveMessage {
          case "test" =>
            if (validate()) {
              ctx.log.info("Validated. Stopping")
              Behaviors.stopped
            } else {
              Behaviors.same
            }
          case "timeout" =>
            ctx.log.error("Timout out")
            Behaviors.stopped
        }
      }
    }
  }
}
