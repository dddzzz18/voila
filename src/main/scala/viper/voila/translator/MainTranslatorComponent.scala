/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.translator

import viper.silver.ast.{Field, FieldAccess, FieldAccessPredicate, FuncApp}
import viper.voila.frontend._
import viper.silver.{ast => vpr}

trait MainTranslatorComponent { this: PProgramToViperTranslator =>
  val returnVarName = "ret"

  val diamondField: Field =
    vpr.Field("$diamond", vpr.Int)()

  def diamondLocation(rcvr: vpr.Exp): FieldAccess =
    vpr.FieldAccess(rcvr, diamondField)()

  def diamondAccess(rcvr: vpr.Exp): FieldAccessPredicate =
    vpr.FieldAccessPredicate(diamondLocation(rcvr), vpr.FullPerm()())()

  val intSet: FuncApp =
    vpr.FuncApp.apply("IntSet", Vector.empty)(vpr.NoPosition, vpr.NoInfo, vpr.SetType(vpr.Int), Vector.empty)

  val natSet: FuncApp =
    vpr.FuncApp("NatSet", Vector.empty)(vpr.NoPosition, vpr.NoInfo, vpr.SetType(vpr.Int), Vector.empty)

  def tmpVar(typ: PType): vpr.LocalVarDecl =
    vpr.LocalVarDecl(s"tmp_$typ", translateNonVoid(typ))()

  def tmpVars(tree: VoilaTree): Vector[vpr.LocalVarDecl] =
    tree.root.regions map (region => tmpVar(semanticAnalyser.typ(region.state)))

  def translate(tree: VoilaTree): vpr.Program = {
    val members: Vector[vpr.Member] = (
         heapLocations(tree)
      ++ Vector(diamondField)
      ++ (tree.root.regions flatMap translate)
      ++ (tree.root.predicates map translate)
      ++ (tree.root.procedures map translate)
    )

    val tmpVarDecls = tmpVars(tree)

    val fields = members collect { case f: vpr.Field => f }
    val predicates = members collect { case p: vpr.Predicate => p }
    val functions = members collect { case f: vpr.Function => f }

    val methods =
      members collect { case m: vpr.Method =>
        m.copy(_locals = tmpVarDecls ++ m.locals)(m.pos, m.info)
      }

    vpr.Program(
      domains = Nil,
      fields = fields,
      functions = functions,
      predicates = predicates,
      methods = methods
    )()
  }

  def translate(member: PMember): vpr.Member =
    member match {
      case r: PRegion => translate(r)
      case p: PPredicate => translate(p)
      case p: PProcedure => translate(p)
    }

  def translate(predicate: PPredicate): vpr.Predicate = {
    vpr.Predicate(
      name = predicate.id.name,
      formalArgs = predicate.formalArgs map translate,
      _body = Some(translate(predicate.body))
    )()
  }

  def translate(procedure: PProcedure): vpr.Method = {
    val formalReturns =
      procedure.typ match {
        case PVoidType() => Nil
        case other => Seq(vpr.LocalVarDecl(returnVarName, translateNonVoid(other))())
      }

    val pres = (
         procedure.pres.map(translate)
      ++ procedure.inters.map(translate)
    )

    vpr.Method(
      name = procedure.id.name,
      formalArgs = procedure.formalArgs map translate,
      formalReturns = formalReturns,
      _pres = pres,
      _posts = procedure.posts map translate,
      _locals = procedure.locals map translate,
      _body = vpr.Seqn(procedure.body map translate)()
    )()
  }

  def translate(declaration: PFormalArgumentDecl): vpr.LocalVarDecl =
    vpr.LocalVarDecl(declaration.id.name, translateNonVoid(declaration.typ))()

  def translate(interference: PInterferenceClause): vpr.AnySetContains = {
    /* TODO: Use correct type */
    val lv = vpr.LocalVar(interference.variable.name)(typ = vpr.Int)
    val set = translate(interference.set)

    vpr.AnySetContains(lv, set)()
  }

