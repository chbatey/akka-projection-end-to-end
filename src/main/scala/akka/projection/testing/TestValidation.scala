package akka.projection.testing

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.projection.testing.LoadGeneration.{Pass, Result}
import javax.sql.DataSource

import scala.concurrent.duration.FiniteDuration

object TestValidation {
  // FIXME blocking, dispatcher
  // FIXME timeout
  def apply(replyTo: ActorRef[Result], testName: String, expectedNrEvents: Long, timeout: FiniteDuration, source: DataSource): Behavior[String] = {
    import scala.concurrent.duration._
    Behaviors.setup { ctx =>
      def validate(): Boolean = {
        val connection = source.getConnection
        try {
          val resultSet = connection.createStatement().executeQuery(s"select count(*) from events where name = '$testName'")
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

      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate("test", 2.seconds)
        timers.startSingleTimer("timeout", timeout)
        Behaviors.receiveMessage {
          case "test" =>
            if (validate()) {
              ctx.log.info("Validated. Stopping")
              replyTo ! Pass()
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