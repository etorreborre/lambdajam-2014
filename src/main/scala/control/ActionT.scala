package control

import scalaz._, Scalaz._, \&/._
import scalaz.effect._
import scalaz.concurrent.Task

/**
 * A data type for holding computations that can fail with exceptions.
 * This is effectively a ReaderT > ErrorT > WriterT > F stack, with
 * a specialized error type. This particular specializtion handles
 * string/exception based failures and should be used to wrap up unsafe
 * apis (i.e. java code).
 *
 * This specialization exists for a number of reasons:
 *  - Basically because you can't use the stack directly via a type alias
 *    without incurring the wrath of scalac and the inference warlords.
 *  - The formulation lets us plug in a few things together to handle
 *    IO and other values of F, whilst keeping some level of sanity.
 *
 * NOTE: This is specifically formulated to not hit scalac bugzzzzz, change with caution.....
 */
case class ActionT[F[+_], W, R, +A](runT: R => StatusT[({ type l[+a] = WriterT[F, W, a] })#l, A]) {
  def map[B](f: A => B)(implicit W: Monoid[W], F: Functor[F]): ActionT[F, W, R, B] =
    ActionT(r => runT(r).map(f))

  def contramap[B](f: B => R)(implicit W: Monoid[W], F: Functor[F]): ActionT[F, W, B, A] =
    ActionT(r => runT(f(r)))

  def flatMap[B](f: A => ActionT[F, W, R, B])(implicit W: Monoid[W], F: Monad[F]): ActionT[F, W, R, B] =
    ActionT(r => runT(r).flatMap(a => f(a).runT(r)))

  def onStatus[B](f: Status[A] => Status[B])(implicit W: Monoid[W], F: Functor[F]): ActionT[F, W, R, B] =
    ActionT(r => runT(r).onStatus(f))

  def mapError(f: These[String, Throwable] => These[String, Throwable])(implicit W: Monoid[W], F: Functor[F]): ActionT[F, W, R, A] =
    onStatus(_.mapError(f))

  def run(r: R): F[(W, Status[A])] =
    runT(r).run.run

  def execute(r: R)(implicit F: Functor[F]): F[Status[A]] =
    run(r).map({ case (w, a) => a })

  def executeT(r: R)(implicit F: Functor[F]): StatusT[F, A] =
    StatusT(execute(r))

  def |||[AA >: A](otherwise: => ActionT[F, W, R, AA])(implicit W: Monoid[W], F: Monad[F]): ActionT[F, W, R, AA] =
    ActionT[F, W, R, AA](r => runT(r) ||| otherwise.runT(r))

  def orElse[AA >: A](otherwise: => AA)(implicit W: Monoid[W], F: Monad[F]): ActionT[F, W, R, AA] =
    |||(ActionT.ok[F, W, R, AA](otherwise))
}

object ActionT extends ActionTLowPriority {
  def ask[F[+_]: Monad, W: Monoid, R]: ActionT[F, W, R, R] =
    reader(identity)

  def reader[F[+_]: Monad, W: Monoid, R, A](f: R => A): ActionT[F, W, R, A] =
    ActionT(r => StatusT.safe[({ type l[+a] = WriterT[F, W, a] })#l, A](f(r)))

  def status[F[+_]: Monad, W: Monoid, R, A](f: R => Status[A]): ActionT[F, W, R, A] =
    ActionT(r => StatusT.status[({ type l[+a] = WriterT[F, W, a] })#l, A](f(r)))

  def option[F[+_]: Monad, W: Monoid, R, A](f: R => A): ActionT[F, W, R, Option[A]] =
    ActionT(r => StatusT.option[({ type l[+a] = WriterT[F, W, a] })#l, A](f(r)))

  def safe[F[+_]: Monad, W: Monoid, R, A](a: => A): ActionT[F, W, R, A] =
    reader[F, W, R, A](_ => a)

  def ok[F[+_]: Monad, W: Monoid, R, A](a: A): ActionT[F, W, R, A] =
    ActionT(_ => StatusT.ok[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def exception[F[+_]: Monad, W: Monoid, R, A](t: Throwable): ActionT[F, W, R, A] =
    ActionT(_ => StatusT.exception[({ type l[+a] = WriterT[F, W, a] })#l, A](t))

  def fail[F[+_]: Monad, W: Monoid, R, A](message: String): ActionT[F, W, R, A] =
    ActionT(_ => StatusT.fail[({ type l[+a] = WriterT[F, W, a] })#l, A](message))

  def error[F[+_]: Monad, W: Monoid, R, A](message: String, t: Throwable): ActionT[F, W, R, A] =
    ActionT(_ => StatusT.error[({ type l[+a] = WriterT[F, W, a] })#l, A](message, t))

  def these[F[+_]: Monad, W: Monoid, R, A](both: These[String, Throwable]): ActionT[F, W, R, A] =
    ActionT(_ => StatusT.these[({ type l[+a] = WriterT[F, W, a] })#l, A](both))

  def fromDisjunction[F[+_]: Monad, W: Monoid, R, A](either: These[String, Throwable] \/ A): ActionT[F, W, R, A] =
    ActionT[F, W, R, A](_ => StatusT.fromDisjunction[({ type l[+a] = WriterT[F, W, a] })#l, A](either))

  def fromDisjunctionString[F[+_]: Monad, W: Monoid, R, A](either: String \/ A): ActionT[F, W, R, A] =
    fromDisjunction[F, W, R, A](either.leftMap(This.apply))

  def fromDisjunctionThrowable[F[+_]: Monad, W: Monoid, R, A](either: Throwable \/ A): ActionT[F, W, R, A] =
    fromDisjunction[F, W, R, A](either.leftMap(That.apply))

  def fromDisjunctionF[F[+_]: Monad, W: Monoid, R, A](either: F[These[String, Throwable] \/ A]): ActionT[F, W, R, A] =
    ActionT[F, W, R, A](_ => StatusT.fromDisjunctionF[({ type l[+a] = WriterT[F, W, a] })#l, A](WriterT(either.map(a => (Monoid[W].zero, a)))))

  def fromIO[F[+_]: MonadIO, W: Monoid, R, A](v: IO[A]): ActionT[F, W, R, A] =
    ActionT[F, W, R, A](_ => StatusT[({ type l[+a] = WriterT[F, W, a] })#l, A](WriterT(v.map(a => (Monoid[W].zero, Status.ok(a))).liftIO[F])))

  def fromTask[F[+_]: MonadIO, W: Monoid, R, A](v: Task[A]): ActionT[F, W, R, A] =
    fromIO(IO(v.run))

  def fromIOStatus[F[+_]: MonadIO, W: Monoid, R, A](v: IO[Status[A]]): ActionT[F, W, R, A] =
    fromIO[F, W, R, Status[A]](v).flatMap(r => status(_ => r))

  implicit def ActionTMonad[F[+_]: Monad, W: Monoid, R]: Monad[({ type l[a] = ActionT[F, W, R, a] })#l] =
    new Monad[({ type l[a] = ActionT[F, W, R, a] })#l] {
      def bind[A, B](a: ActionT[F, W, R, A])(f: A => ActionT[F, W, R, B]) = a.flatMap(f)
      def point[A](a: => A) = ok[F, W, R, A](a)
    }
}

trait ActionTLowPriority {
  implicit def ActionTMonadIO[F[+_]: MonadIO, W: Monoid, R]: MonadIO[({ type l[a] = ActionT[F, W, R, a] })#l] =
    new MonadIO[({ type l[a] = ActionT[F, W, R, a] })#l] {
      def bind[A, B](a: ActionT[F, W, R, A])(f: A => ActionT[F, W, R, B]) = a.flatMap(f)
      def point[A](a: => A) = ActionT.ok[F, W, R, A](a)
      def liftIO[A](a: IO[A]) = ActionT.fromIO[F, W, R, A](a)
    }
}


trait ActionTSupport[F[+_], W, R] {
  def ask(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, R] =
    ActionT.ask

  def reader[A](f: R => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.reader(f)

  def status[A](f: R => Status[A])(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.status(f)

  def option[A](f: R => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, Option[A]] =
    ActionT.option(f)

  def safe[A](a: => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.safe(a)

  def ok[A](a: => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.ok(a)

  def fromIO[A](v: IO[A])(implicit M: MonadIO[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.fromIO(v)

  def fromIO[A](v: =>A)(implicit M: MonadIO[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.fromIO(IO(v))

  def fromTask[A](v: Task[A])(implicit M: MonadIO[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.fromTask(v)

  def fromIOStatus[A](v: IO[Status[A]])(implicit M: MonadIO[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.fromIOStatus(v)

  def exception[A](t: Throwable)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.exception(t)

  def fail[A](message: String)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.fail(message)

  def error[A](message: String, t: Throwable)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.error(message, t)

  def these[A](both: These[String, Throwable])(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT.these(both)
}

