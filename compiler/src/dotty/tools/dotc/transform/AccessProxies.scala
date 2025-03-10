package dotty.tools.dotc
package transform

import core._
import Contexts.Context
import Symbols._
import Flags._
import Names._
import Decorators._
import TypeUtils._
import Annotations.Annotation
import Types._
import NameKinds.ClassifiedNameKind
import ast.Trees._
import util.Property
import util.Positions.Position

/** A utility class for generating access proxies. Currently used for
 *  inline accessors and protected accessors.
 */
abstract class AccessProxies {
  import ast.tpd._

  def getterName: ClassifiedNameKind
  def setterName: ClassifiedNameKind

  /** accessor -> accessed */
  private val accessedBy = newMutableSymbolMap[Symbol]

  /** The accessor definitions that need to be added to class `cls`
   *  As a side-effect, this method removes entries from the `accessedBy` map.
   *  So a second call of the same method will yield the empty list.
   */
  private def accessorDefs(cls: Symbol)(implicit ctx: Context): Iterator[DefDef] =
    for (accessor <- cls.info.decls.iterator; accessed <- accessedBy.remove(accessor)) yield
      polyDefDef(accessor.asTerm, tps => argss => {
        val accessRef = ref(TermRef(cls.thisType, accessed))
        val rhs =
          if (accessor.name.is(setterName) &&
              argss.nonEmpty && argss.head.nonEmpty) // defensive conditions
            accessRef.becomes(argss.head.head)
          else
            accessRef.appliedToTypes(tps).appliedToArgss(argss)
        rhs.withPos(accessed.pos)
      })

  /** Add all needed accessors to the `body` of class `cls` */
  def addAccessorDefs(cls: Symbol, body: List[Tree])(implicit ctx: Context): List[Tree] = {
    val accDefs = accessorDefs(cls)
    if (accDefs.isEmpty) body else body ++ accDefs
  }

  trait Insert {
    import ast.tpd._

    def needsAccessor(sym: Symbol)(implicit ctx: Context): Boolean

    /** A fresh accessor symbol */
    def newAccessorSymbol(accessed: Symbol, name: TermName, info: Type)(implicit ctx: Context): TermSymbol =
      ctx.newSymbol(accessed.owner.enclosingSubClass, name, Synthetic | Method,
                    info, coord = accessed.pos).entered

    /** Create an accessor unless one exists already, and replace the original
      *  access with a reference to the accessor.
      *
      *  @param reference    The original reference to the non-public symbol
      *  @param onLHS        The reference is on the left-hand side of an assignment
      */
    def useAccessor(reference: RefTree, onLHS: Boolean)(implicit ctx: Context): Tree = {

      def nameKind = if (onLHS) setterName else getterName
      val accessed = reference.symbol.asTerm

      def refersToAccessed(sym: Symbol) = accessedBy.get(sym) == Some(accessed)

      val accessorInfo =
        if (onLHS) MethodType(accessed.info :: Nil, defn.UnitType)
        else accessed.info.ensureMethodic
      val accessorName = nameKind(accessed.name)
      val accessorSymbol =
        accessed.owner.info.decl(accessorName).suchThat(refersToAccessed).symbol
          .orElse {
            val acc = newAccessorSymbol(accessed, accessorName, accessorInfo)
            accessedBy(acc) = accessed
            acc
          }

      { reference match {
          case Select(qual, _) => qual.select(accessorSymbol)
          case Ident(name) => ref(accessorSymbol)
        }
      }.withPos(reference.pos)
    }

    /** Replace tree with a reference to an accessor if needed */
    def accessorIfNeeded(tree: Tree)(implicit ctx: Context): Tree = tree match {
      case tree: RefTree if needsAccessor(tree.symbol) =>
        if (tree.symbol.isConstructor) {
          ctx.error("Implementation restriction: cannot use private constructors in inline methods", tree.pos)
          tree // TODO: create a proper accessor for the private constructor
        }
        else useAccessor(tree, onLHS = false)
      case Assign(lhs: RefTree, rhs) if needsAccessor(lhs.symbol) =>
        cpy.Apply(tree)(useAccessor(lhs, onLHS = true), List(rhs))
      case _ =>
        tree
    }
  }
}