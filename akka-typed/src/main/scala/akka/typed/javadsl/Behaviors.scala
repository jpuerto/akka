/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package javadsl

import akka.japi.function
import akka.util.LineNumbers

final case class Full[T](signal: function.Function2[ActorContext[T], Signal, Behavior[T]],
                         mesg: function.Function2[ActorContext[T], T, Behavior[T]]) extends Behavior[T] {
  override def management(ctx: ActorContext[T], msg: Signal) = signal(ctx, msg)
  override def message(ctx: ActorContext[T], msg: T) = mesg(ctx, msg)
  override def toString = s"Full(${LineNumbers(signal)},${LineNumbers(mesg)})"
}
final case class Static[T](mesg: function.Procedure2[ActorContext[T], T]) extends Behavior[T] {
  override def management(ctx: ActorContext[T], msg: Signal) = ScalaDSL.Unhandled
  override def message(ctx: ActorContext[T], msg: T) = {
    mesg(ctx, msg)
    ScalaDSL.Same
  }
  override def toString = s"Static(${LineNumbers(mesg)})"
}
final case class Tap[T](signal: function.Procedure2[ActorContext[T], Signal],
                        mesg: function.Procedure2[ActorContext[T], T],
                        behavior: Behavior[T]) extends Behavior[T] {
  private def canonical(behv: Behavior[T]): Behavior[T] =
    if (Behavior.isUnhandled(behv)) ScalaDSL.Unhandled
    else if (behv eq Behavior.sameBehavior) ScalaDSL.Same
    else if (Behavior.isAlive(behv)) Tap(signal, mesg, behv)
    else ScalaDSL.Stopped
  override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
    signal(ctx, msg)
    canonical(behavior.management(ctx, msg))
  }
  override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
    mesg(ctx, msg)
    canonical(behavior.message(ctx, msg))
  }
  override def toString = s"Tap(${LineNumbers(signal)},${LineNumbers(mesg)},$behavior)"
}

object Actor {
  private val _unhandledFun =
    new function.Function2[Any, Signal, Behavior[Any]] {
      override def apply(x: Any, sig: Signal) = ScalaDSL.Unhandled
    }
  private def unhandledFun[T] = _unhandledFun.asInstanceOf[function.Function2[Any, Signal, Behavior[T]]]
  private val effectFun =
    new function.Procedure2[Any, Any] {
      override def apply(x: Any, y: Any) = {}
    }

  def signalOrMessage[T](signal: function.Function2[ActorContext[T], Signal, Behavior[T]],
                         message: function.Function2[ActorContext[T], T, Behavior[T]]): Behavior[T] =
    Full(signal, message)

  def stateful[T](message: function.Function2[ActorContext[T], T, Behavior[T]]): Behavior[T] =
    Full(unhandledFun, message)

  def stateless[T](message: function.Procedure2[ActorContext[T], T]): Behavior[T] =
    Static(message)

  def tap[T](signal: function.Procedure2[ActorContext[T], Signal],
             mesg: function.Procedure2[ActorContext[T], T],
             behavior: Behavior[T]): Behavior[T] =
    Tap(signal, mesg, behavior)

  def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Behavior[T] =
    Tap(effectFun, new function.Procedure2[ActorContext[T], T] {
      override def apply(ctx: ActorContext[T], msg: T) = monitor ! msg
    }, behavior)

  def same[T]: Behavior[T] = ScalaDSL.Same
  def unhandled[T]: Behavior[T] = ScalaDSL.Unhandled
  def stopped[T]: Behavior[T] = ScalaDSL.Stopped
}
