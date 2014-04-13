import control._
import java.io.File
import org.specs2.execute.Error.ThrowableException
import org.specs2.execute.{Result, AsResult}
import org.specs2.matcher.{Matcher, MatchersImplicits}
import scalaz.concurrent.Task
import scalaz.effect.IO
import scalaz.stream.Process
import scalaz._, Scalaz._

package object io extends MatchersImplicits {
  type Logger = String => IO[Unit]
  lazy val noLogging = (s: String) => IO(())
  lazy val consoleLogging = (s: String) => IO(println(s))

  type Action[+A] = ActionT[IO, Unit, Logger, A]
  object Actions extends ActionTSupport[IO, Unit, Logger] {
    def configuration: Action[Configuration] = ???
  }

  trait Configuration {
    def statsFile: File = ???
  }

  /** log a value, using the logger coming from the Reader environment */
  def log[R](r: R): Action[Unit] =
    Actions.ask.flatMap(logger => logger(r.toString).liftIO[Action])

  /**
   * This implicit allows any IO[result] to be used inside an example:
   *
   * "this should work" in {
   *   IO(success)
   * }
   */
  implicit def ioResultAsResult[T : AsResult]: AsResult[IO[T]] = new AsResult[IO[T]] {
    def asResult(io: =>IO[T]) = AsResult(io.unsafePerformIO())
  }

  /**
   * This implicit allows an IOAction[result] to be used inside an example.
   *
   * For example to read a database.
   *
   */
  implicit def ioActionResultAsResult[T : AsResult]: AsResult[Action[T]] = new AsResult[Action[T]] {
    def asResult(ioAction: =>Action[T]): Result =
      ioAction.execute(noLogging).unsafePerformIO.foldAll(
        ok        => AsResult(ok),
        fail      => org.specs2.execute.Failure(fail),
        throwable => org.specs2.execute.Error(throwable),
        (m, t)    => org.specs2.execute.Error(m, new ThrowableException(t))
      )
  }

  def beOk[T]: Matcher[Action[T]] = (action: Action[T]) =>
    AsResult(action.map(_ => org.specs2.execute.Success()))

  def beOk[T, R : AsResult](f: T => R): Matcher[Action[T]] = (action: Action[T]) =>
    AsResult(action.map(f))

  implicit class ioActionToProcess[T](action: Action[T]) {
    def toProcess = Process(action.toTask).eval
  }

  implicit class ioActionToTask[T](action: Action[T]) {
    def toTask = Task.delay(action.execute(noLogging).unsafePerformIO.toOption).map {
      case Some(a) => a
      case None    => throw new Exception("error")
    }
  }

  implicit class ioActionToOption[T](action: Action[T]) {
    def runOption = action.toTask.attemptRun.toOption
  }

}

