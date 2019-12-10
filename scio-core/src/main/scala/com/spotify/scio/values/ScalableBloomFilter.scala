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

import java.io.{DataInputStream, DataOutputStream, InputStream, OutputStream}

import com.google.common.hash.{Funnel, BloomFilter => gBloomFilter}
import com.spotify.scio.values.ScalableBloomFilter.Nel

import scala.collection.mutable

@SerialVersionUID(1L)
final case class ScalableBloomFilter[T] private (
  private val params: ScalableBloomFilter.SBFParams,
  private val filters: Nel[gBloomFilter[T]]
) extends ApproxFilter[T] {

  override type Param = ScalableBloomFilter.SBFParams

  override type Typeclass[_] = Funnel[T]

  import params._

  /**
   * Check if the filter may contain a given element.
   */
  override def mayBeContains(t: T): Boolean = filters.exists(_.mightContain(t))

  def numFilters: Int = filters.size // Explain why min in always 1

  def approximateElementCount: Long = filters.map(_.approximateElementCount()).sum

  /**
   * Add more elements, and create a new [[ScalableBloomFilter]]
   */
  def putAll(
    moreElements: Iterable[T]
  )(implicit funnel: Funnel[T]): ScalableBloomFilter[T] = {
    val initial: ScalableBloomFilter[T] = this
    import initial.params._

    var numInsertedInCurrentFilter = initial.filters.head.approximateElementCount()
    var currentCapacity = initial.params.initialCapacity * (growthRate * initial.numFilters)
    var currentFpProb = initial.params.fpProb * (tighteningRatio * initial.numFilters)

    val it = moreElements.iterator
    // create a copy
    val newFilters = initial.filters.to[mutable.ListBuffer]

    while (it.hasNext) {
      while (it.hasNext && numInsertedInCurrentFilter < currentCapacity) {
        newFilters.head.put(it.next())
        numInsertedInCurrentFilter += 1
      }

      if (it.hasNext) {
        // We have more elements to insert
        currentCapacity *= growthRate
        currentFpProb *= tighteningRatio
        numInsertedInCurrentFilter = 0
        val f = gBloomFilter.create[T](funnel, currentCapacity, currentFpProb)
        newFilters.insert(0, f)
      }
    }

    initial.copy(
      filters = new Nel(newFilters.head, newFilters.tail.toList)
    )
  }

  /**
   * Serialize the filter to the given [[OutputStream]]
   */
  override def writeTo(out: OutputStream): Unit = {
    // Serial form:
    // fpProb, initialCapacity, growthRate, tighteningRatio
    // N the number of BloomFilters in this ScalableBloomFilter
    // The N BloomFilters.
    val dout = new DataOutputStream(out)
    dout.writeDouble(fpProb)
    dout.writeInt(initialCapacity)
    dout.writeInt(growthRate)
    dout.writeDouble(tighteningRatio)
    dout.writeInt(filters.size)
    filters.foreach(_.writeTo(dout))
  }
}

object ScalableBloomFilter extends ApproxFilterCompanion[ScalableBloomFilter] {

  case class SBFParams(fpProb: Double,
                       initialCapacity: Int,
                       growthRate: Int,
                       tighteningRatio: Double)

  // Type alias a Non Empty List
  private type Nel[A] = ::[A]

  /**
   * An implicit deserializer available when we know a Funnel instance for the
   * Filter's type.
   *
   * A deserialization doesn't require specifying any parameters like `fpProb`
   * and `numElements` and hence is available as in implicit.
   */
  override def readFrom[T](in: InputStream)(implicit tc: Funnel[T]): ScalableBloomFilter[T] = {
    val din = new DataInputStream(in)

    val fpProb = din.readDouble()
    val initialCapacity = din.readInt()
    val growthRate = din.readInt()
    val tighteningRatio = din.readDouble()
    val numFilters = din.readInt()
    val filters =
      (1 to numFilters).map(_ => gBloomFilter.readFrom[T](in, implicitly[Funnel[T]])).toList

    ScalableBloomFilter[T](
      SBFParams(fpProb, initialCapacity, growthRate, tighteningRatio),
      new Nel(filters.head, filters.tail) // This is a NonEmptyList
    )
  }

  def apply[T](param: SBFParams)(
    implicit tc: Funnel[T]): ScalableBloomFilterBuilder[T] =
    apply(param.fpProb, param.initialCapacity, param.growthRate, param.tighteningRatio)

  def apply[T: Funnel](
    fpProb: Double,
    initialCapacity: Int,
    growthRate: Int,
    tighteningRatio: Double
  ): ScalableBloomFilterBuilder[T] =
    ScalableBloomFilterBuilder(
      fpProb,
      initialCapacity,
      growthRate,
      tighteningRatio
    )

  def empty[T: Funnel](
    fpProb: Double,
    initialCapacity: Int,
    growthRate: Int,
    tighteningRatio: Double
  ): ScalableBloomFilter[T] = ScalableBloomFilter(
    SBFParams(fpProb, initialCapacity, growthRate, tighteningRatio),
    new Nel(gBloomFilter.create[T](implicitly[Funnel[T]], initialCapacity, fpProb), Nil)
  )

  override def apply[T](param: SBFParams, items: Iterable[T])(
    implicit tc: Funnel[T]): ScalableBloomFilter[T] =
    empty(param.fpProb, param.initialCapacity, param.growthRate, param.tighteningRatio)
      .putAll(items)
}

final case class ScalableBloomFilterBuilder[T: Funnel] private[values] (
  fpProb: Double,
  initialCapacity: Int,
  growthRate: Int,
  tighteningRatio: Double
) extends ApproxFilterBuilder[T, ScalableBloomFilter] {
  override def build(iterable: Iterable[T]): ScalableBloomFilter[T] =
    // create an empty Filter and then add all the elements to that.
    ScalableBloomFilter
      .empty(
        fpProb,
        initialCapacity,
        growthRate,
        tighteningRatio
      )
      .putAll(iterable)
}
