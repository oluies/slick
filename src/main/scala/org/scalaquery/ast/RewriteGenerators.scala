package org.scalaquery.ast

import OptimizerUtil._
import collection.mutable.HashMap
import org.scalaquery.ql.{ConstColumn, RawNamedColumn, AbstractTable}
import org.scalaquery.ast.RewriteGenerators.ReplaceSelectContext

/**
 * Rewrite all generators to return exactly the required fields.
 */
object RewriteGenerators {

  def apply(tree: Node): Node = memoized[Node, Node](r => {
    //case fs @ RealFilterChain(filterSyms, Bind(_, _, _)) =>
    //TODO push additional columns needed by the filters into the Bind (without rewriting the existing ones)
    case b @ Bind(gen, from, what) if (what match {
      case Pure(StructNode(_)) => false // generated by rewriting a FilteredJoin, so skip it
      case _ => true
    }) =>
      rewriteGenerator(b, gen, from).nodeMapChildren(r)
    case j @ FilteredJoin(leftGen, rightGen, left, right, _, _) =>
      rewriteGenerator(rewriteGenerator(j, rightGen, right).nodeMapChildren(r), leftGen, left).nodeMapChildren(r)
    case j @ BaseJoin(leftGen, rightGen, left, right, _) =>
      rewriteGenerator(rewriteGenerator(j, rightGen, right).nodeMapChildren(r), leftGen, left).nodeMapChildren(r)
    case n => n.nodeMapChildren(r)
  })(tree)

  def rewriteGenerator(b: Node, gen: Symbol, from: Node): Node = {
    val rewrite = collectRewriteSymbols(gen, from)
    println("*** rewrite: "+rewrite)
    val refsMap = collectReferences(b, rewrite).iterator.map(n => (n, new AnonSymbol)).toMap
    val struct = refsMap.iterator.map{ case (n,s) => (s,n) }.toIndexedSeq
    //println("*** refs for "+allSyms+": "+refsMap.keys)
    //struct.dump("*** struct: ")

    val (withNewSelect, replacementMap) = {
      val isTableBased = findSelect(from) match {
        case AbstractTable(_) => true
        case Pure(TableRef(_)) => true
        case x =>
          x.dump("*** not table-based: ")
          false
      }
      println("*** isTableBased: "+isTableBased)
      println("*** keys: "+refsMap.keys)
      StructNode(struct).dump("*** struct: ")
      if(isTableBased && refsMap.keys.forall(_.isInstanceOf[RawNamedColumn])) {
        // only column refs -> rewrite directly
        (b, refsMap.map { case (r: RawNamedColumn, _) => (r: Node, r.symbol) })
      } else (b.nodeMapChildren(n => if(n eq from) replaceSelect(from, struct, rewrite, new ReplaceSelectContext) else n), refsMap)
    }
    withNewSelect.dump("*** withNewSelect: ")
    println("*** replacementMap: "+replacementMap)
    val rr = replaceReferences(withNewSelect, rewrite, replacementMap, Set.empty)
    rr.dump("*** rewritten: ")
    rr
  }

  def collectRewriteSymbols(s: Symbol, n: Node): Set[Symbol] = n match {
    case FilteredQuery(gen, from) => collectRewriteSymbols(gen, from) + s
    case _ => Set(s)
  }

  def collectReferences(n: Node, syms: Set[Symbol]): Set[Node] = n match {
    case InRef(sym, what) if syms contains sym => Set(what)
    case n => n.nodeChildren.map(ch => collectReferences(ch, syms)).flatten.toSet
  }

