/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.net.InetSocketAddress
import java.net.URLEncoder
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import akka.actor.{ NoSerializationVerificationNeeded, Actor, DeadLetterSuppression }
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.stream.ActorMaterializerSettings
import akka.stream.impl.ActorProcessor
import akka.stream.impl.ActorPublisher
import akka.stream.scaladsl.{ Tcp ⇒ StreamTcp }
import akka.util.ByteString
import org.reactivestreams.Processor
import org.reactivestreams.Subscriber

/**
 * INTERNAL API
 */
private[akka] object StreamTcpManager {
  /**
   * INTERNAL API
   */
  private[akka] final case class Connect(
    processorPromise: Promise[Processor[ByteString, ByteString]],
    localAddressPromise: Promise[InetSocketAddress],
    remoteAddress: InetSocketAddress,
    localAddress: Option[InetSocketAddress],
    halfClose: Boolean,
    options: immutable.Traversable[SocketOption],
    connectTimeout: Duration,
    idleTimeout: Duration)
    extends DeadLetterSuppression with NoSerializationVerificationNeeded

  /**
   * INTERNAL API
   */
  private[akka] final case class Bind(
    localAddressPromise: Promise[InetSocketAddress],
    unbindPromise: Promise[() ⇒ Future[Unit]],
    flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
    endpoint: InetSocketAddress,
    backlog: Int,
    halfClose: Boolean,
    options: immutable.Traversable[SocketOption],
    idleTimeout: Duration)
    extends DeadLetterSuppression with NoSerializationVerificationNeeded

  /**
   * INTERNAL API
   */
  private[akka] final case class ExposedProcessor(processor: Processor[ByteString, ByteString])
    extends DeadLetterSuppression with NoSerializationVerificationNeeded

}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpManager extends Actor {
  import StreamTcpManager._

  var nameCounter = 0
  def encName(prefix: String, endpoint: InetSocketAddress) = {
    nameCounter += 1
    s"$prefix-$nameCounter-${URLEncoder.encode(endpoint.toString, "utf-8")}"
  }

  def receive: Receive = {
    case Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, halfClose, options, connectTimeout, _) ⇒
      val connTimeout = connectTimeout match {
        case x: FiniteDuration ⇒ Some(x)
        case _                 ⇒ None
      }
      val processorActor = context.actorOf(TcpStreamActor.outboundProps(processorPromise, localAddressPromise, halfClose,
        Tcp.Connect(remoteAddress, localAddress, options, connTimeout, pullMode = true),
        materializerSettings = ActorMaterializerSettings(context.system)), name = encName("client", remoteAddress))
      processorActor ! ExposedProcessor(ActorProcessor[ByteString, ByteString](processorActor))

    case Bind(localAddressPromise, unbindPromise, flowSubscriber, endpoint, backlog, halfClose, options, _) ⇒
      val props = TcpListenStreamActor.props(localAddressPromise, unbindPromise, flowSubscriber, halfClose,
        Tcp.Bind(context.system.deadLetters, endpoint, backlog, options, pullMode = true),
        ActorMaterializerSettings(context.system))
        .withDispatcher(context.props.dispatcher)
      val publisherActor = context.actorOf(props, name = encName("server", endpoint))
      // this sends the ExposedPublisher message to the publisher actor automatically
      ActorPublisher[Any](publisherActor)
  }
}
