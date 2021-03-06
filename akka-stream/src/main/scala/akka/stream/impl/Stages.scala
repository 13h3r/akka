/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.event.LoggingAdapter
import akka.stream.impl.SplitDecision.SplitDecision
import akka.stream.impl.StreamLayout._
import akka.stream.{ OverflowStrategy, Attributes }
import akka.stream.Attributes._
import akka.stream.stage.Stage
import org.reactivestreams.Processor
import scala.collection.immutable
import scala.concurrent.Future

/**
 * INTERNAL API
 */
private[stream] object Stages {

  object DefaultAttributes {
    val timerTransform = name("timerTransform")
    val stageFactory = name("stageFactory")
    val fused = name("fused")
    val map = name("map")
    val filter = name("filter")
    val collect = name("collect")
    val recover = name("recover")
    val mapAsync = name("mapAsync")
    val mapAsyncUnordered = name("mapAsyncUnordered")
    val grouped = name("grouped")
    val sliding = name("sliding")
    val take = name("take")
    val drop = name("drop")
    val takeWhile = name("takeWhile")
    val dropWhile = name("dropWhile")
    val scan = name("scan")
    val fold = name("fold")
    val intersperse = name("intersperse")
    val buffer = name("buffer")
    val conflate = name("conflate")
    val expand = name("expand")
    val mapConcat = name("mapConcat")
    val groupBy = name("groupBy")
    val prefixAndTail = name("prefixAndTail")
    val split = name("split")
    val concatAll = name("concatAll")
    val processor = name("processor")
    val processorWithKey = name("processorWithKey")
    val identityOp = name("identityOp")

    val merge = name("merge")
    val mergePreferred = name("mergePreferred")
    val broadcast = name("broadcast")
    val balance = name("balance")
    val zip = name("zip")
    val unzip = name("unzip")
    val concat = name("concat")
    val flexiMerge = name("flexiMerge")
    val flexiRoute = name("flexiRoute")
    val identityJunction = name("identityJunction")
    val repeat = name("repeat")

    val publisherSource = name("publisherSource")
    val iterableSource = name("iterableSource")
    val futureSource = name("futureSource")
    val tickSource = name("tickSource")
    val singleSource = name("singleSource")
    val emptySource = name("emptySource")
    val maybeSource = name("MaybeSource")
    val failedSource = name("failedSource")
    val concatSource = name("concatSource")
    val concatMatSource = name("concatMatSource")
    val subscriberSource = name("subscriberSource")
    val actorPublisherSource = name("actorPublisherSource")
    val actorRefSource = name("actorRefSource")
    val synchronousFileSource = name("synchronousFileSource")
    val inputStreamSource = name("inputStreamSource")
    val acknowledgeSource = name("acknowledgeSource")

    val subscriberSink = name("subscriberSink")
    val cancelledSink = name("cancelledSink")
    val headSink = name("headSink") and inputBuffer(initial = 1, max = 1)
    val publisherSink = name("publisherSink")
    val fanoutPublisherSink = name("fanoutPublisherSink")
    val ignoreSink = name("ignoreSink")
    val actorRefSink = name("actorRefSink")
    val actorSubscriberSink = name("actorSubscriberSink")
    val synchronousFileSink = name("synchronousFileSink")
    val outputStreamSink = name("outputStreamSink")
    val acknowledgeSink = name("acknowledgeSink")
  }

  import DefaultAttributes._

  sealed trait StageModule extends FlowModule[Any, Any, Any] {
    def attributes: Attributes
    def withAttributes(attributes: Attributes): StageModule
    override def carbonCopy: Module = withAttributes(attributes)
  }

  final case class StageFactory(mkStage: () ⇒ Stage[_, _], attributes: Attributes = stageFactory) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class MaterializingStageFactory(
    mkStageAndMaterialized: () ⇒ (Stage[_, _], Any),
    attributes: Attributes = stageFactory) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Identity(attributes: Attributes = identityOp) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Map(f: Any ⇒ Any, attributes: Attributes = map) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Log(name: String, extract: Any ⇒ Any, loggingAdapter: Option[LoggingAdapter], attributes: Attributes = map) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Filter(p: Any ⇒ Boolean, attributes: Attributes = filter) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Collect(pf: PartialFunction[Any, Any], attributes: Attributes = collect) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Recover(pf: PartialFunction[Any, Any], attributes: Attributes = recover) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class MapAsync(parallelism: Int, f: Any ⇒ Future[Any], attributes: Attributes = mapAsync) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class MapAsyncUnordered(parallelism: Int, f: Any ⇒ Future[Any], attributes: Attributes = mapAsyncUnordered) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Grouped(n: Int, attributes: Attributes = grouped) extends StageModule {
    require(n > 0, "n must be greater than 0")
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Sliding(n: Int, step: Int, attributes: Attributes = sliding) extends StageModule {
    require(n > 0, "n must be greater than 0")
    require(step > 0, "step must be greater than 0")
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Take(n: Long, attributes: Attributes = take) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Drop(n: Long, attributes: Attributes = drop) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class TakeWhile(p: Any ⇒ Boolean, attributes: Attributes = takeWhile) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)

  }

  final case class DropWhile(p: Any ⇒ Boolean, attributes: Attributes = dropWhile) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Scan(zero: Any, f: (Any, Any) ⇒ Any, attributes: Attributes = scan) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Fold(zero: Any, f: (Any, Any) ⇒ Any, attributes: Attributes = fold) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Intersperse(start: Option[Any], inject: Any, end: Option[Any], attributes: Attributes = intersperse) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Buffer(size: Int, overflowStrategy: OverflowStrategy, attributes: Attributes = buffer) extends StageModule {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")

    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Conflate(seed: Any ⇒ Any, aggregate: (Any, Any) ⇒ Any, attributes: Attributes = conflate) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Expand(seed: Any ⇒ Any, extrapolate: Any ⇒ (Any, Any), attributes: Attributes = expand) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class MapConcat(f: Any ⇒ immutable.Iterable[Any], attributes: Attributes = mapConcat) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class GroupBy(f: Any ⇒ Any, attributes: Attributes = groupBy) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class PrefixAndTail(n: Int, attributes: Attributes = prefixAndTail) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class Split(p: Any ⇒ SplitDecision, attributes: Attributes = split) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class ConcatAll(attributes: Attributes = concatAll) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }

  final case class DirectProcessor(p: () ⇒ (Processor[Any, Any], Any), attributes: Attributes = processor) extends StageModule {
    override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  }
}
