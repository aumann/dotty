
import scala.quoted._

import scala.tasty.Universe
import scala.tasty.util.TastyPrinter

class MyMap[Keys](private val underlying: Array[Int]) extends AnyVal {
  def get[K <: String](implicit i: Index[K, Keys]): Int = underlying(i.index)
  def set[K <: String](value: Int)(implicit i: Index[K, Keys]): Unit = underlying(i.index) = value
}

object MyMap {
  def create[Keys](implicit s: Size[Keys]): MyMap[Keys] = new MyMap[Keys](new Array[Int](s.size))
}

trait Size[Keys] { def size: Int }
object Size {
  def apply[Keys](v: Int): Size[Keys] = new Size { def size = v }
  implicit val unit: Size[Unit] = Size(0)
  implicit def cons[H, T](implicit s: Size[T]): Size[(H, T)] = Size(s.size + 1)
}

class Index[K, Keys](val index: Int) extends AnyVal
object Index {

  implicit def zero[K, T]: Index[K, (K, T)] = new Index(0)

  implicit inline def succ[K, H, T](implicit prev: => Index[K, T]): Index[K, (H, T)] = ~succImpl(Universe.compilationUniverse)('[K], '[H], '[T])

  def succImpl[K, H, T](u: Universe)(implicit k: Type[K], h: Type[H], t: Type[T]): Expr[Index[K, (H, T)]] = {
    import u._
    import u.tasty._

    def name(tp: TypeOrBounds): String = tp match {
      case Type.ConstantType(Constant.String(str)) => str
    }

    def names(tp: TypeOrBounds): List[String] = tp match {
      case Type.AppliedType(_, x1 :: x2 :: Nil) => name(x1) :: names(x2)
      case _ => Nil
    }

    val key = name(k.toTasty.tpe)
    val keys = name(h.toTasty.tpe) :: names(t.toTasty.tpe)

    val index = keys.indexOf(key)

    '(new Index(~index.toExpr))
  }
}
