/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.actor.{ ActorRef, Cancellable, Props }
import akka.event.LoggingAdapter
import akka.japi.{ Pair, Util, function }
import akka.stream._
import akka.stream.impl.{ ConstantFun, StreamLayout }
import akka.stream.stage.Stage
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.language.{ higherKinds, implicitConversions }

/** Java API */
object Source {
  private[this] val _empty = new Source[Any, Unit](scaladsl.Source.empty)

  /**
   * Create a `Source` with no elements, i.e. an empty stream that is completed immediately
   * for every connected `Sink`.
   */
  def empty[O](): Source[O, Unit] = _empty.asInstanceOf[Source[O, Unit]]

  /**
   * Create a `Source` which materializes a [[scala.concurrent.Promise]] which controls what element
   * will be emitted by the Source.
   * If the materialized promise is completed with a Some, that value will be produced downstream,
   * followed by completion.
   * If the materialized promise is completed with a None, no value will be produced downstream and completion will
   * be signalled immediately.
   * If the materialized promise is completed with a failure, then the returned source will terminate with that error.
   * If the downstream of this source cancels before the promise has been completed, then the promise will be completed
   * with None.
   */
  def maybe[T]: Source[T, Promise[Option[T]]] =
    new Source(scaladsl.Source.maybe[T])

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def from[O](publisher: Publisher[O]): javadsl.Source[O, Unit] =
    new Source(scaladsl.Source.apply(publisher))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage:
   *
   * {{{
   * List<Integer> data = new ArrayList<Integer>();
   * data.add(1);
   * data.add(2);
   * data.add(3);
   * Source.from(() -> data.iterator());
   * }}}
   *
   * Start a new `Source` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def fromIterator[O](f: function.Creator[java.util.Iterator[O]]): javadsl.Source[O, Unit] =
    new Source(scaladsl.Source(() ⇒ f.create().asScala))

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage:
   * {{{
   * List<Integer> data = new ArrayList<Integer>();
   * data.add(1);
   * data.add(2);
   * data.add(3);
   * Source.fom(data);
   * }}}
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as a `Source`. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   */
  def from[O](iterable: java.lang.Iterable[O]): javadsl.Source[O, Unit] = {
    // this adapter is not immutable if the the underlying java.lang.Iterable is modified
    // but there is not anything we can do to prevent that from happening.
    // ConcurrentModificationException will be thrown in some cases.
    val scalaIterable = new immutable.Iterable[O] {

      import collection.JavaConverters._

      override def iterator: Iterator[O] = iterable.iterator().asScala
    }
    new Source(scaladsl.Source(scalaIterable))
  }

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def from[O](future: Future[O]): javadsl.Source[O, Unit] =
    new Source(scaladsl.Source(future))

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def from[O](initialDelay: FiniteDuration, interval: FiniteDuration, tick: O): javadsl.Source[O, Cancellable] =
    new Source(scaladsl.Source(initialDelay, interval, tick))

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def single[T](element: T): Source[T, Unit] =
    new Source(scaladsl.Source.single(element))

  /**
   * Create a `Source` that will continually emit the given element.
   */
  def repeat[T](element: T): Source[T, Unit] =
    new Source(scaladsl.Source.repeat(element))

  /**
   * Create a `Source` that immediately ends the stream with the `cause` failure to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, Unit] =
    new Source(scaladsl.Source.failed(cause))

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def subscriber[T](): Source[T, Subscriber[T]] =
    new Source(scaladsl.Source.subscriber)

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorPublisher]].
   */
  def actorPublisher[T](props: Props): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorPublisher(props))

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] is not supported, and an
   * IllegalArgument("Backpressure overflowStrategy not supported") will be thrown if it is passed as argument.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped
   * if there is no demand from downstream. When `bufferSize` is 0 the `overflowStrategy` does
   * not matter.
   *
   * The stream can be completed successfully by sending [[akka.actor.PoisonPill]] or
   * [[akka.actor.Status.Success]] to the actor reference.
   *
   * The stream can be completed with failure by sending [[akka.actor.Status.Failure]] to the
   * actor reference.
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorRef(bufferSize, overflowStrategy))

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SourceShape[T], M]): Source[T, M] =
    g match {
      case s: Source[T, M]                 ⇒ s
      case s if s eq scaladsl.Source.empty ⇒ empty().asInstanceOf[Source[T, M]]
      case other                           ⇒ new Source(scaladsl.Source.fromGraph(other))
    }

  /**
   * Combines several sources with fan-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: java.util.List[Source[T, _]], strategy: function.Function[java.lang.Integer, Graph[UniformFanInShape[T, U], Unit]]): Source[U, Unit] = {
    import scala.collection.JavaConverters._
    val seq = if (rest != null) rest.asScala.map(_.asScala) else Seq()
    new Source(scaladsl.Source.combine(first.asScala, second.asScala, seq: _*)(num ⇒ strategy.apply(num)))
  }

  /**
   * Creates a `Source` that is materialized as an [[akka.stream.SourceQueue]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[akka.stream.SourceQueue.offer]] returns ``Future[Boolean]`` which completes with true
   * if element was added to buffer or sent downstream. It completes
   * with false if element was dropped.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] will not complete `offer():Future` until buffer is full.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped
   * if there is no demand from downstream. When `bufferSize` is 0 the `overflowStrategy` does
   * not matter.
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   * @param timeout Timeout for ``SourceQueue.offer(T):Future[Boolean]``
   */
  def queue[T](bufferSize: Int, overflowStrategy: OverflowStrategy, timeout: FiniteDuration): Source[T, SourceQueue[T]] =
    new Source(scaladsl.Source.queue(bufferSize, overflowStrategy, timeout))
}

