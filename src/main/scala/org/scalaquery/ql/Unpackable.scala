package org.scalaquery.ql

import org.scalaquery.ast.Node
import org.scalaquery.util.ValueLinearizer

/**
 * A packed value together with its unpacking
 */
case class Unpackable[T, U](value: T, unpack: Unpack[T, U]) {
  def endoMap(f: T => T): Unpackable[T, U] = {
    val fv = f(value)
    if(fv.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef]) this else new Unpackable(fv, unpack)
  }
  def reifiedNode = Node(unpack.reify(value))
  def reifiedUnpackable[R](implicit ev: Reify[T, R]): Unpackable[R, U] = Unpackable(unpack.reify(value).asInstanceOf[R], unpack.reifiedUnpack.asInstanceOf[Unpack[R, U]])
  def linearizer = unpack.linearizer(value).asInstanceOf[ValueLinearizer[U]]
  def mapOp(f: Node => Node) = unpack.mapOp(value, f).asInstanceOf[T]
  def zip[T2, U2](u2: Unpackable[T2, U2]) = new Unpackable[(T, T2), (U, U2)]((value, u2.value), Unpack.unpackTuple2(unpack, u2.unpack))
}

object Unpackable {
  // Should be implicit for using Unpackable as a view bound, but SI-3346 prevents this use case
  def unpackableValueToUnpackable[T, U](value: T)(implicit unpack: Unpack[T, U]) = Unpackable(value, unpack)
}

// Work-around for SI-3346
final class ToUnpackable[T](value: T) {
  def toUnpackable[U](implicit unpack: Unpack[T, U]) = new Unpackable[T, U](value, unpack)
}
