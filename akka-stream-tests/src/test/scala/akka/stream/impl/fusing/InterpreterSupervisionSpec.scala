/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import scala.util.control.NoStackTrace
import akka.stream.Supervision
import akka.stream.stage.Context
import akka.stream.stage.Directive
import akka.stream.stage.PushPullStage
import akka.stream.stage.Stage
import akka.stream.stage.TerminationDirective
import akka.stream.stage.SyncDirective

object InterpreterSupervisionSpec {
  val TE = new Exception("TEST") with NoStackTrace {
    override def toString = "TE"
  }

  class RestartTestStage extends PushPullStage[Int, Int] {
    var sum = 0
    def onPush(elem: Int, ctx: Context[Int]): SyncDirective = {
      sum += elem
      ctx.push(sum)
    }

    override def onPull(ctx: Context[Int]): SyncDirective = {
      ctx.pull()
    }

    override def decide(t: Throwable): Supervision.Directive = Supervision.Restart

    override def restart(): Stage[Int, Int] = {
      sum = 0
      this
    }
  }

  case class OneToManyTestStage(decider: Supervision.Decider, absorbTermination: Boolean = false) extends PushPullStage[Int, Int] {
    var buf: List[Int] = Nil
    def onPush(elem: Int, ctx: Context[Int]): SyncDirective = {
      buf = List(elem + 1, elem + 2, elem + 3)
      ctx.push(elem)
    }

    override def onPull(ctx: Context[Int]): SyncDirective = {
      if (buf.isEmpty && ctx.isFinishing)
        ctx.finish()
      else if (buf.isEmpty)
        ctx.pull()
      else {
        val elem = buf.head
        buf = buf.tail
        if (elem == 3) throw TE
        ctx.push(elem)
      }
    }

    override def onUpstreamFinish(ctx: Context[Int]): TerminationDirective =
      if (absorbTermination)
        ctx.absorbTermination()
      else
        ctx.finish()

    // note that resume will be turned into failure in the Interpreter if exception is thrown from onPull
    override def decide(t: Throwable): Supervision.Directive = decider(t)

    override def restart(): OneToManyTestStage = copy()
  }

}

class InterpreterSupervisionSpec extends InterpreterSpecKit {
  import InterpreterSupervisionSpec._
  import Supervision.stoppingDecider
  import Supervision.resumingDecider
  import Supervision.restartingDecider

  "Interpreter error handling" must {

    "handle external failure" in new TestSetup(Seq(Map((x: Int) ⇒ x + 1, stoppingDecider))) {
      lastEvents() should be(Set.empty)

      upstream.onError(TE)
      lastEvents() should be(Set(OnError(TE)))

    }

    "emit failure when op throws" in new TestSetup(Seq(Map((x: Int) ⇒ if (x == 0) throw TE else x, stoppingDecider))) {
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(Cancel, OnError(TE)))
    }

