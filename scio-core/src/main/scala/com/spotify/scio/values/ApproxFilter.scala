/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.values
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}

import com.spotify.scio.annotations.experimental
import com.spotify.scio.coders.Coder
import org.apache.beam.sdk.coders.AtomicCoder

/**
 * An [[ApproxFilter]] is an abstraction over various Approximate / Probabilistic
 * data structures used for checking membership of elements.
 *
 * This trait defines read-only immutable filters. The filters are primarily aimed
 * to be used as singleton [[SideInput]]s in Scio pipelines. The filters can be
 * created from SCollection / Iterables using the various intermediate data structures
 * / algorithms defined using the [[ApproxFilterBuilder]] interface.
 *
 * Constructors for [[ApproxFilter]] are defined using [[ApproxFilterBuilder]]
 * and deserializers are defined using [[ApproxFilterDeserializer]].
 *
 * For example usage see [[BloomFilter]]
 */
@experimental
trait ApproxFilter[-T] extends Serializable {
  /**
   * Check if the filter may contain a given element.
   */
  def mayBeContains(t: T): Boolean

  /**
   * Serialize the Filter to an Array[Byte]
   *
   * The serialized bytes should be used to persist the filter
   * and deserialize using [[ApproxFilter#fromBytes]] in the companion
   * object.
   */
  def toBytes: Array[Byte] = {
    val ba = new ByteArrayOutputStream()
    writeTo(ba)
    ba.toByteArray
  }

  /**
   * Serialize the filter to the given [[OutputStream]]
   *
   * Deserializers are defined by [[ApproxFilterDeserializer]] available as an implicit
   * in the [[ApproxFilterCompanion]] object.
   */
  def writeTo(out: OutputStream): Unit

  /**
   * The serialized size of the filter in bytes.
   */
  def sizeInBytes: Int = toBytes.length
}

/**
 * An `ApproxFilterBuilder[T, To]` is used to create [[ApproxFilter]] of type [[To]]
 * from various source collections which contain elements of type [T]
 *
 * These are implemented for each ApproxFilter and are used for creating the filters.
 * Different instances of an [[ApproxFilterBuilder]] are available via constructors
 * in the [[ApproxFilter]]'s companion object. The constructor can require multiple
 * runtime parameters and configurations like expected insertions / false positive
 * probabilities to define a builder. Hence a Builder is not available as an implicit.
 * However the constructors might summon other implicit type class instances before
 * providing a Builder.
 */
@experimental
trait ApproxFilterBuilder[T, To[B >: T] <: ApproxFilter[B]] extends Serializable {
  /**
   * The name of this builder.
   * This name shows up nicely as a transform name for the pipeline.
   */
  def name: String = this.getClass.getSimpleName

  /** Build from an Iterable */
  def build(it: Iterable[T]): To[T]

  /**
   * Build a `SCollection[To[T]]` from an SCollection[T]
   *
   * By default groups all elements and builds the [[To]]
   */
  def build(
    sc: SCollection[T]
  )(implicit coder: Coder[T], filterCoder: Coder[To[T]]): SCollection[To[T]] =
    sc.transform(name)(
      _.distinct
        .groupBy(_ => ())
        .values
        .map(build)
    )
}

/**
 * Defines how to deserialize an [[ApproxFilter]].
 *
 * This is defined as in implicit in the companion object of the [[ApproxFilter]]
 * A deserializer doesn't need any configurations / parameters from the user to
 * deserialize an [[ApproxFilter]]. Since this is available as an implicit, it might
 * summon other implicit type class instances before being able to successfully provide
 * a deserializer instance.
 */
@experimental
trait ApproxFilterDeserializer[T, To[_] <: ApproxFilter[_]] extends Serializable {
  /**
   * Read from serialized bytes to this filter.
   *
   * Serialization is done using `ApproxFilter[T]#toBytes`
   */
  def fromBytes(serializedBytes: Array[Byte]): To[T] =
    readFrom(new ByteArrayInputStream(serializedBytes))

  /**
   * Deserialize a [[ApproxFilter]] from an [[InputStream]]
   *
   * Serialization is done using `ApproxFilter[T]#writeTo`
   */
  def readFrom(in: InputStream): To[T]
}

/**
 * This trait provides helpers to the [[ApproxFilter]] companion object.
 *
 * This allows the user to directly user the [[ApproxFilter]] to deserialize from
 * an `InputStream` or `Array[Byte]`
 *
 * The implicit [[ApproxFilterDeserializer]] summons an instance of the deserializer.
 * While summoning the implicit, it might try to search for other implicit type classes.
 */
@experimental
trait ApproxFilterCompanion[AF[_] <: ApproxFilter[_]] {
  /**
   * Read from serialized bytes to this filter.
   *
   * Serialization is done using `ApproxFilter[T]#toBytes`
   *
   * The deserialization logic is defined implicitly using a [[ApproxFilterDeserializer]]
   */
  def fromBytes[T](in: Array[Byte])(implicit deser: ApproxFilterDeserializer[T, AF]): AF[T] =
    deser.fromBytes(in)

  /**
   * Deserialize a [[ApproxFilter]] from an [[InputStream]]
   *
   * Serialization is done using `ApproxFilter[T]#writeTo`
   *
   * The deserialization logic is defined implicitly using a [[ApproxFilterDeserializer]]
   */
  def readFrom[T](in: InputStream)(implicit deser: ApproxFilterDeserializer[T, AF]): AF[T] =
    deser.readFrom(in)

  /**
   * [[Coder]] for [[ApproxFilter]]
   *
   * A coder can be created when we have an implicit [[ApproxFilterDeserializer]]
   *
   * An [[ApproxFilterDeserializer]] is defined in the companion object and might pull in
   * other implicit type classes as needed to create an [[ApproxFilter]]
   */
  implicit def coder[T](implicit deser: ApproxFilterDeserializer[T, AF]): Coder[AF[T]] = {
    Coder.beam {
      new AtomicCoder[AF[T]] {
        override def encode(value: AF[T], outStream: OutputStream): Unit = value.writeTo(outStream)
        override def decode(inStream: InputStream): AF[T] = deser.readFrom(inStream)
      }
    }
  }
}
