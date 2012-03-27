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
package com.precog.daze

import com.precog.common.{Path, VectorCase}
import com.precog.yggdrasil._

import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.dispatch.ExecutionContext
import akka.dispatch.Future
import akka.util.duration._

import blueeyes.json.JsonAST._
import blueeyes.json.JsonParser

import scala.io.Source

import scalaz._
import scalaz.std.AllInstances._
import scalaz.effect._
import scalaz.iteratee._
import scalaz.syntax.monad._
import Function._
import IterateeT._
import Validation._

object StubOperationsAPI {
  import akka.dispatch.ExecutionContext

  val actorSystem = ActorSystem("stub_operations_api")
  implicit val asyncContext: akka.dispatch.ExecutionContext = ExecutionContext.defaultExecutionContext(actorSystem)
}

trait StubOperationsAPI 
    extends StorageEngineQueryComponent
    with IterableDatasetOpsComponent { self =>
  type YggConfig <: DatasetConsumersConfig with EvaluatorConfig with YggEnumOpsConfig with IterableDatasetOpsConfig

  implicit val asyncContext = StubOperationsAPI.asyncContext
  
  import ops._
  
  object query extends QueryAPI {
    val chunkSize = 2000
  }

  trait QueryAPI extends StorageEngineQueryAPI[Dataset] {
    private var pathIds = Map[Path, Int]()
    private var currentId = 0

    def chunkSize: Int
    
    private case class StubDatasetMask(userUID: String, path: Path, selector: Vector[Either[Int, String]], valueType: Option[SType]) extends DatasetMask[Dataset] {
      def derefObject(field: String): DatasetMask[Dataset] = copy(selector = selector :+ Right(field))
      def derefArray(index: Int): DatasetMask[Dataset] = copy(selector = selector :+ Left(index))
      def typed(tpe: SType): DatasetMask[Dataset] = copy(valueType = Some(tpe))
      
      def realize(expiresAt: Long): Dataset[SValue] = {
        fullProjection(userUID, path, expiresAt) collect unlift(mask)
      }
      
      private def mask(sv: SValue): Option[SValue] = {
        val masked = selector.foldLeft(Option(sv)) {
          case (None, _) => None
          case (Some(SObject(obj)), Right(field)) => obj get field
          case (Some(SArray(arr)),  Left(index)) => arr.lift(index)
          case _ => None
        }

        masked.filter(v => valueType.forall(v.isA))
      }
      
      // TODO merge with Evaluator impl
      private def unlift[A, B](f: A => Option[B]): PartialFunction[A, B] = new PartialFunction[A, B] {
        def apply(a: A) = f(a).get
        def isDefinedAt(a: A) = f(a).isDefined
      }
    }
    
    def fullProjection(userUID: String, path: Path, expiresAt: Long): Dataset[SValue] = 
      IterableDataset(1, new Iterable[(Identities, SValue)] { def iterator = readJSON(path) })
    
    def mask(userUID: String, path: Path): DatasetMask[Dataset] = StubDatasetMask(userUID, path, Vector(), None)
    
    private def readJSON(path: Path): Iterator[SEvent] = {
      val src = Source.fromInputStream(getClass getResourceAsStream path.elements.mkString("/", "/", ".json"))
      val stream = Stream from 0 map scaleId(path) zip (src.getLines map parseJSON toStream) map tupled(wrapSEvent)
      //Iteratee.enumPStream[X, Vector[SEvent], IO](stream.grouped(chunkSize).map(Vector(_: _*)).toStream)
      stream.iterator
    }
    
    private def scaleId(path: Path)(seed: Int): Long = {
      val scalar = synchronized {
        if (!(pathIds contains path)) {
          pathIds += (path -> currentId)
          currentId += 1
        }
        
        pathIds(path)
      }
      
      (scalar.toLong << 32) | seed
    }
    
    private def parseJSON(str: String): JValue =
      JsonParser parse str
    
    private def wrapSEvent(id: Long, value: JValue): SEvent =
      (VectorCase(id), SValue.fromJValue(value))
  }
}

// vim: set ts=4 sw=4 et:
