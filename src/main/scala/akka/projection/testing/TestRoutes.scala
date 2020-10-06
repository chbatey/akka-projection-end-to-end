package akka.projection.testing

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object TestRoutes {

  case class RunTest(name: String, nrActors: Int, messagesPerActor: Int, rate: Int, timeout: Int)

  case class TestResult(pass: Boolean)

  implicit val runTestFormat: RootJsonFormat[RunTest] = jsonFormat5(RunTest)
  implicit val testResultFormat: RootJsonFormat[TestResult] = jsonFormat1(TestResult)
}

class TestRoutes(loadGeneration: ActorRef[LoadGeneration.RunTest])(implicit val system: ActorSystem[_]) {

  import TestRoutes._

  val route: Route = path("test") {
    post {
      entity(as[RunTest]) { runTest =>
        implicit val timeout: Timeout = Timeout(60.seconds)
        import akka.actor.typed.scaladsl.AskPattern._
        val name = if (runTest.name.isBlank) s"test-${System.currentTimeMillis()}" else runTest.name
        val test = loadGeneration.ask(replyTo => LoadGeneration.RunTest(name, runTest.nrActors, runTest.messagesPerActor, replyTo, runTest.rate, runTest.timeout))
        onComplete(test) {
          case Success(_) =>
            complete(TestResult(true))
          case Failure(_) =>
            complete(TestResult(false))
        }
      }
    }
  }

}
