/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

class BehaviorSpec extends TypedSpec {

  sealed trait Command {
    def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Nil
  }
  case object GetSelf extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Self(ctx.self) :: Nil
  }
  // Behavior under test must return Unhandled
  case object Miss extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Missed :: Nil
  }
  // Behavior under test must return Same
  case object Ignore extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Ignored :: Nil
  }
  case object Ping extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Pong :: Nil
  }
  case object Swap extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Swapped :: Nil
  }
  case class GetState(replyTo: ActorRef[State]) extends Command
  object GetState {
    def apply()(implicit inbox: Inbox[State]): GetState = GetState(inbox.ref)
  }
  case class AuxPing(id: Int) extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Pong :: Nil
  }
  case object Stop extends Command

  sealed trait Event
  case class GotSignal(signal: Signal) extends Event
  case class Self(self: ActorRef[Command]) extends Event
  case object Missed extends Event
  case object Ignored extends Event
  case object Pong extends Event
  case object Swapped extends Event

  trait State { def next: State }
  val StateA: State = new State { override def toString = "StateA"; override def next = StateB }
  val StateB: State = new State { override def toString = "StateB"; override def next = StateA }

  trait Common {
    def system: ActorSystem[TypedSpec.Command]
    def behavior(monitor: ActorRef[Event]): Behavior[Command]

    case class Setup(ctx: EffectfulActorContext[Command], inbox: Inbox[Event])

    protected def mkCtx(requirePreStart: Boolean = false, factory: (ActorRef[Event]) ⇒ Behavior[Command] = behavior) = {
      val inbox = Inbox[Event]("evt")
      val ctx = new EffectfulActorContext("ctx", factory(inbox.ref), 1000, system)
      val msgs = inbox.receiveAll()
      if (requirePreStart)
        msgs should ===(GotSignal(PreStart) :: Nil)
      Setup(ctx, inbox)
    }

    protected implicit class Check(val setup: Setup) {
      def check(signal: Signal): Setup = {
        setup.ctx.signal(signal)
        setup.inbox.receiveAll() should ===(GotSignal(signal) :: Nil)
        setup
      }
      def check(command: Command): Setup = {
        setup.ctx.run(command)
        setup.inbox.receiveAll() should ===(command.expectedResponse(setup.ctx))
        setup
      }
      def check[T](command: Command, aux: T*)(implicit inbox: Inbox[T]): Setup = {
        setup.ctx.run(command)
        setup.inbox.receiveAll() should ===(command.expectedResponse(setup.ctx))
        inbox.receiveAll() should ===(aux)
        setup
      }
      def check2(command: Command): Setup = {
        setup.ctx.run(command)
        val expected = command.expectedResponse(setup.ctx)
        setup.inbox.receiveAll() should ===(expected ++ expected)
        setup
      }
      def check2[T](command: Command, aux: T*)(implicit inbox: Inbox[T]): Setup = {
        setup.ctx.run(command)
        val expected = command.expectedResponse(setup.ctx)
        setup.inbox.receiveAll() should ===(expected ++ expected)
        inbox.receiveAll() should ===(aux ++ aux)
        setup
      }
    }

    protected val ex = new Exception("mine!")
  }

  trait Lifecycle extends Common {
    def `must react to PreStart`(): Unit = {
      mkCtx(requirePreStart = true)
    }

    def `must react to PostStop`(): Unit = {
      mkCtx().check(PostStop)
    }

    def `must react to PostStop after a message`(): Unit = {
      mkCtx().check(GetSelf).check(PostStop)
    }

    def `must react to PreRestart`(): Unit = {
      mkCtx().check(PreRestart)
    }

    def `must react to PreRestart after a message`(): Unit = {
      mkCtx().check(GetSelf).check(PreRestart)
    }

    def `must react to Terminated`(): Unit = {
      mkCtx().check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to Terminated after a message`(): Unit = {
      mkCtx().check(GetSelf).check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to a message after Terminated`(): Unit = {
      mkCtx().check(Terminated(Inbox("x").ref)(null)).check(GetSelf)
    }
  }

  trait Messages extends Common {
    def `must react to two messages`(): Unit = {
      mkCtx().check(Ping).check(Ping)
    }

    def `must react to a message after missing one`(): Unit = {
      mkCtx().check(Miss).check(Ping)
    }

    def `must react to a message after ignoring one`(): Unit = {
      mkCtx().check(Ignore).check(Ping)
    }
  }

  trait Unhandled extends Common {
    def `must return Unhandled`(): Unit = {
      val Setup(ctx, inbox) = mkCtx()
      ctx.currentBehavior.message(ctx, Miss) should ===(ScalaDSL.Unhandled[Command])
      inbox.receiveAll() should ===(Missed :: Nil)
    }
  }

  trait Stoppable extends Common {
    def `must stop`(): Unit = {
      val Setup(ctx, inbox) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should ===(ScalaDSL.Stopped[Command])
    }
  }

  trait Become extends Common with Unhandled {
    private implicit val inbox = Inbox[State]("state")

    def `must be in state A`(): Unit = {
      mkCtx().check(GetState(), StateA)
    }

    def `must switch to state B`(): Unit = {
      mkCtx().check(Swap).check(GetState(), StateB)
    }

    def `must switch back to state A`(): Unit = {
      mkCtx().check(Swap).check(Swap).check(GetState(), StateA)
    }
  }

  trait BecomeWithLifecycle extends Become with Lifecycle {
    def `must react to PostStop after swap`(): Unit = {
      mkCtx().check(Swap).check(PostStop)
    }

    def `must react to PostStop after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(PostStop)
    }

    def `must react to PreRestart after swap`(): Unit = {
      mkCtx().check(Swap).check(PreRestart)
    }

    def `must react to PreRestart after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(PreRestart)
    }

    def `must react to Terminated after swap`(): Unit = {
      mkCtx().check(Swap).check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to Terminated after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to a message after Terminated after swap`(): Unit = {
      mkCtx().check(Swap).check(Terminated(Inbox("x").ref)(null)).check(GetSelf)
    }
  }

  private def mkFull(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] = {
    import ScalaDSL.{ Full, Msg, Sig, Same, Unhandled, Stopped }
    Full {
      case Sig(ctx, signal) ⇒
        monitor ! GotSignal(signal)
        Same
      case Msg(ctx, GetSelf) ⇒
        monitor ! Self(ctx.self)
        Same
      case Msg(ctx, Miss) ⇒
        monitor ! Missed
        Unhandled
      case Msg(ctx, Ignore) ⇒
        monitor ! Ignored
        Same
      case Msg(ctx, Ping) ⇒
        monitor ! Pong
        mkFull(monitor, state)
      case Msg(ctx, Swap) ⇒
        monitor ! Swapped
        mkFull(monitor, state.next)
      case Msg(ctx, GetState(replyTo)) ⇒
        replyTo ! state
        Same
      case Msg(ctx, Stop) ⇒ Stopped
    }
  }

  trait FullBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = mkFull(monitor)
  }
  object `A Full Behavior (native)` extends FullBehavior with NativeSystem
  object `A Full Behavior (adapted)` extends FullBehavior with AdaptedSystem

  trait FullTotalBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = behv(monitor, StateA)
    private def behv(monitor: ActorRef[Event], state: State): Behavior[Command] = {
      import ScalaDSL.{ FullTotal, Msg, Sig, Same, Unhandled, Stopped }
      FullTotal {
        case Sig(ctx, signal) ⇒
          monitor ! GotSignal(signal)
          Same
        case Msg(ctx, GetSelf) ⇒
          monitor ! Self(ctx.self)
          Same
        case Msg(_, Miss) ⇒
          monitor ! Missed
          Unhandled
        case Msg(_, Ignore) ⇒
          monitor ! Ignored
          Same
        case Msg(_, Ping) ⇒
          monitor ! Pong
          behv(monitor, state)
        case Msg(_, Swap) ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case Msg(_, GetState(replyTo)) ⇒
          replyTo ! state
          Same
        case Msg(_, Stop)       ⇒ Stopped
        case Msg(_, _: AuxPing) ⇒ Unhandled
      }
    }
  }
  object `A FullTotal Behavior (native)` extends FullTotalBehavior with NativeSystem
  object `A FullTotal Behavior (adapted)` extends FullTotalBehavior with AdaptedSystem

  trait WidenedBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Widened(mkFull(monitor), { case x ⇒ x })
  }
  object `A Widened Behavior (native)` extends WidenedBehavior with NativeSystem
  object `A Widened Behavior (adapted)` extends WidenedBehavior with AdaptedSystem

  trait ContextAwareBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.ContextAware(ctx ⇒ mkFull(monitor))
  }
  object `A ContextAware Behavior (native)` extends ContextAwareBehavior with NativeSystem
  object `A ContextAware Behavior (adapted)` extends ContextAwareBehavior with AdaptedSystem

  trait SelfAwareBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.SelfAware(self ⇒ mkFull(monitor))
  }
  object `A SelfAware Behavior (native)` extends SelfAwareBehavior with NativeSystem
  object `A SelfAware Behavior (adapted)` extends SelfAwareBehavior with AdaptedSystem

  trait NonMatchingTapBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Tap({ case null ⇒ }, mkFull(monitor))
  }
  object `A non-matching Tap Behavior (native)` extends NonMatchingTapBehavior with NativeSystem
  object `A non-matching Tap Behavior (adapted)` extends NonMatchingTapBehavior with AdaptedSystem

  trait MatchingTapBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Tap({ case _ ⇒ }, mkFull(monitor))
  }
  object `A matching Tap Behavior (native)` extends MatchingTapBehavior with NativeSystem
  object `A matching Tap Behavior (adapted)` extends MatchingTapBehavior with AdaptedSystem

  trait PartialBehavior extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = behv(monitor, StateA)
    def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
      ScalaDSL.Partial {
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Miss ⇒
          monitor ! Missed
          ScalaDSL.Unhandled
        case Ignore ⇒
          monitor ! Ignored
          ScalaDSL.Same
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState(replyTo) ⇒
          replyTo ! state
          ScalaDSL.Same
        case Stop ⇒ ScalaDSL.Stopped
      }
  }
  object `A Partial Behavior (native)` extends PartialBehavior with NativeSystem
  object `A Partial Behavior (adapted)` extends PartialBehavior with AdaptedSystem

  trait TotalBehavior extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = behv(monitor, StateA)
    def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
      ScalaDSL.Total {
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Miss ⇒
          monitor ! Missed
          ScalaDSL.Unhandled
        case Ignore ⇒
          monitor ! Ignored
          ScalaDSL.Same
        case GetSelf ⇒ ScalaDSL.Unhandled
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState(replyTo) ⇒
          replyTo ! state
          ScalaDSL.Same
        case Stop       ⇒ ScalaDSL.Stopped
        case _: AuxPing ⇒ ScalaDSL.Unhandled
      }
  }
  object `A Total Behavior (native)` extends TotalBehavior with NativeSystem
  object `A Total Behavior (adapted)` extends TotalBehavior with AdaptedSystem

  trait StaticBehavior extends Messages {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Static {
        case Ping        ⇒ monitor ! Pong
        case Miss        ⇒ monitor ! Missed
        case Ignore      ⇒ monitor ! Ignored
        case GetSelf     ⇒
        case Swap        ⇒
        case GetState(_) ⇒
        case Stop        ⇒
        case _: AuxPing  ⇒
      }
  }
  object `A Static Behavior (native)` extends StaticBehavior with NativeSystem
  object `A Static Behavior (adapted)` extends StaticBehavior with AdaptedSystem
}
