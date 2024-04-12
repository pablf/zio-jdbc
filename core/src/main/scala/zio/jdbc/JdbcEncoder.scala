/*
 * Copyright 2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zio.jdbc

import zio.{Chunk, Unsafe}
import zio.schema.{ Derive, Schema, StandardType }
import zio.schema.Deriver._

/**
 * A type class that describes the ability to convert a value of type `A` into
 * a fragment of SQL. This is useful for forming SQL insert statements.
 */
trait JdbcEncoder[-A] {
  def encode(value: A): SqlFragment

  final def contramap[B](f: B => A): JdbcEncoder[B] = value => encode(f(value))
}

object JdbcEncoder extends JdbcEncoder0LowPriorityImplicits {
  def apply[A](implicit encoder: JdbcEncoder[A]): JdbcEncoder[A] = encoder

  trait Deriver extends zio.schema.Deriver[JdbcEncoder] {
        def deriveRecord[A](record: Schema.Record[A], fields: => Chunk[WrappedF[JdbcEncoder, _]], summoned: => Option[JdbcEncoder[A]]): JdbcEncoder[A] =
            Unsafe.unsafe { implicit unsafe =>
                value => record.deconstruct(value).zip(fields).map {
                    case (field, encoder) => encoder.unwrap.asInstanceOf[JdbcEncoder[Any]].encode(field)
                }.reduce(_ ++ SqlFragment.comma ++ _)
            }


        def deriveEnum[A](`enum`: Schema.Enum[A], cases: => Chunk[WrappedF[JdbcEncoder, _]], summoned: => Option[JdbcEncoder[A]]): JdbcEncoder[A] =
            value => {
                val encoder = (for {
                    case (c: Schema.Case[A, _], encoder: WrappedF[JdbcEncoder, _]) <- `enum`.cases.zip(cases)
                    if c.isCase(value)
                } yield encoder).head
                encoder.unwrap.asInstanceOf[JdbcEncoder[A]].encode(value)
            }

        def derivePrimitive[A](st: StandardType[A], summoned: => Option[JdbcEncoder[A]]): JdbcEncoder[A] =
            st match {
                case StandardType.StringType     => JdbcEncoder.stringEncoder
                case StandardType.BoolType       => JdbcEncoder.booleanEncoder
                case StandardType.ShortType      => JdbcEncoder.shortEncoder
                case StandardType.IntType        => JdbcEncoder.intEncoder
                case StandardType.LongType       => JdbcEncoder.longEncoder
                case StandardType.FloatType      => JdbcEncoder.floatEncoder
                case StandardType.DoubleType     => JdbcEncoder.doubleEncoder
                case StandardType.CharType       => JdbcEncoder.charEncoder
                case StandardType.BigIntegerType => JdbcEncoder.bigIntEncoder
                case StandardType.BinaryType     => JdbcEncoder.byteChunkEncoder
                case StandardType.BigDecimalType => JdbcEncoder.bigDecimalEncoder
                case StandardType.UUIDType       => JdbcEncoder.uuidEncoder
                // TODO: Standard Types which are missing are the date time types, not sure what would be the best way to handle them
                case _                           => throw JdbcEncoderError(s"Failed to encode schema ${st}", new IllegalArgumentException)
            }

        // TODO: review for cases like Option of a tuple
        def deriveOption[A](option: Schema.Optional[A], inner: => JdbcEncoder[A], summoned: => Option[JdbcEncoder[Option[A]]]): JdbcEncoder[Option[A]] =
            value => value.fold(SqlFragment.nullLiteral)(inner.encode)

        def deriveSequence[C[_], A](
            sequence: Schema.Sequence[C[A], A, _],
            inner: => JdbcEncoder[A],
            summoned: => Option[JdbcEncoder[C[A]]]
        ): JdbcEncoder[C[A]] =
            value => {
                val seq = sequence.toChunk(value).map(inner.encode)
                SqlFragment.lparen ++ seq.reduce( _ ++ SqlFragment.comma ++ _ ) ++ SqlFragment.rparen
            }

        def deriveMap[K, V](
            map: Schema.Map[K, V],
            key: => JdbcEncoder[K],
            value: => JdbcEncoder[V],
            summoned: => Option[JdbcEncoder[Map[K, V]]]
        ): JdbcEncoder[Map[K, V]] = 
            throw JdbcEncoderError(s"Failed to encode schema ${map}", new IllegalArgumentException)

