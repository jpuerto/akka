/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.duration._
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.impl.SynchronousIterablePublisher
import org.reactivestreams.Subscription
import akka.testkit.TestProbe
import org.reactivestreams.Subscriber

class FlowIteratorSpec extends AbstractFlowIteratorSpec {
  override def testName = "A Flow based on an iterator producing function"
  override def createSource(elements: Int): Source[Int, Unit] =
    Source(() ⇒ (1 to elements).iterator)
}

class FlowIterableSpec extends AbstractFlowIteratorSpec {
  override def testName = "A Flow based on an iterable"
  override def createSource(elements: Int): Source[Int, Unit] =
    Source(1 to elements)

  implicit def mmaterializer = super.materializer

  "produce onError when iterator throws" in {
    val iterable = new immutable.Iterable[Int] {
      override def iterator: Iterator[Int] =
        (1 to 3).iterator.map(x ⇒ if (x == 2) throw new IllegalStateException("not two") else x)
    }
    val p = Source(iterable).runWith(Sink.publisher)
    val c = TestSubscriber.manualProbe[Int]()
    p.subscribe(c)
    val sub = c.expectSubscription()
    sub.request(1)
    c.expectNext(1)
    c.expectNoMsg(100.millis)
    sub.request(2)
    c.expectError.getMessage should be("not two")
    sub.request(2)
    c.expectNoMsg(100.millis)
  }

  "produce onError when Source construction throws" in {
    val iterable = new immutable.Iterable[Int] {
      override def iterator: Iterator[Int] = throw new IllegalStateException("no good iterator")
    }
    val p = Source(iterable).runWith(Sink.publisher)
    val c = TestSubscriber.manualProbe[Int]()
    p.subscribe(c)
    c.expectSubscriptionAndError().getMessage should be("no good iterator")
    c.expectNoMsg(100.millis)
  }

  "produce onError when hasNext throws" in {
    val iterable = new immutable.Iterable[Int] {
      override def iterator: Iterator[Int] = new Iterator[Int] {
        override def hasNext: Boolean = throw new IllegalStateException("no next")
        override def next(): Int = -1
      }
    }
    val p = Source(iterable).runWith(Sink.publisher)
    val c = TestSubscriber.manualProbe[Int]()
    p.subscribe(c)
    c.expectSubscriptionAndError().getMessage should be("no next")
    c.expectNoMsg(100.millis)
  }
}

class SynchronousIterableSpec extends AbstractFlowIteratorSpec {
  override def testName = "A Flow based on small collection"
  override def createSource(elements: Int): Source[Int, Unit] =
    Source(SynchronousIterablePublisher(1 to elements, "range"))

  "not produce after cancel from onNext" in {
    val p = SynchronousIterablePublisher(1 to 5, "range")
    val probe = TestProbe()
    p.subscribe(new Subscriber[Int] {
      var sub: Subscription = _
      override def onError(cause: Throwable): Unit = probe.ref ! cause
      override def onComplete(): Unit = probe.ref ! "complete"
      override def onNext(element: Int): Unit = {
        probe.ref ! element
        if (element == 3) sub.cancel()
      }
      override def onSubscribe(subscription: Subscription): Unit = {
        sub = subscription
        sub.request(10)
      }
    })

    probe.expectMsg(1)
    probe.expectMsg(2)
    probe.expectMsg(3)
    probe.expectNoMsg(500.millis)
  }

  "produce onError when iterator throws" in {
    val iterable = new immutable.Iterable[Int] {
      override def iterator: Iterator[Int] =
        (1 to 3).iterator.map(x ⇒ if (x == 2) throw new IllegalStateException("not two") else x)
    }
    val p = SynchronousIterablePublisher(iterable, "iterable")
    val c = TestSubscriber.manualProbe[Int]()
    p.subscribe(c)
    val sub = c.expectSubscription()
    sub.request(1)
    c.expectNext(1)
    c.expectNoMsg(100.millis)
    sub.request(2)
    c.expectError.getMessage should be("not two")
    sub.request(2)
    c.expectNoMsg(100.millis)
  }

  "handle reentrant requests" in {
    val N = 50000
    val p = SynchronousIterablePublisher(1 to N, "range")
    val probe = TestProbe()
    p.subscribe(new Subscriber[Int] {
      var sub: Subscription = _
      override def onError(cause: Throwable): Unit = probe.ref ! cause
      override def onComplete(): Unit = probe.ref ! "complete"
      override def onNext(element: Int): Unit = {
        probe.ref ! element
        sub.request(1)

      }
      override def onSubscribe(subscription: Subscription): Unit = {
        sub = subscription
        sub.request(1)
      }
    })
    probe.receiveN(N) should be((1 to N).toVector)
    probe.expectMsg("complete")
  }

  "have a toString that doesn't OOME" in {
    SynchronousIterablePublisher(1 to 3, "range").toString should be("range")
  }
}

abstract class AbstractFlowIteratorSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  private val m = ActorFlowMaterializer(settings)
  implicit final def materializer = m

  def testName: String

  def createSource(elements: Int): Source[Int, Unit]

  testName must {
    "produce elements" in assertAllStagesStopped {
      val p = createSource(3).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectNoMsg(100.millis)
      sub.request(3)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "complete empty" in assertAllStagesStopped {
      val p = createSource(0).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      c.expectSubscriptionAndComplete()
      c.expectNoMsg(100.millis)
    }

    "produce elements with multiple subscribers" in assertAllStagesStopped {
      val p = createSource(3).runWith(Sink.fanoutPublisher(2, 4))
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c1)
      p.subscribe(c2)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c2.expectNext(2)
      c1.expectNoMsg(100.millis)
      c2.expectNoMsg(100.millis)
      sub1.request(2)
      sub2.request(2)
      c1.expectNext(2)
      c1.expectNext(3)
      c2.expectNext(3)
      c1.expectComplete()
      c2.expectComplete()
    }

    "produce elements to later subscriber" in assertAllStagesStopped {
      val p = createSource(3).runWith(Sink.fanoutPublisher(2, 4))
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      p.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      // element 1 is already gone
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
      sub1.request(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "produce elements with one transformation step" in assertAllStagesStopped {
      val p = createSource(3).map(_ * 2).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(2)
      c.expectNext(4)
      c.expectNext(6)
      c.expectComplete()
    }

    "produce elements with two transformation steps" in assertAllStagesStopped {
      val p = createSource(4).filter(_ % 2 == 0).map(_ * 2).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(4)
      c.expectNext(8)
      c.expectComplete()
    }

    "not produce after cancel" in assertAllStagesStopped {
      val p = createSource(3).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      sub.cancel()
      sub.request(2)
      c.expectNoMsg(100.millis)
    }

  }
}