  def replaceReferences(n: Node, syms: Set[Symbol], repl: Map[Node, Symbol], parents: Set[Symbol]): Node = n match {
    case ir @ InRef(sym, what) if syms contains sym =>
      //println("*** matched InRef("+sym+", "+what+") with matching sym")
      repl.get(what) match {
        case Some(sym2) =>
          if(parents contains sym) {
            //TODO aliasing to another column in the same struct -> we should prevent this earlier
            //println("*** NOT replacing with Path("+sym+", "+sym2+") at parents "+parents)
            replaceReferences(what, syms, repl, parents)
          } else {
            //println("*** replacing with Path("+sym+", "+sym2+") at parents "+parents)
            Path(sym, sym2)
          }
        case None =>
          replaceReferences(what, syms, repl, parents)
      }
    case d: DefNode =>
      d.nodeMapScopedChildren{ case (symO, ch) => replaceReferences(ch, syms, repl, parents ++ symO.toSet) }
    case n => n.nodeMapChildren(ch => replaceReferences(ch, syms, repl, parents))
  }

  def findSelect(in: Node): Node = in match {
    case f: FilteredQuery => findSelect(f.from)
    case b @ Bind(_, _, Pure(_)) => b.from
    case b @ Bind(_, _, nonPure) => findSelect(nonPure)
    case n => n
  }

  class ReplaceSelectContext(val keepExisting: Boolean = false, var indices: Option[Seq[Int]] = None)

  def replaceSelect(in: Node, struct: IndexedSeq[(Symbol, Node)], genChain: Set[Symbol], ctx: ReplaceSelectContext): Node = in match {
    case f: FilteredQuery => f.nodeMapFrom(n => replaceSelect(n, struct, genChain, ctx))
    case b @ Bind(_, _, p @ Pure(_)) => b.copy(select = replaceSelect(p, struct, genChain, ctx))
    case b @ Bind(gen, _, nonPure) => b.copy(select = replaceSelect(nonPure, struct, genChain, ctx))
    case t @ AbstractTable(_) => //TODO support keepExisting and useIndices
      val gen = new AnonSymbol
      val rewrapped = StructNode(struct.map { case (s,n) => (s, rewrap(n, genChain.iterator.map(s => (s, gen)).toMap, gen)) })
      rewrapped.dump("*** actual replacement: ")
      Bind(gen, t, Pure(rewrapped))
    case f @ FilteredJoin(leftGen, rightGen, _, _, jt, on) => //TODO support keepExisting and useIndices
      val gen = new AnonSymbol
      StructNode(struct).dump("*** struct: ")
      val rewrapMap = genChain.iterator.map(s => (s, gen)).toMap + (leftGen -> leftGen) + (rightGen -> rightGen)
      val rewrapped = StructNode(struct.map { case (s,n) => (s, rewrap(n, rewrapMap, gen)) })
      println("*** genChain: "+genChain)
      rewrapped.dump("*** replacement for FilteredJoin: ")
      Bind(gen, f, Pure(rewrapped))
    case Pure(what) =>
      if(ctx.keepExisting) {
        val cols: IndexedSeq[Node] = what match {
          case ProductNode(cs) => cs.toIndexedSeq
          case n => IndexedSeq(n)
        }
        ProductNode(cols: _*).dump("*** keeping existing in: ")
        StructNode(struct).dump("*** columns to find: ")
        //TODO support keepExisting and useIndices
        sys.error("not implemented")
        Pure(StructNode(struct))
      } else Pure(StructNode(struct))
    case u @ Union(left, right, _, leftGen, rightGen) =>
      println("*** replacing Union of "+leftGen+", "+rightGen)
      println("*** genChain: "+genChain)
      val unionCtx = new ReplaceSelectContext(true)
      val lr = replaceSelect(left, struct.map{ case (s,n) => (s, n.unwrap(Set(leftGen))) }, genChain, unionCtx)
      val rr = replaceSelect(right, struct, genChain, unionCtx)
      u.copy(left = lr, right = rr)
  }

  def rewrap(n: Node, wrappers: Map[Symbol, Symbol], newWrapper: Symbol): Node = n match {
    case c @ RawNamedColumn(_, _, _) => Path(newWrapper, c.symbol)
    case InRef(sym, what) =>
      if(wrappers.keySet contains sym) rewrap(what, wrappers, wrappers(sym))
      else rewrap(what, wrappers, newWrapper)
    case n => n.nodeMapChildren(ch => rewrap(ch, wrappers, newWrapper))
  }
}