/**
 * Java API
 *
 * A `Source` is a set of stream processing steps that has one open output and an attached input.
 * Can be used as a `Publisher`
 */
class Source[+Out, +Mat](delegate: scaladsl.Source[Out, Mat]) extends Graph[SourceShape[Out], Mat] {

  import scala.collection.JavaConverters._

  override def shape: SourceShape[Out] = delegate.shape

  private[stream] def module: StreamLayout.Module = delegate.module

  /** Converts this Java DSL element to its Scala DSL counterpart. */
  def asScala: scaladsl.Source[Out, Mat] = delegate

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): Source[Out, Mat2] =
    new Source(delegate.mapMaterializedValue(f.apply _))

  /**
   * Transform this [[Source]] by appending the given processing stages.
   */
  def via[T, M](flow: Graph[FlowShape[Out, T], M]): javadsl.Source[T, Mat] =
    new Source(delegate.via(flow))

  /**
   * Transform this [[Source]] by appending the given processing stages.
   */
  def viaMat[T, M, M2](flow: Graph[FlowShape[Out, T], M], combine: function.Function2[Mat, M, M2]): javadsl.Source[T, M2] =
    new Source(delegate.viaMat(flow)(combinerToScala(combine)))

  /**
   * Connect this [[Source]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to[M](sink: Graph[SinkShape[Out], M]): javadsl.RunnableGraph[Mat] =
    RunnableGraph.fromGraph(delegate.to(sink))

  /**
   * Connect this [[Source]] to a [[Sink]], concatenating the processing steps of both.
   */
  def toMat[M, M2](sink: Graph[SinkShape[Out], M], combine: function.Function2[Mat, M, M2]): javadsl.RunnableGraph[M2] =
    RunnableGraph.fromGraph(delegate.toMat(sink)(combinerToScala(combine)))

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a `Sink.publisher`.
   */
  def runWith[M](sink: Graph[SinkShape[Out], M], materializer: Materializer): M =
    delegate.runWith(sink)(materializer)

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   */
  def runFold[U](zero: U, f: function.Function2[U, Out, U], materializer: Materializer): Future[U] =
    runWith(Sink.fold(zero, f), materializer)

  /**
   * Concatenate this [[Source]] with the given one, meaning that once current
   * is exhausted and all result elements have been generated,
   * the given source elements will be produced.
   *
   * Note that given [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Source]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * '''Emits when''' element is available from current source or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def concat[T >: Out, M](that: Graph[SourceShape[T], M]): javadsl.Source[T, Mat] =
    new Source(delegate.concat(that))

  /**
   * Concatenate this [[Source]] with the given one, meaning that once current
   * is exhausted and all result elements have been generated,
   * the given source elements will be produced.
   *
   * Note that given [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Source]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * @see [[#concat]].
   */
  def concatMat[T >: Out, M, M2](that: Graph[SourceShape[T], M],
                                 matF: function.Function2[Mat, M, M2]): javadsl.Source[T, M2] =
    new Source(delegate.concatMat(that)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * '''Emits when''' element is available and demand exists both from the Sink and the downstream.
   *
   * '''Backpressures when''' downstream or Sink backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def alsoTo(that: Graph[SinkShape[Out], _]): javadsl.Source[Out, Mat] =
    new Source(delegate.alsoTo(that))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * @see [[#alsoTo]]
   */
  def alsoToMat[M2, M3](that: Graph[SinkShape[Out], M2],
                        matF: function.Function2[Mat, M2, M3]): javadsl.Source[Out, M3] =
    new Source(delegate.alsoToMat(that)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def merge[T >: Out](that: Graph[SourceShape[T], _]): javadsl.Source[T, Mat] =
    new Source(delegate.merge(that))

  /**
   * Merge the given [[Source]] to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * @see [[#merge]].
   */
  def mergeMat[T >: Out, M, M2](that: Graph[SourceShape[T], M],
                                matF: function.Function2[Mat, M, M2]): javadsl.Source[T, M2] =
    new Source(delegate.mergeMat(that)(combinerToScala(matF)))

  /**
   * Combine the elements of current [[Source]] and the given one into a stream of tuples.
   *
   * '''Emits when''' all of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zip[T](that: Graph[SourceShape[T], _]): javadsl.Source[Out @uncheckedVariance Pair T, Mat] =
    zipMat(that, Keep.left)

  /**
   * Combine the elements of current [[Source]] and the given one into a stream of tuples.
   *
   * @see [[#zip]].
   */
  def zipMat[T, M, M2](that: Graph[SourceShape[T], M],
                       matF: function.Function2[Mat, M, M2]): javadsl.Source[Out @uncheckedVariance Pair T, M2] =
    this.viaMat(Flow.create[Out].zipMat(that, Keep.right[Unit, M]), matF)

  /**
   * Put together the elements of current [[Source]] and the given one
   * into a stream of combined elements using a combiner function.
   *
   * '''Emits when''' all of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWith[Out2, Out3](that: Graph[SourceShape[Out2], _],
                          combine: function.Function2[Out, Out2, Out3]): javadsl.Source[Out3, Mat] =
    new Source(delegate.zipWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Source]] and the given one
   * into a stream of combined elements using a combiner function.
   *
   * @see [[#zipWith]].
   */
  def zipWithMat[Out2, Out3, M, M2](that: Graph[SourceShape[Out2], M],
                                    combine: function.Function2[Out, Out2, Out3],
                                    matF: function.Function2[Mat, M, M2]): javadsl.Source[Out3, M2] =
    new Source(delegate.zipWithMat[Out2, Out3, M, M2](that)(combinerToScala(combine))(combinerToScala(matF)))

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure is signaled in
   * the stream.
   */
  def runForeach(f: function.Procedure[Out], materializer: Materializer): Future[Unit] =
    runWith(Sink.foreach(f), materializer)

  // COMMON OPS //

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: function.Function[Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.map(f.apply))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   */
  def recover[T >: Out](pf: PartialFunction[Throwable, T]): javadsl.Source[T, Mat] =
    new Source(delegate.recover(pf))

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   *
   * The returned list MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   */
  def mapConcat[T](f: function.Function[Out, java.util.List[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: function.Function[Out, Future[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.mapAsync(parallelism)(f.apply))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and each processed element will be emitted downstream
   * as soon as it is ready, i.e. it is possible that the elements are not emitted downstream
   * in the same order as received from upstream.
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: function.Function[Out, Future[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.mapAsyncUnordered(parallelism)(f.apply))

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: function.Predicate[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.filter(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.collect(pf))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * @param n must be positive, otherwise [[IllegalArgumentException]] is thrown.
   */
  def grouped(n: Int): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.grouped(n).map(_.asJava))

  /**
   * Apply a sliding window over the stream and return the windows as groups of elements, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * @param n must be positive, otherwise [[IllegalArgumentException]] is thrown.
   * @param step must be positive, otherwise [[IllegalArgumentException]] is thrown.
   */
  def sliding(n: Int, step: Int): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.sliding(n, step).map(_.asJava))

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * yielding the next current value.
   */
  def scan[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.scan(zero)(f.apply))

  /**
   * Similar to `scan` but only emits the current value once, when completing.
   * Its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * yielding the next current value.
   */
  def fold[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.fold(zero)(f.apply))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * In case you want to only prepend or only append an element (yet still use the `intercept` feature
   * to inject a separator between elements, you may want to use the following pattern instead of the 3-argument
   * version of intersperse (See [[Source.concat]] for semantics details):
   *
   * {{{
   * Source.single(">> ").concat(list.intersperse(","))
   * list.intersperse(",").concat(Source.single("END"))
   * }}}
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse[T >: Out](start: T, inject: T, end: T): javadsl.Source[T, Mat] =
    new Source(delegate.intersperse(start, inject, end))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse[T >: Out](inject: T): javadsl.Source[T, Mat] =
    new Source(delegate.intersperse(inject))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * @param n must be positive, and `d` must be greater than 0 seconds, otherwise [[IllegalArgumentException]] is thrown.
   */
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.groupedWithin(n, d).map(_.asJava)) // TODO optimize to one step

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Long): javadsl.Source[Out, Mat] =
    new Source(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.dropWithin(d))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate returned false for the first time.
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * @param p predicate is evaluated for each new element until first time returns false
   */
  def takeWhile(p: function.Predicate[Out]): javadsl.Source[Out, Mat] = new Source(delegate.takeWhile(p.test))

  /**
   * Discard elements at the beginning of the stream while predicate is true.
   * No elements will be dropped after predicate first time returned false.
   *
   * @param p predicate is evaluated for each new element until first time returns false
   */
  def dropWhile(p: function.Predicate[Out]): javadsl.Source[Out, Mat] = new Source(delegate.dropWhile(p.test))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * @param n if `n` is zero or negative the stream will be completed without producing any elements.
   */
  def take(n: Long): javadsl.Source[Out, Mat] =
    new Source(delegate.take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.takeWithin(d))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate[S](seed: function.Function[Out, S], aggregate: function.Function2[S, Out, S]): javadsl.Source[S, Mat] =
    new Source(delegate.conflate(seed.apply)(aggregate.apply))

  /**
   * Allows a faster downstream to progress independently of a slower publisher by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: function.Function[Out, S], extrapolate: function.Function[S, akka.japi.Pair[U, S]]): javadsl.Source[U, Mat] =
    new Source(delegate.expand(seed(_))(s ⇒ {
      val p = extrapolate(s)
      (p.first, p.second)
    }))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Source[Out, Mat] =
    new Source(delegate.buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[U](mkStage: function.Creator[Stage[Out, U]]): javadsl.Source[U, Mat] =
    new Source(delegate.transform(() ⇒ mkStage.create()))

  /**
   * Takes up to `n` elements from the stream (less than `n` only if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements has been seen, and therefore the substream
   *    has not yet been emitted
   *  - the tail substream signals the error after the prefix and tail has been emitted by the main stream
   *    (at that point the main stream has already completed)
   */
  def prefixAndTail(n: Int): javadsl.Source[akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance, Unit]], Mat] =
    new Source(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ akka.japi.Pair(taken.asJava, tail.asJava) })

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * it is emitted to the downstream subscriber together with a fresh
   * flow that will eventually produce all the elements of the substream
   * for that key. Not consuming the elements from the created streams will
   * stop this processor from processing more elements, therefore you must take
   * care to unblock (or cancel) all of the produced streams even if you want
   * to consume only one of them.
   */
  def groupBy[K](f: function.Function[Out, K]): javadsl.Source[akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance, Unit]], Mat] =
    new Source(delegate.groupBy(f.apply).map { case (k, p) ⇒ akka.japi.Pair(k, p.asJava) }) // TODO optimize to one step

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it. This means
   * that for the following series of predicate values, three substreams will
   * be produced with lengths 1, 2, and 3:
   *
   * {{{
   * false,             // element goes into first substream
   * true, false,       // elements go into second substream
   * true, false, false // elements go into third substream
   * }}}
   *
   * In case the *first* element of the stream matches the predicate, the first
   * substream emitted by splitWhen will start from that element. For example:
   *
   * {{{
   * true, false, false // first substream starts from the split-by element
   * true, false        // subsequent substreams operate the same way
   * }}}
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element for which the provided predicate is true, opening and emitting a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel
   *
   * See also [[Source.splitAfter]].
   */
  def splitWhen(p: function.Predicate[Out]): javadsl.Source[javadsl.Source[Out, Unit], Mat] =
    new Source(delegate.splitWhen(p.test).map(_.asJava))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true. This means that for the following series of predicate values,
   * three substreams will be produced with lengths 2, 2, and 3:
   *
   * {{{
   * false, true,        // elements go into first substream
   * false, true,        // elements go into second substream
   * false, false, true  // elements go into third substream
   * }}}
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element passes through. When the provided predicate is true it emitts the element
   * and opens a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel
   *
   * See also [[Source.splitWhen]].
   */
  def splitAfter[U >: Out](p: function.Predicate[Out]): javadsl.Source[Source[Out, Unit], Mat] =
    new Source(delegate.splitAfter(p.test).map(_.asJava))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): javadsl.Source[U, Mat] =
    new Source(delegate.flatten(strategy))

  override def withAttributes(attr: Attributes): javadsl.Source[Out, Mat] =
    new Source(delegate.withAttributes(attr))

  override def named(name: String): javadsl.Source[Out, Mat] =
    new Source(delegate.named(name))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any], log: LoggingAdapter): javadsl.Source[Out, Mat] =
    new Source(delegate.log(name, e ⇒ extract.apply(e))(log))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any]): javadsl.Source[Out, Mat] =
    this.log(name, extract, null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, log: LoggingAdapter): javadsl.Source[Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String): javadsl.Source[Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

}
