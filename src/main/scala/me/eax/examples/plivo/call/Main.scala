package me.eax.examples.plivo.call

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.pattern.after
import org.json4s._
import org.json4s.jackson.JsonMethods._
import java.net.URLDecoder

import scala.util._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App {
  val authId = "Ololo"
  val authToken = "Trololo"
  val src = "+13364960988"
  val dst = "+79161234567"
  val listenPort = 30000 + Random.nextInt(10000)
  val msg = (for(_ <- 1 to 3) yield "Всё сломалось. Проверьте Nagios. ").mkString

  val shortTimeout = 10.seconds
  val callDuration = 15.seconds
  val longTimeout = 30.seconds

  val callIdPromise = Promise[String]()
  val fCallId = callIdPromise.future
  val callEndPromise = Promise[Unit]()
  val fCallEnd = callEndPromise.future

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  try {
    doit()
    System.exit(0)
  } catch {
    case e: Throwable =>
      println(s"Error: ${e.getMessage}")
      e.printStackTrace()
      System.exit(1)
  }

  def externalAddr(): Future[String] = {
    for {
      resp <- Http().singleRequest(HttpRequest(HttpMethods.GET, s"http://checkip.amazonaws.com/"))
      body <- resp.entity.toStrict(shortTimeout).map(_.data.decodeString("UTF-8"))
    } yield body.trim
  }

  def decodeForm(body: String): Map[String, String] = {
    body.split('&').map { pair =>
      pair.split('=') match {
        case Array(k) => (k, "") // ...&something=&... case
        case Array(k, v) => (k, URLDecoder.decode(v, "UTF-8"))
      }
    }.toMap
  }

  def doit(): Unit = {
    val serverSource = Http().bind(interface = "0.0.0.0", port = listenPort)

    val reqHandler: HttpRequest => Future[HttpResponse] = {
      case req@HttpRequest(HttpMethods.POST, Uri.Path("/"), _, ent, _)
        if ent.contentType().mediaType == MediaTypes.`application/x-www-form-urlencoded` =>

        for {
          body <- ent.toStrict(shortTimeout).map(_.data.decodeString("UTF-8"))
          _ = {
            println(
              s"""
                 |--- Request received ---
                 |body = $body
                 |req = $req
                 |------------------------
                 |""".stripMargin
            )

            val form = decodeForm(body)
            val callStatus = form("CallStatus")

            if(!callIdPromise.isCompleted && callStatus == "in-progress") { // keep this check!
              callIdPromise.success(form("CallUUID"))
            }
          }
          _ <- after(callDuration, system.scheduler) { Future.successful({}) }
        } yield {
          callEndPromise.success({})
          HttpResponse(StatusCodes.OK, entity = "")
        }

      case _: HttpRequest =>
        Future.successful {
          HttpResponse(
            StatusCodes.NotFound,
            entity = HttpEntity(MediaTypes.`text/html`, "<html><body>Not found!</body></html>")
          )
        }
    }

    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection =>
        connection.handleWithAsyncHandler(reqHandler)
      }).run()

    val fCallResp = externalAddr() flatMap { addr =>
      Http().singleRequest(
        HttpRequest(
          method = HttpMethods.POST,
          uri = s"https://api.plivo.com/v1/Account/$authId/Call/",
          headers = List(
            headers.Authorization(headers.BasicHttpCredentials(authId, authToken))
          ),
          entity = HttpEntity(
            ContentTypes.`application/json`,
            compact(JObject(List(
              "from" -> JString(src),
              "to" -> JString(dst),
              "answer_url" -> JString(s"http://$addr:$listenPort/"),
              "ring_timeout" -> JInt(longTimeout.toSeconds)
            )))
          )
        )
      )
    }

    println(s"Sending call request...")
    val callResp = Await.result(fCallResp, shortTimeout)

    if(!callResp.status.isSuccess()) {
      println(s"Failed to make a call, callResp = $callResp")
      System.exit(1)
    }

    println(s"Call request sent, awaiting callId...")
    val callId = Await.result(fCallId, longTimeout)

    println(s"callId = $callId, sending speak request...")

    val fSpeakResp = Http().singleRequest(
      HttpRequest(
        method = HttpMethods.POST,
        uri = s"https://api.plivo.com/v1/Account/$authId/Call/$callId/Speak/",
        headers = List(
          headers.Authorization(headers.BasicHttpCredentials(authId, authToken))
        ),
        entity = HttpEntity(
          ContentTypes.`application/json`,
          compact(JObject(List(
            "text" -> JString(msg),
            "voice" -> JString("WOMAN"),
            "language" -> JString("ru-RU")
          )))
        )
      )
    )

    val speakResp = Await.result(fSpeakResp, shortTimeout)

    if(!speakResp.status.isSuccess()) {
      println(s"Failed to speak, speakResp = $speakResp")
      System.exit(1)
    }

    println("Waiting call end...")
    Await.result(fCallEnd, longTimeout)
    println("All done!")
  }
}
