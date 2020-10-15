package akka.projection.testing

import akka.actor.Scheduler
import akka.pattern.retry
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, DispatcherSelector, Terminated}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.pattern.StatusReply
import akka.projection.testing.LoadGeneration.{RunTest, TestSummary}
import akka.projection.testing.LoadTest.Start
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{Done, NotUsed}
import javax.sql.DataSource

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.util.{Failure, Success}

object LoadGeneration {

  case class RunTest(name: String, actors: Int, eventsPerActor: Int, reply: ActorRef[TestSummary], numberOfConcurrentActors: Int, timeout: Long)
  case class TestSummary(name: String, expectedMessages: Long)

  def apply(settings: EventProcessorSettings, shardRegion: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]], source: DataSource): Behavior[RunTest] = Behaviors.setup { ctx =>
    Behaviors.receiveMessage[RunTest] {
      rt: RunTest =>
        ctx.spawn(LoadTest(settings, rt.name, shardRegion, source), s"test-${rt.name}") ! Start(rt)
        Behaviors.same
    }
  }

}

object LoadTest {

  sealed trait Command
  case class Start(test: RunTest) extends Command
  private case class StartValidation() extends Command
  private case class LoadGenerationFailed(t: Throwable) extends Command

  def apply(settings: EventProcessorSettings, testName: String, shardRegion: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]], source: DataSource): Behavior[Command] = Behaviors.setup { ctx =>
    import akka.actor.typed.scaladsl.AskPattern._
    implicit val timeout: Timeout = 5.seconds
    implicit val system: ActorSystem[Nothing] = ctx.system
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val scheduler: Scheduler = system.toClassic.scheduler

    Behaviors.receiveMessagePartial[Command] {
      case Start(RunTest(name, actors, eventsPerActor, replyTo, numberOfConcurrentActors, t)) =>
        ctx.log.info("Starting load generation")
        val expected: Int = actors * eventsPerActor
        val total = expected * settings.nrProjections
        replyTo ! TestSummary(name, expected * settings.nrProjections)
        val startTime = System.nanoTime()

        // The operation is idempotent so retries will not affect the final event count
        val testRun: Source[StatusReply[Done], NotUsed] = Source(1 to actors).mapAsyncUnordered(numberOfConcurrentActors)(id => retry(() => shardRegion.ask[StatusReply[Done]] { replyTo =>
          ShardingEnvelope(s"${testName}-$id", ConfigurablePersistentActor.PersistAndAck(eventsPerActor, s"actor-$id-message", replyTo, testName))
        }, 10, 1.second, 10.seconds, 0.1))

        ctx.pipeToSelf(testRun.run()) {
          case Success(_) => StartValidation()
          case Failure(t) => LoadGenerationFailed(t)
        }

        Behaviors.receiveMessagePartial[Command] {
          case StartValidation() =>
            ctx.log.info("Starting validation")
            val validation = ctx.spawn(TestValidation(testName, settings.nrProjections, expected, t.seconds, source: DataSource), s"TestValidation=$testName", DispatcherSelector.blocking())
            ctx.watch(validation)
            Behaviors.same
          case LoadGenerationFailed(t) =>
            ctx.log.error("Load generation failed", t)
            Behaviors.stopped
        }.receiveSignal {
          case (ctx, Terminated(_)) =>
            val finishTime = System.nanoTime()
            val totalTime = finishTime - startTime
            ctx.log.info("Validation finished for test {}, terminating. Total time for {} events. {}. Rough rate: {}", testName, total, akka.util.PrettyDuration.format(totalTime.nanos), total/ totalTime.nanos.toSeconds)
            Behaviors.stopped
        }
    }
  }

}