        def deriveTransformedRecord[A, B](
            record: Schema.Record[A],
            transform: Schema.Transform[A, B, _],
            fields: => Chunk[WrappedF[JdbcEncoder, _]],
            summoned: => Option[JdbcEncoder[B]]
        ): JdbcEncoder[B] =
            deriveRecord(record, fields, None).contramap(transform.g.andThen(_.getOrElse(throw JdbcEncoderError(s"Failed to encode schema ${record}", new IllegalArgumentException))))
    }
  
  implicit val deriver: JdbcEncoder.Deriver = new Deriver {}

  implicit val intEncoder: JdbcEncoder[Int]                               = value => sql"$value"
  implicit val longEncoder: JdbcEncoder[Long]                             = value => sql"$value"
  implicit val doubleEncoder: JdbcEncoder[Double]                         = value => sql"$value"
  implicit val charEncoder: JdbcEncoder[Char]                             = value => sql"$value"
  implicit val stringEncoder: JdbcEncoder[String]                         = value => sql"$value"
  implicit val booleanEncoder: JdbcEncoder[Boolean]                       = value => sql"$value"
  implicit val bigIntEncoder: JdbcEncoder[java.math.BigInteger]           = value => sql"$value"
  implicit val bigDecimalEncoder: JdbcEncoder[java.math.BigDecimal]       = value => sql"$value"
  implicit val bigDecimalEncoderScala: JdbcEncoder[scala.math.BigDecimal] = value => sql"$value"
  implicit val shortEncoder: JdbcEncoder[Short]                           = value => sql"$value"
  implicit val floatEncoder: JdbcEncoder[Float]                           = value => sql"$value"
  implicit val byteEncoder: JdbcEncoder[Byte]                             = value => sql"$value"
  implicit val byteArrayEncoder: JdbcEncoder[Array[Byte]]                 = value => sql"$value"
  implicit val byteChunkEncoder: JdbcEncoder[Chunk[Byte]]                 = value => sql"$value"
  implicit val blobEncoder: JdbcEncoder[java.sql.Blob]                    = value => sql"$value"
  implicit val uuidEncoder: JdbcEncoder[java.util.UUID]                   = value => sql"$value"

  implicit def singleParamEncoder[A: SqlFragment.Setter]: JdbcEncoder[A] = value => sql"$value"

  // TODO: review for cases like Option of a tuple
  def optionEncoder[A](implicit encoder: JdbcEncoder[A]): JdbcEncoder[Option[A]] =
    value => value.fold(SqlFragment.nullLiteral)(encoder.encode)

  implicit def tuple2Encoder[A: JdbcEncoder, B: JdbcEncoder]: JdbcEncoder[(A, B)] =
    tuple => JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(tuple._2)

  implicit def tuple3Encoder[A: JdbcEncoder, B: JdbcEncoder, C: JdbcEncoder]: JdbcEncoder[(A, B, C)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3)

  implicit def tuple4Encoder[A: JdbcEncoder, B: JdbcEncoder, C: JdbcEncoder, D: JdbcEncoder]
    : JdbcEncoder[(A, B, C, D)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      )

  implicit def tuple5Encoder[A: JdbcEncoder, B: JdbcEncoder, C: JdbcEncoder, D: JdbcEncoder, E: JdbcEncoder]
    : JdbcEncoder[(A, B, C, D, E)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5)

