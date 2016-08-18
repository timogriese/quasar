package blueeyes
package json
package serialization

import scalaz._, Scalaz._
import ExtractorDecomposer.by

/** Decomposes the value into a JSON object.
  */
trait Decomposer[A] { self =>
  def decompose(tvalue: A): JValue

  def contramap[B](f: B => A): Decomposer[B] = new Decomposer[B] {
    override def decompose(b: B) = self.decompose(f(b))
  }

  def apply(tvalue: A): JValue = decompose(tvalue)

  def unproject(jpath: JPath): Decomposer[A] = new Decomposer[A] {
    override def decompose(b: A) = JUndefined.unsafeInsert(jpath, self.decompose(b))
  }
}

object Decomposer {
  def apply[A](implicit d: Decomposer[A]): Decomposer[A] = d
}

class ExtractorDecomposer[A](toJson: A => JValue, fromJson: JValue => Validation[Extractor.Error, A]) extends Extractor[A] with Decomposer[A] {
  def decompose(x: A)      = toJson(x)
  def validated(x: JValue) = fromJson(x)
}
object ExtractorDecomposer {
  def makeOpt[A, B](fg: A => B, gf: B => Validation[Extractor.Error, A])(implicit ez: Extractor[B], dz: Decomposer[B]): ExtractorDecomposer[A] = {
    new ExtractorDecomposer[A](
      v => dz decompose fg(v),
      j => ez validated j flatMap gf
    )
  }

  def make[A, B](fg: A => B, gf: B => A)(implicit ez: Extractor[B], dz: Decomposer[B]): ExtractorDecomposer[A] = {
    new ExtractorDecomposer[A](
      v => dz decompose fg(v),
      j => ez validated j map gf
    )
  }

  def by[A] = new {
    def apply[B](fg: A => B)(gf: B => A)(implicit ez: Extractor[B], dz: Decomposer[B]): ExtractorDecomposer[A] = make[A, B](fg, gf)
    def opt[B](fg: A => B)(gf: B => Validation[Extractor.Error, A])(implicit ez: Extractor[B], dz: Decomposer[B]): ExtractorDecomposer[A] =
      makeOpt[A, B](fg, gf)
  }
}

trait MiscSerializers {
  import DefaultExtractors._, DefaultDecomposers._
  import SerializationImplicits._

  implicit val JPathExtractorDecomposer: ExtractorDecomposer[JPath] =
    ExtractorDecomposer.by[JPath].opt(x => JString(x.toString): JValue)(_.validated[String] map (JPath(_)))

  implicit val InstantExtractorDecomposer  = by[Instant](_.getMillis)(instant fromMillis _)
  implicit val DurationExtractorDecomposer = by[Duration](_.getMillis)(duration fromMillis _)
  implicit val UuidExtractorDecomposer     = by[UUID](_.toString)(uuid)
}

/** Serialization implicits allow a convenient syntax for serialization and
  * deserialization when implicit decomposers and extractors are in scope.
  * <p>
  * foo.serialize
  * <p>
  * jvalue.deserialize[Foo]
  */
trait SerializationImplicits extends MiscSerializers {
  case class DeserializableJValue(jvalue: JValue) {
    def deserialize[T](implicit e: Extractor[T]): T                            = e.extract(jvalue)
    def validated[T](implicit e: Extractor[T]): Validation[Extractor.Error, T] = e.validated(jvalue)
    def validated[T](jpath: JPath)(implicit e: Extractor[T])                   = e.validated(jvalue, jpath)
  }

  case class SerializableTValue[T](tvalue: T) {
    def serialize(implicit d: Decomposer[T]): JValue = d.decompose(tvalue)
    def jv(implicit d: Decomposer[T]): JValue        = d.decompose(tvalue)
  }

  implicit def JValueToTValue[T](jvalue: JValue): DeserializableJValue = DeserializableJValue(jvalue)
  implicit def TValueToJValue[T](tvalue: T): SerializableTValue[T]     = SerializableTValue[T](tvalue)
}

object SerializationImplicits extends SerializationImplicits

/** Bundles default extractors, default decomposers, and serialization
  * implicits for natural serialization of core supported types.
  */
object DefaultSerialization extends DefaultExtractors with DefaultDecomposers with SerializationImplicits {
  implicit val DateTimeExtractorDecomposer =
    by[DateTime].opt(x => JNum(x.getMillis): JValue)(_.validated[Long] map (dateTime fromMillis _))
}

// when we want to serialize dates as ISO8601 not as numbers
object Iso8601Serialization extends DefaultExtractors with DefaultDecomposers with SerializationImplicits {
  import Extractor._
  implicit val TZDateTimeExtractorDecomposer =
    by[DateTime].opt(d => JString(dateTime showIso d): JValue) {
      case JString(dt) => (Thrown.apply _) <-: Validation.fromTryCatchNonFatal(dateTime fromIso dt)
      case _           => Failure(Invalid("Date time must be represented as JSON string"))
    }
}