  def translate(statement: PStatement): vpr.Stmt = statement match {
    case PBlock(stmts) =>
      vpr.Seqn(stmts map translate)()
    case PIf(cond, thn, els) =>
      vpr.If(translate(cond), translate(thn), translate(els))()
    case PWhile(cond, body) =>
      vpr.While(translate(cond), Nil, Nil, translate(body))()
    case PAssign(lhs, rhs) =>
      /* TODO: Use correct type */
      vpr.LocalVarAssign(vpr.LocalVar(lhs.name)(typ = vpr.Int), translate(rhs))()
    case read: PHeapRead =>
      translate(read)
    case write: PHeapWrite =>
      translate(write)
    case stmt @ PPredicateAccess(predicate, arguments) =>
      val vprArguments =
        semanticAnalyser.entity(predicate) match {
          case _: PredicateEntity => arguments map translate
          case _: RegionEntity => arguments.init map translate
        }

      val loc =
        vpr.PredicateAccess(
          args = vprArguments,
          predicateName = predicate.name
        )(vpr.NoPosition, vpr.NoInfo)

      val acc =
        vpr.PredicateAccessPredicate(
          loc = loc,
          perm = vpr.FullPerm()()
        )()

      stmt match {
        case _: PFold => vpr.Fold(acc)()
        case _: PUnfold => vpr.Unfold(acc)()
      }
    case stmt: PMakeAtomic => translate(stmt)
    case stmt: PUpdateRegion => translate(stmt)
  }

  def translate(expression: PExpression): vpr.Exp = expression match {
    case PTrueLit() => vpr.TrueLit()()
    case PFalseLit() => vpr.FalseLit()()
    case PIntLit(n) => vpr.IntLit(n)()
    case PEquals(left, right) => vpr.EqCmp(translate(left), translate(right))()
    case PAnd(left, right) => vpr.And(translate(left), translate(right))()
    case POr(left, right) => vpr.Or(translate(left), translate(right))()
    case PNot(operand) => vpr.Not(translate(operand))()
    case PLess(left, right) => vpr.LtCmp(translate(left), translate(right))()
    case PAtMost(left, right) => vpr.LeCmp(translate(left), translate(right))()
    case PGreater(left, right) => vpr.GtCmp(translate(left), translate(right))()
    case PAtLeast(left, right) => vpr.GeCmp(translate(left), translate(right))()
    case PAdd(left, right) => vpr.Add(translate(left), translate(right))()
    case PSub(left, right) => vpr.Sub(translate(left), translate(right))()
    case PConditional(cond, thn, els) => vpr.CondExp(translate(cond), translate(thn), translate(els))()
    case PExplicitSet(elements) => vpr.ExplicitSet(elements map translate)()
    case PIntSet() => intSet
    case PNatSet() => natSet

    case pointsTo: PPointsTo => translate(pointsTo)

    case PPredicateExp(id, args) =>
      semanticAnalyser.entity(id) match {
        case _: PredicateEntity =>
          vpr.PredicateAccess(args map translate, id.name)(vpr.NoPosition, vpr.NoInfo)

        case RegionEntity(decl) =>
          translateUseOf(decl, args)

        case other =>
          sys.error(s"Not yet supported: $other")
      }

    case PIdnExp(id) => translateUseOf(id)
    case guard: PGuardExp => translate(guard)
  }

  def translateUseOf(id: PIdnNode): vpr.Exp = {
    semanticAnalyser.entity(id) match {
      case entity: LogicalVariableEntity => translateUseOf(entity.declaration)
      case ArgumentEntity(decl) => vpr.LocalVar(id.name)(typ = translateNonVoid(decl.typ))
      case LocalVariableEntity(decl) => vpr.LocalVar(id.name)(typ = translateNonVoid(decl.typ))
    }
  }

  def translate(declaration: PLocalVariableDecl): vpr.LocalVarDecl =
    vpr.LocalVarDecl(declaration.id.name, translateNonVoid(declaration.typ))()

  def translateNonVoid(typ: PType): vpr.Type = typ match {
    case PIntType() => vpr.Int
    case PBoolType() => vpr.Bool
    case PSetType(elementType) => vpr.SetType(translateNonVoid(elementType))
    case PRefType(_) => vpr.Ref
    case PRegionIdType() => vpr.Ref
    case unsupported@(_: PVoidType | _: PUnknownType) =>
      sys.error(s"Cannot translate type '$unsupported'")
  }
}