  implicit def tuple6Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      )

  implicit def tuple7Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7)

  implicit def tuple8Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      )

  implicit def tuple9Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9)

  implicit def tuple10Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      )

  implicit def tuple11Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11)

  implicit def tuple12Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      )

  implicit def tuple13Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13)

  implicit def tuple14Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      )

  implicit def tuple15Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15)

  implicit def tuple16Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      )

  implicit def tuple17Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder,
    Q: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      ) ++ SqlFragment.comma ++ JdbcEncoder[Q].encode(tuple._17)

  implicit def tuple18Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder,
    Q: JdbcEncoder,
    R: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      ) ++ SqlFragment.comma ++ JdbcEncoder[Q].encode(tuple._17) ++ SqlFragment.comma ++ JdbcEncoder[R].encode(
        tuple._18
      )

  implicit def tuple19Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder,
    Q: JdbcEncoder,
    R: JdbcEncoder,
    S: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      ) ++ SqlFragment.comma ++ JdbcEncoder[Q].encode(tuple._17) ++ SqlFragment.comma ++ JdbcEncoder[R].encode(
        tuple._18
      ) ++ SqlFragment.comma ++ JdbcEncoder[S].encode(tuple._19)

  implicit def tuple20Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder,
    Q: JdbcEncoder,
    R: JdbcEncoder,
    S: JdbcEncoder,
    T: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      ) ++ SqlFragment.comma ++ JdbcEncoder[Q].encode(tuple._17) ++ SqlFragment.comma ++ JdbcEncoder[R].encode(
        tuple._18
      ) ++ SqlFragment.comma ++ JdbcEncoder[S].encode(tuple._19) ++ SqlFragment.comma ++ JdbcEncoder[T].encode(
        tuple._20
      )

  implicit def tuple21Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder,
    Q: JdbcEncoder,
    R: JdbcEncoder,
    S: JdbcEncoder,
    T: JdbcEncoder,
    U: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      ) ++ SqlFragment.comma ++ JdbcEncoder[Q].encode(tuple._17) ++ SqlFragment.comma ++ JdbcEncoder[R].encode(
        tuple._18
      ) ++ SqlFragment.comma ++ JdbcEncoder[S].encode(tuple._19) ++ SqlFragment.comma ++ JdbcEncoder[T].encode(
        tuple._20
      ) ++ SqlFragment.comma ++ JdbcEncoder[U].encode(tuple._21)

  implicit def tuple22Encoder[
    A: JdbcEncoder,
    B: JdbcEncoder,
    C: JdbcEncoder,
    D: JdbcEncoder,
    E: JdbcEncoder,
    F: JdbcEncoder,
    G: JdbcEncoder,
    H: JdbcEncoder,
    I: JdbcEncoder,
    J: JdbcEncoder,
    K: JdbcEncoder,
    L: JdbcEncoder,
    M: JdbcEncoder,
    N: JdbcEncoder,
    O: JdbcEncoder,
    P: JdbcEncoder,
    Q: JdbcEncoder,
    R: JdbcEncoder,
    S: JdbcEncoder,
    T: JdbcEncoder,
    U: JdbcEncoder,
    V: JdbcEncoder
  ]: JdbcEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
    tuple =>
      JdbcEncoder[A].encode(tuple._1) ++ SqlFragment.comma ++ JdbcEncoder[B].encode(
        tuple._2
      ) ++ SqlFragment.comma ++ JdbcEncoder[C].encode(tuple._3) ++ SqlFragment.comma ++ JdbcEncoder[D].encode(
        tuple._4
      ) ++ SqlFragment.comma ++ JdbcEncoder[E].encode(tuple._5) ++ SqlFragment.comma ++ JdbcEncoder[F].encode(
        tuple._6
      ) ++ SqlFragment.comma ++ JdbcEncoder[G].encode(tuple._7) ++ SqlFragment.comma ++ JdbcEncoder[H].encode(
        tuple._8
      ) ++ SqlFragment.comma ++ JdbcEncoder[I].encode(tuple._9) ++ SqlFragment.comma ++ JdbcEncoder[J].encode(
        tuple._10
      ) ++ SqlFragment.comma ++ JdbcEncoder[K].encode(tuple._11) ++ SqlFragment.comma ++ JdbcEncoder[L].encode(
        tuple._12
      ) ++ SqlFragment.comma ++ JdbcEncoder[M].encode(tuple._13) ++ SqlFragment.comma ++ JdbcEncoder[N].encode(
        tuple._14
      ) ++ SqlFragment.comma ++ JdbcEncoder[O].encode(tuple._15) ++ SqlFragment.comma ++ JdbcEncoder[P].encode(
        tuple._16
      ) ++ SqlFragment.comma ++ JdbcEncoder[Q].encode(tuple._17) ++ SqlFragment.comma ++ JdbcEncoder[R].encode(
        tuple._18
      ) ++ SqlFragment.comma ++ JdbcEncoder[S].encode(tuple._19) ++ SqlFragment.comma ++ JdbcEncoder[T].encode(
        tuple._20
      ) ++ SqlFragment.comma ++ JdbcEncoder[U].encode(tuple._21) ++ SqlFragment.comma ++ JdbcEncoder[V].encode(
        tuple._22
      )
}

trait JdbcEncoder0LowPriorityImplicits { self =>

  import zio.schema.Factory
  import zio.schema.Factory._

  def fromSchema[A: Factory](implicit schema: Schema[A]): JdbcEncoder[A] =
    implicitly[Factory[A]].derive[JdbcEncoder](JdbcEncoder.deriver)

}
