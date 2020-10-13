package akka.projection.testing

import akka.Done
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.persistence.cassandra.cleanup.Cleanup
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.util.Timeout
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object TestRoutes {

  case class RunTest(name: String, nrActors: Int, messagesPerActor: Int, rate: Int, timeout: Int)

  case class Response(testName: String, expectedMessages: Long)

  implicit val runTestFormat: RootJsonFormat[RunTest] = jsonFormat5(RunTest)
  implicit val testResultFormat: RootJsonFormat[Response] = jsonFormat2(Response)
}

class TestRoutes(loadGeneration: ActorRef[LoadGeneration.RunTest])(implicit val system: ActorSystem[_]) {

  private val log = LoggerFactory.getLogger(classOf[TestRoutes])

  import TestRoutes._

  private val cleanup = new Cleanup(system)
  private val queries = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  import system.executionContext

  val route: Route = path("test") {
    post {
      entity(as[RunTest]) { runTest =>
        implicit val timeout: Timeout = Timeout(60.seconds)
        import akka.actor.typed.scaladsl.AskPattern._
        val name = if (runTest.name.isBlank) s"test-${System.currentTimeMillis()}" else runTest.name
        // this is too expensive as it starts events by tag queries for every persistence id
        //        val preTestCleanup: Future[Done] = queries.currentPersistenceIds()
        //          .log("cleanup")
        //          .mapAsync(10)(pid => cleanup.deleteAll(pid, neverUsePersistenceIdAgain = true))
        //          .run()

        // not safe for a real app but we know we don't re-use any persistence ids


        val session = CassandraSessionRegistry(system).sessionFor("akka.persistence.cassandra")

        val truncates: Seq[Future[Done]] = List(session.executeWrite(s"truncate akka_testing.tag_views"),
          session.executeWrite(s"truncate akka_testing.tag_write_progress"),
          session.executeWrite(s"truncate akka_testing.tag_scanning"),
          session.executeWrite(s"truncate akka_testing.messages"),
          session.executeWrite(s"truncate akka_testing.all_persistence_ids"))

        val test = for {
          _ <- Future.sequence(truncates)
          result <- {
            log.info("Finished cleanup. Starting load generation")
            loadGeneration.ask(replyTo => LoadGeneration.RunTest(name, runTest.nrActors, runTest.messagesPerActor, replyTo, runTest.rate, runTest.timeout))
          }
        } yield result

        onComplete(test) {
          case Success(summary) =>
            complete(Response(summary.name, summary.expectedMessages))
          case Failure(t) =>
            complete(StatusCodes.InternalServerError, s"test failed to start: " + t.getMessage)
        }
      }
    }
  }

}
