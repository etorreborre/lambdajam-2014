import java.io.File
import org.specs2.execute.Result
import org.specs2.main.Arguments
import scalaz._
import Scalaz._
import scalaz.effect.IO
import stream.{Process, process1}
import Process._
import concurrent.Task
import control._
import io._

object Filter extends App {

  val examples: Process[Task, Fragment] =
    Process(Example("ex1"), Example("ex2"), Example("ex3")).toSource

  def filterByName(name: String): Process1[Fragment, Fragment] =
    process1.filter((f: Fragment) => f.matches(".*"+name+".*"))

  val filtered: Process[Task, Fragment]  =
    examples.pipe(filterByName("2"))

  val action: Task[IndexedSeq[Fragment]] =
    filtered.runLog[Task, Fragment]

  val result: Throwable \/ IndexedSeq[Fragment] =
    action.attemptRun

  result.fold(
    t    => println("Sorry: "+t),
    list => println("Yay: "  +list.mkString(", ")))

  type Fragments = Process[Task, Fragment]


  def withPrevious(store: StatsStore) = { fs: Fragments =>
    fs.flatMap { fragment =>
      eval(store.previousResult(fragment)
        .map(fragment.setPreviousResult).toTask)
    }
  }


  def filterFailed: Process1[Fragment, Fragment] =
    process1.filter((_: Fragment).previousFailed)


  def giveMeAnInt: Task[Int] =
    ???

  def task: Task[Int] =
    for {
      a <- giveMeAnInt
      b <- giveMeAnInt
    } yield a + b

  def readLines(file: File): IO[List[String]] = ???

  def readStats: Action[Stats] =
    for {
      conf  <- Actions.configuration
      file = conf.statsFile
      lines <-
        if (!file.exists)
          Actions.fail(s"the file $file is missing")
        else
          Actions.fromIO(readLines(file))
    } yield Stats.fromLines(lines)

  import Actions._

  implicit def fromReader[T](reader: Configuration => Action[T]): Action[T] =
    ???

  implicit def fromStatsFile[T](file: File => Action[T]): Action[T] =
    ???

  implicit def fromIO[T](io: IO[T]): Action[T] =
    ???

  type StatsFile = File

  def readStats1: Action[Stats] = { file: StatsFile =>
    if (!file.exists)
      fail(s"the file $file is missing")
    else
      readLines(file).map(Stats.fromLines): Action[Stats]
  }


  def example(fromArguments: Arguments => Example): Example =
    ???

  def exampleFromPrevious(store: StatsStore => Example): Example =
    ???

  val fragments: Process[Task, Fragment] = ???

  def execute: Process1[Fragment, Task[Fragment]] = ???

  def execute(seq: Boolean): Process1[Fragment, Task[Fragment]] =
    Process.receive1 { fragment: Fragment =>
      if (seq) emit(Task.now(fragment.execute))
      else     emit(Task1.start(fragment.execute))
    }

  (fragments |> execute).sequence(4): Fragments

  def executeS(barrier: Task[Result]): Process1[Fragment, Task[Fragment]] =
    Process.receive1 { fragment: Fragment =>
      lazy val executed = fragment.execute

      val newBarrier =
        if (fragment.isStep) {
          val result = barrier.run
          Task(result)
        } else barrier.map(_ and executed.result)

      emit(Task1.start(executed)) fby executeS(newBarrier)
    }
  type FragmentsTasks = Process[Task, Task[Fragment]]

  def executeOnline: Fragment => Fragments = { fragment =>
    fragment.continuation match {
      case Some(continue) =>
        emit(fragment).toSource fby
        executeAll(continue)

      case None =>
        emit(fragment).toSource
    }
  }

  def executeAll: Fragments => Fragments = { fragments: Fragments =>
    (fragments |> execute).sequence(4).flatMap(executeOnline)
  }

  type Sink[O] = Process[Task, O => Task[Unit]]

  def console: Sink[Fragment] = ???
  def html: Sink[Fragment] = ???


  def computeStats: Process1[Fragment, Stats] = ???

  val stats: Process[Task, Stats] =
    logged(fragments)
      .pipeO(computeStats)
      .drainW(console)

  val consolePrinter: Process[Task, Stats] =
    logged(fragments)
      .pipeO(computeStats)
      .drainW(console)

  val htmlPrinter: Process[Task, Stats] =
    logged(fragments)
      .pipeO(computeStats)
      .drainW(console)



  val finalStats: Task[Stats] =
    stats.runLastOr(Stats())

}

object Task1 {
  def start(f: Fragment): Task[Fragment] = ???
}
trait Stats
object Stats {
  def apply(): Stats = ???
  def fromLines(lines: List[String]): Stats = ???
}

trait StatsStore {
  def previousResult(fragment: Fragment): Action[Result] =
    ???
}



sealed trait Fragment {
  def matches(n: String): Boolean
  def previousFailed: Boolean
  def setPreviousResult(r: Result): Fragment
  def execute: Fragment = this
  def isStep = true
  def result: Result = org.specs2.execute.Success()
  def continuation: Option[Process[Task, Fragment]] = None
}
case class Example(name: String) extends Fragment {
  def matches(n: String) = name matches n
  def previousFailed: Boolean = false
  def setPreviousResult(r: Result) = this
}
case class Text(name: String) extends Fragment {
  def matches(n: String) = name matches n
  def previousFailed: Boolean = false
  def setPreviousResult(r: Result) = this
}