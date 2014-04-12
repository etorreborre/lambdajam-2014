import scalaz._
import Scalaz._
import stream.{Process, process1}
import Process._
import concurrent.Task

object Filter extends App {

  def filterByName(name: String): Process1[Fragment, Fragment] =
    process1.filter((f: Fragment) => f.matches(".*"+name+".*"))

  val action: Task[IndexedSeq[Fragment]] = {

    val fragments: Process[Task, Fragment] = Process(Fragment("ex1"), Fragment("ex2"), Fragment("ex3")).toSource
    val filtered: Process[Task, Fragment]  = fragments.pipe(filterByName("2"))

    filtered.runLog[Task, Fragment]
  }

  val result: Throwable \/ IndexedSeq[Fragment] = action.attemptRun

  result.fold(
    t    => println("Sorry: "+t),
    list => println("Yay: "  +list.mkString(", ")))
}

case class Fragment(name: String) {
  def matches(n: String) = name matches n
}