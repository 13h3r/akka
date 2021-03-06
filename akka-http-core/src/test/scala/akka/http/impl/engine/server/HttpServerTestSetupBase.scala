/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress

import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.stream.io.{ SendBytes, SslTlsOutbound, SessionBytes }

import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.util.ByteString

import akka.stream.{ ClosedShape, Materializer }
import akka.stream.scaladsl._
import akka.stream.testkit.{ TestPublisher, TestSubscriber }

import akka.http.impl.util._

import akka.http.ServerSettings
import akka.http.scaladsl.model.headers.{ ProductVersion, Server }
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }

abstract class HttpServerTestSetupBase {
  implicit def system: ActorSystem
  implicit def materializer: Materializer

  val requests = TestSubscriber.probe[HttpRequest]
  val responses = TestPublisher.probe[HttpResponse]()

  def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))
  def remoteAddress: Option[InetSocketAddress] = None

  val (netIn, netOut) = {
    val netIn = TestPublisher.probe[ByteString]()
    val netOut = ByteStringSinkProbe()

    RunnableGraph.fromGraph(FlowGraph.create(HttpServerBluePrint(settings, remoteAddress = remoteAddress, log = NoLogging)) { implicit b ⇒
      server ⇒
        import FlowGraph.Implicits._
        Source(netIn) ~> Flow[ByteString].map(SessionBytes(null, _)) ~> server.in2
        server.out1 ~> Flow[SslTlsOutbound].collect { case SendBytes(x) ⇒ x } ~> netOut.sink
        server.out2 ~> Sink(requests)
        Source(responses) ~> server.in1
        ClosedShape
    }).run()

    netIn -> netOut
  }

  def expectResponseWithWipedDate(expected: String): Unit = {
    val trimmed = expected.stripMarginWithNewline("\r\n")
    // XXXX = 4 bytes, ISO Date Time String = 29 bytes => need to request 15 bytes more than expected string
    val expectedSize = ByteString(trimmed, "utf8").length + 25
    val received = wipeDate(netOut.expectBytes(expectedSize).utf8String)
    assert(received == trimmed, s"Expected request '$trimmed' but got '$received'")
  }

  def wipeDate(string: String) =
    string.fastSplit('\n').map {
      case s if s.startsWith("Date:") ⇒ "Date: XXXX\r"
      case s                          ⇒ s
    }.mkString("\n")

  def expectRequest: HttpRequest = requests.requestNext()
  def expectNoRequest(max: FiniteDuration): Unit = requests.expectNoMsg(max)
  def expectNetworkClose(): Unit = netOut.expectComplete()

  def send(data: ByteString): Unit = netIn.sendNext(data)
  def send(string: String): Unit = send(ByteString(string.stripMarginWithNewline("\r\n"), "UTF8"))

  def closeNetworkInput(): Unit = netIn.sendComplete()
}