    "emit failure when op throws in middle of the chain" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, stoppingDecider),
      Map((x: Int) ⇒ if (x == 0) throw TE else x + 10, stoppingDecider),
      Map((x: Int) ⇒ x + 100, stoppingDecider))) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(113)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(-1) // boom
      lastEvents() should be(Set(Cancel, OnError(TE)))
    }

    "resume when Map throws" in new TestSetup(Seq(Map((x: Int) ⇒ if (x == 0) throw TE else x, resumingDecider))) {
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(3)))

      // try one more time
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4)))
    }

    "resume when Map throws in middle of the chain" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, resumingDecider),
      Map((x: Int) ⇒ if (x == 0) throw TE else x + 10, resumingDecider),
      Map((x: Int) ⇒ x + 100, resumingDecider))) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(113)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(114)))
    }

    "resume when Map throws before Grouped" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, resumingDecider),
      Map((x: Int) ⇒ if (x <= 0) throw TE else x + 10, resumingDecider),
      Grouped(3))) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(Vector(13, 14, 15))))
    }

    "complete after resume when Map throws before Grouped" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1, resumingDecider),
      Map((x: Int) ⇒ if (x <= 0) throw TE else x + 10, resumingDecider),
      Grouped(1000))) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(Vector(13, 14)), OnComplete))
    }

    "restart when onPush throws" in {
      val stage = new RestartTestStage {
        override def onPush(elem: Int, ctx: Context[Int]): SyncDirective = {
          if (elem <= 0) throw TE
          else super.onPush(elem, ctx)
        }
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(-1) // boom
        lastEvents() should be(Set(RequestOne))

        upstream.onNext(3)
        lastEvents() should be(Set(OnNext(104)))
      }
    }

    "restart when onPush throws after ctx.push" in {
      val stage = new RestartTestStage {
        override def onPush(elem: Int, ctx: Context[Int]): SyncDirective = {
          val ret = ctx.push(sum)
          super.onPush(elem, ctx)
          if (elem <= 0) throw TE
          ret
        }
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(-1) // boom
        lastEvents() should be(Set(RequestOne))

        upstream.onNext(3)
        lastEvents() should be(Set(OnNext(104)))
      }
    }

    "fail when onPull throws" in {
      val stage = new RestartTestStage {
        override def onPull(ctx: Context[Int]): SyncDirective = {
          if (sum < 0) throw TE
          super.onPull(ctx)
        }
      }

      new TestSetup(Seq(
        Map((x: Int) ⇒ x + 1, restartingDecider),
        stage,
        Map((x: Int) ⇒ x + 100, restartingDecider))) {

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(103)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(-5) // this will trigger failure of next requestOne (pull)
        lastEvents() should be(Set(OnNext(99)))

        downstream.requestOne() // boom
        lastEvents() should be(Set(OnError(TE), Cancel))
      }
    }

    "resume when Filter throws" in new TestSetup(Seq(
      Filter((x: Int) ⇒ if (x == 0) throw TE else true, resumingDecider))) {
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(3)))
    }

    "resume when MapConcat throws" in new TestSetup(Seq(
      MapConcat((x: Int) ⇒ if (x == 0) throw TE else List(x, -x), resumingDecider))) {
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1)))
      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))
      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-2)))
    }

    "restart when Collect throws" in {
      // TODO can't get type inference to work with `pf` inlined
      val pf: PartialFunction[Int, Int] =
        { case x: Int ⇒ if (x == 0) throw TE else x }
      new TestSetup(Seq(
        Collect(pf, restartingDecider))) {
        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(2)
        lastEvents() should be(Set(OnNext(2)))

        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))
        upstream.onNext(0) // boom
        lastEvents() should be(Set(RequestOne))

        upstream.onNext(3)
        lastEvents() should be(Set(OnNext(3)))
      }
    }

    "resume when Scan throws" in new TestSetup(Seq(
      Scan(1, (acc: Int, x: Int) ⇒ if (x == 10) throw TE else acc + x, resumingDecider))) {
      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(10) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(7))) // 1 + 2 + 4
    }

    "restart when Scan throws" in new TestSetup(Seq(
      Scan(1, (acc: Int, x: Int) ⇒ if (x == 10) throw TE else acc + x, restartingDecider))) {
      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(10) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(1))) // starts over again

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(5)))
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(20)
      lastEvents() should be(Set(OnNext(25))) // 1 + 4 + 20
    }

    "restart when Conflate `seed` throws" in new TestSetup(Seq(Conflate(
      (in: Int) ⇒ if (in == 1) throw TE else in,
      (agg: Int, x: Int) ⇒ agg + x,
      restartingDecider))) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(10)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(12))) // note that 1 has been discarded

      downstream.requestOne()
      lastEvents() should be(Set.empty)
    }

    "restart when Conflate `aggregate` throws" in new TestSetup(Seq(Conflate(
      (in: Int) ⇒ in,
      (agg: Int, x: Int) ⇒ if (x == 2) throw TE else agg + x,
      restartingDecider))) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(10)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(10))) // note that 1 and 2 has been discarded

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4), RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "fail when Expand `seed` throws" in new TestSetup(Seq(Expand(
      (in: Int) ⇒ if (in == 2) throw TE else in,
      (agg: Int) ⇒ (agg, -math.abs(agg))))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      upstream.onNext(2) // boom
      lastEvents() should be(Set(OnError(TE), Cancel))
    }

    "fail when Expand `extrapolate` throws" in new TestSetup(Seq(Expand(
      (in: Int) ⇒ in,
      (agg: Int) ⇒ if (agg == 2) throw TE else (agg, -math.abs(agg))))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      upstream.onNext(2) // boom
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(OnError(TE), Cancel))
    }

    "fail when onPull throws before pushing all generated elements" in {
      def test(decider: Supervision.Decider, absorbTermination: Boolean): Unit = {
        new TestSetup(Seq(
          OneToManyTestStage(decider, absorbTermination))) {

          downstream.requestOne()
          lastEvents() should be(Set(RequestOne))
          upstream.onNext(1)
          lastEvents() should be(Set(OnNext(1)))

          if (absorbTermination) {
            upstream.onComplete()
            lastEvents() should be(Set.empty)
          }

          downstream.requestOne()
          lastEvents() should be(Set(OnNext(2)))

          downstream.requestOne()
          // 3 => boom
          if (absorbTermination)
            lastEvents() should be(Set(OnError(TE)))
          else
            lastEvents() should be(Set(OnError(TE), Cancel))
        }
      }

      test(resumingDecider, absorbTermination = false)
      test(restartingDecider, absorbTermination = false)
      test(resumingDecider, absorbTermination = true)
      test(restartingDecider, absorbTermination = true)
    }

  }

}
