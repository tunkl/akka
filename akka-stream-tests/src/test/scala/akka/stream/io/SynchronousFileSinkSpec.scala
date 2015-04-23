/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.File

import akka.actor.{ ActorCell, ActorSystem, RepointableActorRef }
import akka.stream.scaladsl.Source
import akka.stream.testkit.StreamTestKit._
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings, ActorOperationAttributes }
import akka.util.{ ByteString, Timeout }

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

class SynchronousFileSinkSpec extends AkkaSpec(StreamTestKit.UnboundedMailboxConfig) {

  val settings = ActorFlowMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorFlowMaterializer(settings)

  val TestLines = {
    val b = ListBuffer[String]()
    b.append("a" * 1000 + "\n")
    b.append("b" * 1000 + "\n")
    b.append("c" * 1000 + "\n")
    b.append("d" * 1000 + "\n")
    b.append("e" * 1000 + "\n")
    b.append("f" * 1000 + "\n")
    b.toList
  }

  val TestByteStrings = TestLines.map(ByteString(_))

  "SynchronousFile Sink" must {
    "write lines to a file" in checkThatAllStagesAreStopped {
      targetFile { f ⇒
        val completion = Source(TestByteStrings)
          .runWith(SynchronousFileSink(f))

        val size = Await.result(completion, 3.seconds)
        size should equal(6006)
        checkFileContents(f, TestLines.mkString(""))
      }
    }

    "by default write into existing file" in checkThatAllStagesAreStopped {
      targetFile { f ⇒
        def write(lines: List[String]) =
          Source(lines)
            .map(ByteString(_))
            .runWith(SynchronousFileSink(f))

        val completion1 = write(TestLines)
        Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val written2 = Await.result(completion2, 3.seconds)

        written2 should ===(lastWrite.flatten.length)
        checkFileContents(f, lastWrite.mkString("") + TestLines.mkString("").drop(100))
      }
    }

    "allow appending to file" in checkThatAllStagesAreStopped {
      targetFile { f ⇒
        def write(lines: List[String] = TestLines) =
          Source(lines)
            .map(ByteString(_))
            .runWith(SynchronousFileSink(f, append = true))

        val completion1 = write()
        val written1 = Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val written2 = Await.result(completion2, 3.seconds)

        f.length() should ===(written1 + written2)
        checkFileContents(f, TestLines.mkString("") + lastWrite.mkString("") + "\n")
      }
    }

    "use dedicated file-io-dispatcher by default" in checkThatAllStagesAreStopped {
      targetFile { f ⇒
        val sys = ActorSystem("dispatcher-testing", StreamTestKit.UnboundedMailboxConfig)
        val mat = ActorFlowMaterializer()(sys)
        implicit val timeout = Timeout(3.seconds)

        try {
          Source(() ⇒ Iterator.continually(TestByteStrings.head)).runWith(SynchronousFileSink(f))(mat)

          val ref = Await.result(sys.actorSelection("/user/$a/flow-1-2*").resolveOne(), timeout.duration)
          ref.asInstanceOf[RepointableActorRef].underlying.asInstanceOf[ActorCell].dispatcher.id should ===("akka.stream.default-file-io-dispatcher")
        } finally shutdown(sys)
      }
    }

    "allow overriding the dispatcher using OperationAttributes" in checkThatAllStagesAreStopped {
      targetFile { f ⇒
        val sys = ActorSystem("dispatcher-testing", StreamTestKit.UnboundedMailboxConfig)
        val mat = ActorFlowMaterializer()(sys)
        implicit val timeout = Timeout(3.seconds)

        try {
          Source(() ⇒ Iterator.continually(TestByteStrings.head))
            .to(SynchronousFileSink(f))
            .withAttributes(ActorOperationAttributes.dispatcher("akka.actor.default-dispatcher"))
            .run()(mat)

          val ref = Await.result(sys.actorSelection("/user/$a/flow-1-2*").resolveOne(), timeout.duration)
          ref.asInstanceOf[RepointableActorRef].underlying.asInstanceOf[ActorCell].dispatcher.id should ===("akka.actor.default-dispatcher")
        } finally shutdown(sys)
      }
    }

  }

  private def targetFile(block: File ⇒ Unit) {
    val targetFile = File.createTempFile("synchronous-file-sink", ".tmp")
    try block(targetFile) finally targetFile.delete()
  }

  def checkFileContents(f: File, contents: String): Unit = {
    val s = scala.io.Source.fromFile(f)
    val out = s.getLines().mkString("\n") + "\n"
    s.close()
    out should ===(contents)
  }

}

