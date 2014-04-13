import Filter.Fragments
import scalaz.concurrent.Task
import scalaz._, Scalaz._
import scalaz.stream.Process
import scalaz.stream.Process._

object Fold {



  trait Fold[T] {
    type S
    def sink: Sink[Task, T]
    def fold: (S, T) => S
    def init: S
    def last(s: S): Task[Unit]
  }

  object Fold {
    def fromSink[T](aSink: Sink[Task, T]) =  new Fold[T] {
      type S = Unit
      lazy val sink: Sink[Task, T] = aSink
      def fold = (u: Unit, fragment: T) => ()
      def init = ()
      def last(u: Unit) = Task(())
    }

    def unitSink[T] = Process((t: T) => Task(())).toSource.repeat
    def unit[T] = fromSink(unitSink[T])

  }



  implicit class zipFolds[T](val fold1: Fold[T]) {
    def *(fold2: Fold[T]) = new Fold[T] {
      type S = (fold1.S, fold2.S)

      def sink = fold1.sink.zipWith(fold2.sink) {
        (f1: T => Task[Unit], f2: T => Task[Unit]) =>
        (t: T) => f1(t) >> f2(t)
      }

      def fold = (s12: (fold1.S, fold2.S), t: T) =>
        (fold1.fold(s12._1, t), fold2.fold(s12._2, t))

      def last(s12: (fold1.S, fold2.S)) =
        fold1.last(s12._1) >> fold2.last(s12._2)

      def init = (fold1.init, fold2.init)
    }
  }



  implicit def foldMonoid[T] = new Monoid[Fold[T]] {
    def append(fold1: Fold[T], fold2: =>Fold[T]): Fold[T] =
      fold1 * fold2

    val zero = Fold.unit[T]
  }

  def foldState[S, T](action: (S, T) => S)
                     (init: S): Process1[T, S] = {
    def go(state: S): Process1[T, S] =
      Process.receive1 { t: T =>
        val newState = action(state, t)
        emit(newState) fby go(newState)
      }

    go(init)
  }

  def foldState[T](fold: Fold[T]): Process1[T, fold.S] = {
    def go(state: fold.S): Process1[T, fold.S] =
      Process.receive1 { t: T =>
        val newState = fold.fold(state, t)
        emit(newState) fby go(newState)
      }

    go(fold.init)
  }

  val console: Fold[Fragment] = ???
  val fragments: Fragments = ???

  val last: Task[console.S] =
    logged(fragments)
      .drainW(console.sink)
      .pipe(foldState(console))
      .runLastOr(console.init)

  val finish = last.map(console.last)

}
