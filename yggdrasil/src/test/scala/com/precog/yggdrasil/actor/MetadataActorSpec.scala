/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package actor 

import metadata._
import com.precog.common._
import com.precog.util._

import blueeyes.json.JPath
import blueeyes.concurrent.test._
import blueeyes.json.xschema.Extractor._

import java.io.File

import org.specs2.mutable._

import akka.pattern.ask
import akka.actor.{Actor, ActorSystem, Props}
import akka.dispatch._
import akka.util._

import scala.collection.immutable.ListMap
import scala.collection.GenTraversableOnce

import scalaz.{Success, Validation}
import scalaz.effect._
import scalaz.syntax.std.optionV._

object MetadataActorSpec extends Specification with FutureMatchers {

  val system = ActorSystem("shard_metadata_test")
  implicit val timeout = Timeout(30000) 

  "shard metadata actor" should {
    "correctly propagates initial message clock on flush request" in {
      val testActor = system.actorOf(Props(new TestMetadataActor), "test-metadata-actor1")
      val captureActor = system.actorOf(Props(new CaptureActor), "test-capture-actor1") 
      
      val result = for {
        _ <- testActor ? FlushMetadata(captureActor)
        r <- (captureActor ? GetCaptureResult).mapTo[(Vector[SaveMetadata], Vector[Any])]
      } yield r

      result must whenDelivered {
        beLike {
          case (save, other) =>
            other.size must_== 0
            save must_== Vector(SaveMetadata(Map(), VectorClock.empty, None))
        }
      }
    }

    "correctly propagates updated message clock on flush request" in {
      val testActor = system.actorOf(Props(new TestMetadataActor), "test-metadata-actor2")
      val captureActor = system.actorOf(Props(new CaptureActor), "test-capture-actor2") 

      val colDesc = ColumnDescriptor(Path("/"), JPath(".test"), CStringArbitrary, Authorities(Set("me")))

      val indexedColumns = ListMap((colDesc -> 0))
      val sorting = Vector((colDesc -> ById))

      val descriptor = ProjectionDescriptor(indexedColumns, sorting).toOption.get
      val values = Vector[CValue](CString("Test123"))
      val metadata = Vector(Set[Metadata]())

      val row1 = ProjectionInsert.Row(EventId(0,1), values, metadata)
      val row2 = ProjectionInsert.Row(EventId(0,2), values, metadata)

      testActor ! IngestBatchMetadata(Map(descriptor -> ProjectionMetadata.columnMetadata(descriptor, Seq(row1, row2))), VectorClock.empty.update(0, 1).update(0, 2), Some(0l))

      val result = for {
        _ <- testActor ? FlushMetadata(captureActor) 
        r <- (captureActor ? GetCaptureResult).mapTo[(Vector[SaveMetadata], Vector[Any])]
      } yield r

      result must whenDelivered {
        beLike {
          case (save, other) =>
            val stringStats = StringValueStats(2, "Test123", "Test123")

            val resultingMetadata = Map(
              (descriptor -> Map[ColumnDescriptor, MetadataMap]((colDesc -> Map((stringStats.metadataType -> stringStats)))))
            )

            other.size must_== 0
            save must_== Vector(SaveMetadata(resultingMetadata, VectorClock.empty.update(0,2), Some(0L)))
        }
      }
    }
  }

  step {
    system.shutdown
  }
}

class TestMetadataActor extends MetadataActor("TestMetadataActor", new TestMetadataStorage(Map()), CheckpointCoordination.Noop)

class TestMetadataStorage(data: Map[ProjectionDescriptor, ColumnMetadata]) extends MetadataStorage {
  def currentMetadata(desc: ProjectionDescriptor): IO[Validation[Error, MetadataRecord]] = IO {
    data.get(desc).map(MetadataRecord(_, VectorClock.empty)).toSuccess(Invalid("Metadata doesn't exist for " + desc))
  }

  def updateMetadata(desc: ProjectionDescriptor, metadata: MetadataRecord): IO[Validation[Throwable, Unit]] = IO {
    Success(())
  }

  def findDescriptorRoot(desc: ProjectionDescriptor): Option[File] = None
  
  def findDescriptors(f: ProjectionDescriptor => Boolean): Set[ProjectionDescriptor] = 
    data.keySet.filter(f)

  def flatMapDescriptors[T](f: ProjectionDescriptor => GenTraversableOnce[T]): Seq[T] = 
    data.keySet.toSeq.flatMap(f)
}

case object GetCaptureResult

class CaptureActor extends Actor {
  var saveMetadataCalls = Vector[SaveMetadata]()
  var otherCalls = Vector[Any]()

  def receive = {
    case sm : SaveMetadata => 
      saveMetadataCalls = saveMetadataCalls :+ sm
      sender ! ()

    case GetCaptureResult => 
      sender ! ((saveMetadataCalls, otherCalls))

    case other                   => 
      otherCalls = otherCalls :+ other
  }
}
