/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.server

import java.net.{ InetAddress, InetSocketAddress }

import akka.http.ServerSettings

import scala.util.Random
import scala.annotation.tailrec
import scala.concurrent.duration._
import org.scalatest.Inside
import akka.util.ByteString
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import headers._
import HttpEntity._
import MediaTypes._
import HttpMethods._

class HttpServerSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF") with Inside { spec ⇒
  implicit val materializer = ActorMaterializer()

  "The server implementation" should {

    "deliver an empty request as soon as all headers are received" in new TestSetup {
      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""")

      expectRequest shouldEqual HttpRequest(uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "deliver a request as soon as all headers are received" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNoMsg(50.millis)

          send("abcdef")
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijk")
          dataProbe.expectNext(ByteString("ghijk"))
          dataProbe.expectNoMsg(50.millis)
      }
    }

    "deliver an error response as soon as a parsing error occurred" in new TestSetup {
      send("""GET / HTTP/1.2
             |Host: example.com
             |
             |""")

      expectResponseWithWipedDate(
        """HTTP/1.1 505 HTTP Version Not Supported
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 74
          |
          |The server does not support the HTTP protocol version used in the request.""")
    }

    "report an invalid Chunked stream" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("3ghi\r\n") // missing "\r\n" after the number of bytes
          val error = dataProbe.expectError()
          error.getMessage shouldEqual "Illegal character 'g' in chunk start"
          requests.expectComplete()

          responses.expectRequest()
          responses.sendError(error.asInstanceOf[Exception])

          expectResponseWithWipedDate(
            """HTTP/1.1 400 Bad Request
              |Server: akka-http/test
              |Date: XXXX
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 36
              |
              |Illegal character 'g' in chunk start""")
      }
    }

    "deliver the request entity as it comes in strictly for an immediately completed Strict entity" in new TestSetup {
      send("""POST /strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdefghijkl""")

      expectRequest shouldEqual
        HttpRequest(
          method = POST,
          uri = "http://example.com/strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("abcdefghijkl")))
    }

    "deliver the request entity as it comes in for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijk")
          dataProbe.expectNext(ByteString("ghijk"))
          dataProbe.expectNoMsg(50.millis)
      }
    }

    "deliver the request entity as it comes in for a chunked entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))

          send("3\r\nghi\r\n")
          dataProbe.expectNext(Chunk(ByteString("ghi")))
          dataProbe.expectNoMsg(50.millis)
      }
    }

    "deliver the second message properly after a Strict entity" in new TestSetup {
      send("""POST /strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdefghijkl""")

      expectRequest shouldEqual
        HttpRequest(
          method = POST,
          uri = "http://example.com/strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("abcdefghijkl")))

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |mnopqrstuvwx""")

      expectRequest shouldEqual
        HttpRequest(
          method = POST,
          uri = "http://example.com/next-strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("mnopqrstuvwx")))
    }

    "deliver the second message properly after a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghij")
          dataProbe.expectNext(ByteString("ghij"))

          send("kl")
          dataProbe.expectNext(ByteString("kl"))
          dataProbe.expectComplete()
      }

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 5
             |
             |abcde""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual ByteString("abcde")
      }
    }

    "deliver the second message properly after a Chunked entity" in new TestSetup {
      send("""POST /chunked HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))

          send("3\r\nghi\r\n")
          dataProbe.expectNext(ByteString("ghi"))
          dataProbe.expectNoMsg(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 5
             |
             |abcde""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual ByteString("abcde")
      }
    }

    "close the request entity stream when the entity is complete for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijkl")
          dataProbe.expectNext(ByteString("ghijkl"))
          dataProbe.expectComplete()
      }
    }

    "close the request entity stream when the entity is complete for a Chunked entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }
    }

    "report a truncated entity stream on the entity data stream and the main stream for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")
      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))
          dataProbe.expectNoMsg(50.millis)
          closeNetworkInput()
          dataProbe.expectError().getMessage shouldEqual "Entity stream truncation"
      }
    }

    "report a truncated entity stream on the entity data stream and the main stream for a Chunked entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")
      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)
          closeNetworkInput()
          dataProbe.expectError().getMessage shouldEqual "Entity stream truncation"
      }
    }

    "translate HEAD request to GET request when transparent-head-requests are enabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(transparentHeadRequests = true)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      expectRequest shouldEqual HttpRequest(GET, uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "keep HEAD request when transparent-head-requests are disabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(transparentHeadRequests = false)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      expectRequest shouldEqual HttpRequest(HEAD, uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Strict)" in new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.Strict(ContentTypes.`text/plain`, ByteString("abcd"))))
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain
               |Content-Length: 4
               |
               |""")
      }
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Default)" in new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      val data = TestPublisher.manualProbe[ByteString]()
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.Default(ContentTypes.`text/plain`, 4, Source(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain
               |Content-Length: 4
               |
               |""")
      }
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with CloseDelimited)" in new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      val data = TestPublisher.manualProbe[ByteString]()
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`text/plain`, Source(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain
               |
               |""")
      }
      // No close should happen here since this was a HEAD request
      netOut.expectNoBytes(50.millis)
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Chunked)" in new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      val data = TestPublisher.manualProbe[ChunkStreamPart]()
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain`, Source(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Transfer-Encoding: chunked
               |Content-Type: text/plain
               |
               |""")
      }
    }

    "respect Connection headers of HEAD requests if transparent-head-requests is enabled" in new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |Connection: close
             |
             |""")
      val data = TestPublisher.manualProbe[ByteString]()
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = CloseDelimited(ContentTypes.`text/plain`, Source(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          netOut.expectBytes(1)
      }
      netOut.expectComplete()
    }

    "produce a `100 Continue` response when requested by a `Default` entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""")
      inside(expectRequest) {
        case HttpRequest(POST, _, _, Default(ContentType(`application/octet-stream`, None), 16, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val dataSub = dataProbe.expectSubscription()
          netOut.expectNoBytes(50.millis)
          dataSub.request(1) // triggers `100 Continue` response
          expectResponseWithWipedDate(
            """HTTP/1.1 100 Continue
              |Server: akka-http/test
              |Date: XXXX
              |
              |""")
          dataProbe.expectNoMsg(50.millis)
          send("0123456789ABCDEF")
          dataProbe.expectNext(ByteString("0123456789ABCDEF"))
          dataProbe.expectComplete()
          responses.sendNext(HttpResponse(entity = "Yeah"))
          expectResponseWithWipedDate(
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""")
      }
    }

    "produce a `100 Continue` response when requested by a `Chunked` entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Transfer-Encoding: chunked
             |
             |""")
      inside(expectRequest) {
        case HttpRequest(POST, _, _, Chunked(ContentType(`application/octet-stream`, None), data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val dataSub = dataProbe.expectSubscription()
          netOut.expectNoBytes(50.millis)
          dataSub.request(2) // triggers `100 Continue` response
          expectResponseWithWipedDate(
            """HTTP/1.1 100 Continue
              |Server: akka-http/test
              |Date: XXXX
              |
              |""")
          dataProbe.expectNoMsg(50.millis)
          send("""10
                 |0123456789ABCDEF
                 |0
                 |
                 |""")
          dataProbe.expectNext(Chunk(ByteString("0123456789ABCDEF")))
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
          responses.sendNext(HttpResponse(entity = "Yeah"))
          expectResponseWithWipedDate(
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""")
      }
    }

    "render a closing response instead of `100 Continue` if request entity is not requested" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""")
      inside(expectRequest) {
        case HttpRequest(POST, _, _, Default(ContentType(`application/octet-stream`, None), 16, data), _) ⇒
          responses.sendNext(HttpResponse(entity = "Yeah"))
          expectResponseWithWipedDate(
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""")
      }
    }

    "render a 500 response on response stream errors from the application" in new TestSetup {
      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual HttpRequest(uri = "http://example.com/", headers = List(Host("example.com")))

      responses.expectRequest()
      responses.sendError(new RuntimeException("CRASH BOOM BANG"))

      expectResponseWithWipedDate(
        """HTTP/1.1 500 Internal Server Error
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Length: 0
          |
          |""")
    }

    "correctly consume and render large requests and responses" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 100000
             |
             |""")

      val HttpRequest(POST, _, _, entity, _) = expectRequest
      responses.sendNext(HttpResponse(entity = entity))
      responses.sendComplete()

      expectResponseWithWipedDate(
        """HTTP/1.1 200 OK
          |Server: akka-http/test
          |Date: XXXX
          |Content-Type: application/octet-stream
          |Content-Length: 100000
          |
          |""")

      val random = new Random()
      @tailrec def rec(bytesLeft: Int): Unit =
        if (bytesLeft > 0) {
          val count = math.min(random.nextInt(1000) + 1, bytesLeft)
          val data = random.alphanumeric.take(count).mkString
          send(data)
          netOut.expectUtf8EncodedString(data)
          rec(bytesLeft - count)
        }
      rec(100000)

      netIn.sendComplete()
      requests.expectComplete()
      netOut.expectComplete()
    }

    "deliver a request with a non-RFC3986 request-target" in new TestSetup {
      send("""GET //foo HTTP/1.1
             |Host: example.com
             |
             |""")

      expectRequest shouldEqual HttpRequest(uri = "http://example.com//foo", headers = List(Host("example.com")))
    }

    "use default-host-header for HTTP/1.0 requests" in new TestSetup {
      send("""GET /abc HTTP/1.0
             |
             |""")

      expectRequest shouldEqual HttpRequest(uri = "http://example.com/abc", protocol = HttpProtocols.`HTTP/1.0`)

      override def settings: ServerSettings = super.settings.copy(defaultHostHeader = Host("example.com"))
    }
    "fail an HTTP/1.0 request with 400 if no default-host-header is set" in new TestSetup {
      send("""GET /abc HTTP/1.0
             |
             |""")

      expectResponseWithWipedDate(
        """|HTTP/1.1 400 Bad Request
           |Server: akka-http/test
           |Date: XXXX
           |Connection: close
           |Content-Type: text/plain; charset=UTF-8
           |Content-Length: 41
           |
           |Request is missing required `Host` header""")
    }

    "support remote-address-header" in new TestSetup {
      lazy val theAddress = InetAddress.getByName("127.5.2.1")

      override def remoteAddress: Option[InetSocketAddress] =
        Some(new InetSocketAddress(theAddress, 8080))

      override def settings: ServerSettings =
        super.settings.copy(remoteAddressHeader = true)

      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      val request = expectRequest
      request.headers should contain(`Remote-Address`(RemoteAddress(theAddress, Some(8080))))
    }
  }
  class TestSetup extends HttpServerTestSetupBase {
    implicit def system = spec.system
    implicit def materializer = spec.materializer
  }
}
