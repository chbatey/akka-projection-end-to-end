package akka.projection.testing

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Terminated}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.pattern.StatusReply
import akka.projection.testing.LoadGeneration.{Failed, RunTest}
import akka.projection.testing.LoadTest.Start
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{Done, NotUsed}
import javax.sql.DataSource

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.util.{Failure, Success}

object LoadGeneration {

  case class RunTest(name: String, actors: Int, eventsPerActor: Int, reply: ActorRef[Result], messagesPerSecond: Int, timeout: Long)

  sealed trait Result

  case class Pass() extends Result

  case class Failed(t: Option[Throwable], expected: Int, got: Int) extends Result

  def apply(shardRegion: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]], source: DataSource): Behavior[RunTest] = Behaviors.setup { ctx =>
    Behaviors.receiveMessage[RunTest] {
      rt: RunTest =>
        ctx.spawn(LoadTest(rt.name, shardRegion, source), s"test-${rt.name}") ! Start(rt)
        Behaviors.same
    }
  }

}

object LoadTest {

  sealed trait Command

  case class Start(test: RunTest) extends Command

  private case class StartValidation() extends Command

  private case class LoadGenerationFailed(t: Throwable) extends Command

  def apply(testName: String, shardRegion: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]], source: DataSource): Behavior[Command] = Behaviors.setup { ctx =>
    import akka.actor.typed.scaladsl.AskPattern._
    implicit val timeout: Timeout = 30.seconds
    implicit val system: ActorSystem[Nothing] = ctx.system
    implicit val ec: ExecutionContext = system.executionContext
    Behaviors.receiveMessage[Command] {
      case Start(RunTest(_, actors, eventsPerActor, replyTo, rate, t)) =>
        ctx.log.info("Starting load generation")
        val expected: Int = actors * eventsPerActor
        val startTime = System.nanoTime()
        // FIXME use a smaller timeout here and retry
        val testRun: Source[StatusReply[Done], NotUsed] = Source(1 to actors).mapAsync(1)(id => shardRegion.ask[StatusReply[Done]] { replyTo =>
          ShardingEnvelope(s"${testName}-$id", ConfigurablePersistentActor.PersistAndAck(eventsPerActor, s"actor-$id-message", replyTo, testName))
        }).throttle(rate, 1.second) // now that the  actors are responsible for doing the eventsPerActor this isn't really a rate

        ctx.pipeToSelf(testRun.run()) {
          case Success(_) => StartValidation()
          case Failure(t) => LoadGenerationFailed(t)
        }
        Behaviors.receiveMessagePartial[Command] {
          case StartValidation() =>
            ctx.log.info("Starting validation")
            val validation = ctx.spawn(TestValidation(replyTo, testName, expected, t.seconds, source: DataSource), s"TestValidation=$testName")
            ctx.watch(validation)
            Behaviors.same
          case LoadGenerationFailed(t) =>
            ctx.log.error("Load generation failed", t)
            replyTo ! Failed(Some(t), -1, -1)
            Behaviors.stopped
        }.receiveSignal {
          case (ctx, Terminated(_)) =>
            val finishTime = System.nanoTime()
            val totalTime = finishTime - startTime
            ctx.log.info("Validation finished, terminating. Total time for {} events. {}. Rough rate: {}", expected, akka.util.PrettyDuration.format(totalTime.nanos), expected / totalTime.nanos.toSeconds)
            Behaviors.stopped
        }
    }
  }

}
