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
package zio

import zio.jdbc.Sql.Segment
import zio.stream._

import scala.language.implicitConversions

package object jdbc {

  /**
   * A special purpose type Alias representing a sql fragment that is not yet fully formed,nor mapped to concrete data type
   */
  type SqlFragment = Sql[ZResultSet]

  implicit def sqlInterpolator(sc: StringContext): SqlInterpolator = new SqlInterpolator(sc)

  /**
   * Converts a String into a pure SQL expression
   */
  implicit def stringToSql(s: String): SqlFragment = Sql(Chunk(Sql.Segment.Syntax(s)), identity)

  implicit def paramSegment[A](a: A)(implicit setter: Sql.ParamSetter[A]): Segment.Param =
    Segment.Param(a, setter.asInstanceOf[Sql.ParamSetter[Any]])

  implicit def nestedSqlSegment[A](sql: Sql[A]): Segment.Nested = Segment.Nested(sql)

  /**
   * Executes a SQL delete query.
   */
  def delete(sql: SqlFragment): ZIO[ZConnection, Throwable, Long] =
    ZIO.scoped(executeLargeUpdate(sql))

  /**
   * Executes a SQL statement, such as one that creates a table.
   */
  def execute(sql: SqlFragment): ZIO[ZConnection, Throwable, Unit] =
    ZIO.scoped(for {
      connection <- ZIO.service[ZConnection]
      _          <- connection.executeSqlWith(sql) { preparedStatement =>
                      ZIO.attempt(preparedStatement.executeUpdate())
                    }
    } yield ())

  /**
   * Performs an SQL insert query, returning a count of rows inserted and a
   * [[zio.Chunk]] of auto-generated keys. By default, auto-generated keys are
   * parsed and returned as `Chunk[Long]`. If keys are non-numeric, a
   * `Chunk.empty` is returned.
   */
  def insert(sql: SqlFragment): ZIO[ZConnection, Throwable, Long] =
    ZIO.scoped(executeLargeUpdate(sql))

  /**
   * Performs a SQL select query, returning all results in a chunk.
   */
  def selectAll[A](sql: Sql[A]): ZIO[ZConnection, Throwable, Chunk[A]] =
    ZIO.scoped(for {
      zrs   <- executeQuery(sql)
      chunk <- ZIO.attempt {
                 val builder = ChunkBuilder.make[A]()
                 while (zrs.next())
                   builder += sql.decode(zrs)
                 builder.result()
               }
    } yield chunk)

  /**
   * Performs a SQL select query, returning the first result, if any.
   */
  def selectOne[A](sql: Sql[A]): ZIO[ZConnection, Throwable, Option[A]] =
    ZIO.scoped(for {
      zrs    <- executeQuery(sql)
      option <- ZIO.attempt {
                  if (zrs.next()) Some(sql.decode(zrs)) else None
                }
    } yield option)

  /**
   * Performs a SQL select query, returning a stream of results.
   */
  def selectStream[A](sql: Sql[A]): ZStream[ZConnection, Throwable, A] =
    ZStream.unwrapScoped {
      for {
        zrs   <- executeQuery(sql)
        stream = ZStream.fromZIOOption(ZIO.attempt {
                   if (zrs.next()) Some(sql.decode(zrs)) else None
                 }.some)
      } yield stream
    }

  /**
   * A new transaction, which may be applied to ZIO effects that require a
   * connection in order to execute such effects in the transaction.
   */
  val transaction: ZLayer[ZConnectionPool, Throwable, ZConnection] =
    ZLayer(ZIO.serviceWith[ZConnectionPool](_.transaction)).flatten

  /**
   * Performs a SQL update query, returning a count of rows updated.
   */
  def update(sql: SqlFragment): ZIO[ZConnection, Throwable, Long] =
    ZIO.scoped(executeLargeUpdate(sql))

  private def executeQuery[A](sql: Sql[A]) = for {
    connection <- ZIO.service[ZConnection]
    zrs        <- connection.executeSqlWith(sql) { preparedStatement =>
                    ZIO.acquireRelease {
                      ZIO.attempt(ZResultSet(preparedStatement.executeQuery()))
                    }(_.close)
                  }
  } yield zrs

  private def executeLargeUpdate[A](sql: Sql[A]) = for {
    connection <- ZIO.service[ZConnection]
    count      <- connection.executeSqlWith(sql) { preparedStatement =>
                    ZIO.attempt(preparedStatement.executeLargeUpdate())
                  }
  } yield count
}
