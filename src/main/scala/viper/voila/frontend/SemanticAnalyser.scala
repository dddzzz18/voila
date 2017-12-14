/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.frontend

import scala.collection.immutable.ListSet
import scala.language.{higherKinds, implicitConversions}
import org.bitbucket.inkytonik.kiama.==>
import org.bitbucket.inkytonik.kiama.attribution.{Attribution, Decorators}
import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.{id => _, _}
import org.bitbucket.inkytonik.kiama.util.Messaging._
import org.bitbucket.inkytonik.kiama.util.{Entity, MultipleEntity, UnknownEntity}

class SemanticAnalyser(tree: VoilaTree) extends Attribution {
  val symbolTable = new SymbolTable()
  import symbolTable._

  val decorators = new Decorators(tree)
  import decorators._

  lazy val errors: Messages =
    collectMessages(tree) {
      case decl: PIdnDef if entity(decl) == MultipleEntity() =>
        message(decl, s"${decl.name} is declared more than once")

      case use: PIdnUse =>
        use match {
          case tree.parent(PLocation(receiver, field)) if use eq field =>
            check(typeOfIdn(receiver)) {
              case PRefType(referencedTyp) =>
                check(entity(referencedTyp)) {
                  case StructEntity(struct) =>
                    message(
                      receiver,
                      s"Receiver $receiver does not have a field $field",
                      !struct.fields.exists(_.id.name == field.name))

                   case _ =>
                     message(receiver, s"Receiver $receiver is not of struct type")
                }
              case _ =>
                message(receiver, s"Receiver $receiver is not of reference type")
            }

          case _ =>
            message(use, s"${use.name} is not declared", entity(use) == UnknownEntity())
        }

      case exp: PExpression if typ(exp) == PUnknownType() =>
        message(exp, s"$exp could not be typed")

      case PProcedure(_, _, _, _, pres, posts, _, _, _) =>
        (pres.map(_.assertion) ++ posts.map(_.assertion))
          .flatMap (exp =>
            message(
              exp,
              s"Type error: expected $PBoolType(), but found ${typ(exp)}",
              !isCompatible(typ(exp), PBoolType())))

      case action: PAction =>
        message(
          action.to,
          s"Type error: expected ${PSetType(typ(action.from))} but found ${typ(action.to)}",
          !isCompatible(typ(action.to), PSetType(typ(action.from))))

      case PAssign(lhs, _) if !entity(lhs).isInstanceOf[LocalVariableEntity] =>
        message(lhs, s"Cannot assign to ${lhs.name}")

      case PHeapRead(localVariable, location) =>
        check(entity(localVariable)) {
          case LocalVariableEntity(localVariableDecl) =>
            val locationType = typeOfLocation(location)

            message(
              location,
              s"Type error: expected ${localVariableDecl.typ} but got $locationType",
              !isCompatible(locationType, localVariableDecl.typ))

          case other =>
            message(localVariable, s"Type error: expected a local variable, but found $other")
        }

      case PHeapWrite(location, rhs) =>
        val locationType = typeOfLocation(location)
        val rhsType = typ(rhs)

        message(
          location,
          s"Type error: expected $locationType but got $rhsType",
          !isCompatible(locationType, rhsType))

      case call: PProcedureCall =>
        checkUse(entity(call.procedure)) {
          case ProcedureEntity(decl) => (
               reportArgumentLengthMismatch(call, decl.id, decl.formalArgs.length, call.arguments.length)
            ++ call.arguments.zip(decl.formalArgs).flatMap { case (actual, formal) =>
                 message(
                   actual,
                   s"Type error: expected ${formal.typ} but got ${typ(actual)}",
                   !isCompatible(typ(actual), formal.typ))
               })

          case _ =>
            message(call.procedure, s"Cannot call ${call.procedure}")
        }

      case exp: PExpression => (
           message(
              exp,
              s"Type error: expected ${expectedType(exp)} but got ${typ(exp)}",
              !isCompatible(expectedType(exp), typ(exp)))
        ++
           check(exp) {
              case PIdnExp(id) =>
                checkUse(entity(id)) {
                  case _: ProcedureEntity =>
                    message(id, "Cannot refer to procedures directly")
                }

              case PPredicateExp(id, args) =>
                checkUse(entity(id)) {
                  case PredicateEntity(decl) =>
                    reportArgumentLengthMismatch(exp, decl.id, decl.formalArgs.length, args.length)

                  case RegionEntity(decl) =>
                    /* A region predicate has the following argument structure:
                     * one region id, several regular arguments, one optional out-argument.
-                    * Hence, the provided argument count should be formal arguments plus 1 or 2.
-                    */
                    val requiredArgCount = 1 + decl.formalArgs.length
                    val actualArgCount = args.length
                    val delta = actualArgCount - requiredArgCount
                    message(
                      id,
                        s"Wrong number of arguments for '${id.name}', got $actualArgCount "
                      + s"but expected $requiredArgCount or ${requiredArgCount + 1}",
                      delta != 0 && delta != 1)

                  case _ =>
                    message(id, s"Cannot call ${id.name} here")
                }
          })
    }

  private def reportArgumentLengthMismatch(offendingNode: PAstNode,
                                           callee: PIdnNode,
                                           formalArgCount: Int,
                                           actualArgCount: Int) = {

    message(
      offendingNode,
        s"Wrong number of arguments for '${callee.name}', got $actualArgCount "
      + s"but expected $formalArgCount",
      formalArgCount != actualArgCount)
  }

  /**
    * Are two types compatible?  If either of them are unknown then we
    * assume an error has already been raised elsewhere so we say they
    * are compatible with anything. Otherwise, the two types have to be
    * the same.
    */
  def isCompatible(t1: PType, t2: PType): Boolean = {
    (t1 == t2) ||
    (t1.isInstanceOf[PRefType] && t2.isInstanceOf[PNullType]) ||
    (t2.isInstanceOf[PRefType] && t1.isInstanceOf[PNullType]) ||
    (t1 == PUnknownType()) ||
    (t2 == PUnknownType())
  }

  /**
    * The entity defined by a defining occurrence of an identifier.
    * Defined by the context of the occurrence.
    */
  lazy val definedEntity: PIdnDef => Entity =
    attr {
      case tree.parent(p) =>
        p match {
          case decl: PStruct=> StructEntity(decl)
          case decl: PProcedure => ProcedureEntity(decl)
          case decl: PPredicate => PredicateEntity(decl)
          case decl: PRegion => RegionEntity(decl)
          case tree.parent.pair(decl: PGuardDecl, region: PRegion) => GuardEntity(decl, region)
          case decl: PFormalArgumentDecl => ArgumentEntity(decl)
          case decl: PLocalVariableDecl => LocalVariableEntity(decl)
          case decl: PLogicalVariableBinder => LogicalVariableEntity(decl)
          case _ => UnknownEntity()
        }
    }

  /**
    * The environment to use to lookup names at a node. Defined to be the
    * completed defining environment for the smallest enclosing scope.
    */
  lazy val env: PAstNode => Environment =
    attr {
      // At a scope-introducing node, get the final value of the
      // defining environment, so that all of the definitions of
      // that scope are present.
      case tree.lastChild.pair(_: PProgram | _: PMember, c) =>
        defenv(c)

      // Otherwise, ask our parent so we work out way up to the
      // nearest scope node ancestor (which represents the smallest
      // enclosing scope).
      case tree.parent(p) =>
        env(p)
    }

  /**
    * The environment containing bindings for things that are being
    * defined. Much of the power of this definition comes from the Kiama
    * `chain` method, which threads the attribute through the tree in a
    * depth-first left-to-right order. The `envin` and `envout` definitions
    * are used to (optionally) update attribute value as it proceeds through
    * the tree.
    */
  lazy val defenv : Chain[Environment] =
    chain(defenvin, defenvout)

  def defenvin(in: PAstNode => Environment): PAstNode ==> Environment = {
    case program: PProgram =>
      /* At the root, create a new environment with all top-level members and all guards.
       * The latter is for simplicity, but means that guard names must be globally unique.
       */

      val topLevelBindings = (
           program.regions.map(r => r.id.name -> RegionEntity(r))
        ++ program.structs.map(p => p.id.name -> StructEntity(p))
        ++ program.predicates.map(p => p.id.name -> PredicateEntity(p))
        ++ program.procedures.map(p => p.id.name -> ProcedureEntity(p)))

      val guardBindings =
        program.regions.flatMap(region =>
          region.guards.map(guard =>
            s"${guard.id.name}@${region.id.name}" -> GuardEntity(guard, region)))

      rootenv(topLevelBindings ++ guardBindings :_*)

    case procedure: PProcedure =>
      /* The special return value variable 'ret' is in scope of procedure bodies */

      val retDecl = PLocalVariableDecl(PIdnDef("ret"), procedure.typ)

      defineIfNew(enter(in(procedure)), "ret", LocalVariableEntity(retDecl))

    case bindingContext: PBindingContext with PScope =>
      /* A binding context that defines its own scope, such as a set comprehension
       * Set(?c | 0 < c), results in adding a new scope (inside the outer environment)
       * that contains the bound variable.
       */

      def add(binder: PLogicalVariableBinder): Environment =
        defineIfNew(enter(in(bindingContext)), binder.id.name, definedEntity(binder.id))

      bindingContext match {
        case PAction2(_, from, to) => add(from)
        case PAction3(_, from, _, _) => add(from)
        case PSetComprehension(qvar, _, _) => add(qvar)
      }

    case scope@(_: PScope) =>
      /* At a nested scope region, create a new empty scope inside the outer environment */
      enter(in(scope))
  }

  def defenvout(out: PAstNode => Environment): PAstNode ==> Environment = {
    /*
     *
     * [2017-12-14 Malte]:
     *   The analogous case in defenvin is needed, this one here isn't. I've no idea, why.
     */
    // case scope@(_: PScope) =>
    //   /* When leaving a nested scope region, remove the innermost scope from the environment */
    //   leave(out(scope))

    case idef: PIdnDef =>
      /* At a defining occurrence of an identifier, check to see if it's already
       * been defined in this scope. If so, change its entity to MultipleEntity,
       * otherwise use the entity appropriate for this definition.
       */
      idef match {
        case tree.parent(tree.parent(ctx: PBindingContext with PScope)) => out(idef)
        case _ => defineIfNew(out(idef), idef.name, definedEntity(idef))
      }
  }

  /**********************************************************
   *  TODO: Modify environment computation such that a PPredicateExp
   *          Region(a, ...)
   *        adds a binding from a to Region to the environment
   */

  /**
    * The program entity referred to by an identifier definition or use.
    */
  lazy val entity: PIdnNode => Entity =
    attr {
      case g @ tree.parent(PGuardExp(guardId, regionId)) if guardId eq g =>
        val region = usedWithRegion(regionId)
        val guardAtRegion = s"${guardId.name}@${region.id.name}"

        lookup(env(guardId), guardAtRegion, UnknownEntity())

      case n =>
        lookup(env(n), n.name, UnknownEntity())
    }


  lazy val usedWithRegion: PIdnNode => PRegion =
    attr(regionIdUsedWith(_)._1)

  lazy val usedWithRegionPredicate: PIdnNode => PPredicateExp =
    attr(regionIdUsedWith(_)._2.get.asInstanceOf[PPredicateExp])

  lazy val regionIdUsedWith: PIdnNode => (PRegion, Option[PPredicateAccess]) =
    attr(id => enclosingMember(id) match {
      case Some(scope) =>
        val collectRegionAccesses =
          collect[Vector, Option[(PRegion, Option[PPredicateAccess])]] {
            case exp @ PPredicateAccess(predicate, PIdnExp(`id`) +: _) =>
              entity(predicate) match {
                case RegionEntity(decl) => Some((decl, Some(exp)))
                case _ => None
              }

            case region: PRegion if region.regionId.id.name == id.name =>
              Some(region, None)
          }

        val regions = collectRegionAccesses(scope).flatten

        /* TODO: Reconsider what this method does and what it's supposed to do.
         *       In general, a region id can be used with multiple region
         *       assertions in which the other region arguments differ.
         *       Which region predicate access should be returned in such cases?
         */
//        assert( // Fails all the time ...
//          regions.length == 1,
//          s"Expected exactly one match, but found ${regions.length}: $regions")

        regions.head

      case None =>
        ???
    })

  def enclosingMember(of: PAstNode): Option[PMember] =
    enclosingMemberAttr(of)(of)

  private lazy val enclosingMemberAttr: PAstNode => PAstNode => Option[PMember] =
    paramAttr { of => {
      case member: PMember => Some(member)
      case tree.parent(p) => enclosingMemberAttr(of)(p)
    }}

  def enclosingMakeAtomic(of: PAstNode): PMakeAtomic =
    enclosingMakeAtomicAttr(of)(of)

  private lazy val enclosingMakeAtomicAttr: PAstNode => PAstNode => PMakeAtomic =
    paramAttr { of => {
      case makeAtomic: PMakeAtomic => makeAtomic
      case tree.parent(p) => enclosingMakeAtomicAttr(of)(p)
    }}

  def generalBindingContext(of: PLogicalVariableBinder): LogicalVariableContext =
    generalContextAttr(of.id)(of)

  def generalUsageContext(of: PIdnNode): LogicalVariableContext =
    generalContextAttr(of)(of)

  private lazy val generalContextAttr: PIdnNode => PAstNode => LogicalVariableContext =
    paramAttr { of => {
      case _: PInterferenceClause => LogicalVariableContext.Interference
      case _: PPreconditionClause => LogicalVariableContext.Precondition
      case _: PPostconditionClause => LogicalVariableContext.Postcondition
      case _: PInvariantClause => LogicalVariableContext.Invariant
      case _: PProcedure => LogicalVariableContext.Procedure
      case _: PRegion => LogicalVariableContext.Region
      case _: PPredicate => LogicalVariableContext.Predicate
      case tree.parent(p) => generalContextAttr(of)(p)
    }}

  def enclosingStatement(of: PAstNode): PStatement =
    enclosingStatementAttr(of)(of)

  private lazy val enclosingStatementAttr: PAstNode => PAstNode => PStatement =
    paramAttr { of => {
      case statement: PStatement => statement
      case tree.parent(p) => enclosingStatementAttr(of)(p)
    }}

  lazy val isGhost: PStatement => Boolean =
    attr {
      case _: PGhostStatement => true
      case compound: PCompoundStatement => compound.components forall isGhost
      case _ => false
    }

  lazy val typeOfLocation: PLocation => PType =
    attr { case PLocation(receiver, field) =>
      typeOfIdn(receiver) match {
        case receiverType: PRefType =>
          entity(receiverType.id) match {
            case structEntity: StructEntity =>
              structEntity.declaration.fields.find(_.id.name == field.name) match {
                case Some(fieldDecl) => fieldDecl.typ
                case None => PUnknownType()
              }
            case _ => PUnknownType()
          }
        case _ => PUnknownType()
      }
    }

  lazy val typeOfIdn: PIdnNode => PType =
    attr(entity(_) match {
      case ArgumentEntity(decl) => decl.typ
      case LocalVariableEntity(decl) => decl.typ
      case ProcedureEntity(decl) => decl.typ
      case LogicalVariableEntity(decl) => typeOfLogicalVariable(decl)
      case _ => PUnknownType()
    })

  lazy val typeOfLogicalVariable: PLogicalVariableBinder => PType =
    attr(binder => boundBy(binder) match {
      case PPointsTo(location, _) =>
        typeOfLocation(location)

      case PPredicateExp(id, _) =>
        val region = entity(id).asInstanceOf[RegionEntity].declaration
        typ(region.state)

      case PInterferenceClause(`binder`, set, _) =>
        typ(set).asInstanceOf[PSetType].elementType

      case action @ PAction2(_, `binder`, _) =>
        typ(enclosingMember(action).asInstanceOf[Option[PRegion]].get.state)

      case action @ PAction3(_, `binder`, _, _) =>
        typ(enclosingMember(action).asInstanceOf[Option[PRegion]].get.state)

      case comprehension @ PSetComprehension(`binder`, _, _) =>
        comprehension.typeAnnotation match {
          case Some(_typ) =>
            _typ

          case None =>
            val name = binder.id.name

            val collectOccurrencesOfFreeVariable =
              collect[Set, PIdnExp] { case exp @ PIdnExp(PIdnUse(`name`)) => exp }

            val expectedTypes =
              collectOccurrencesOfFreeVariable(comprehension.filter).map(expectedType)

            if (expectedTypes.size != 1) PUnknownType()
            else expectedTypes.head
        }

      case other => sys.error(s"Unexpectedly found $other as the binding context of $binder")
    })

  lazy val boundBy: PLogicalVariableBinder => PBindingContext =
    attr {
      case tree.parent(interferenceClause: PInterferenceClause) => interferenceClause
      case tree.parent(action: PAction2) => action
      case tree.parent(action: PAction3) => action
      case tree.parent(comprehension: PSetComprehension) => comprehension
      case tree.parent(pointsTo: PPointsTo) => pointsTo

      case tree.parent.pair(binder: PLogicalVariableBinder,
                            predicateExp @ PPredicateExp(id, arguments)) =>

        val region =
          entity(id) match {
            case regionEntity: RegionEntity => regionEntity.declaration
            case _ => sys.error(s"Logical variables cannot yet be bound by a non-region predicate: $predicateExp")
          }

        val idx = arguments.indices.find(arguments(_) == binder).get

        assert(idx == region.formalArgs.length + 1,
                 s"Expected logical variable binder $binder to be the last predicate argument, "
               + s"but got $predicateExp")

        predicateExp
    }

  /**
    * What is the type of an expression?
    */
  lazy val typ: PExpression => PType =
    attr {
      case _: PIntLit => PIntType()
      case _: PTrueLit | _: PFalseLit => PBoolType()
      case _: PNullLit => PNullType()

      case ret: PRet => enclosingMember(ret).get.asInstanceOf[PProcedure].typ

      case PIdnExp(id) => typeOfIdn(id)

      case _: PAdd | _: PSub | _: PMod | _: PDiv => PIntType()
      case _: PAnd | _: POr | _: PNot => PBoolType()
      case _: PEquals | _: PLess | _: PAtMost | _: PGreater | _: PAtLeast => PBoolType()

      case _: PIntSet | _: PNatSet => PSetType(PIntType())

      case explicitCollection: PExplicitCollection =>
        val typeConstructor: PType => PCollectionType =
          explicitCollection match {
            case _: PExplicitSet => PSetType
            case _: PExplicitSeq => PSeqType
          }

        explicitCollection.typeAnnotation match {
          case Some(_typ) =>
            typeConstructor(_typ)
          case None =>
            if (explicitCollection.elements.nonEmpty)
              typeConstructor(typ(explicitCollection.elements.head))
            else
              PUnknownType()
        }

      case PSetComprehension(qvar, _, typeAnnotation) =>
        typeAnnotation match {
          case Some(_typ) =>
            PSetType(_typ)
          case None =>
            /* TODO: Only works if the set comprehension occurs in an action definition */
            PSetType(typeOfLogicalVariable(qvar))
        }

      case _: PSetContains => PBoolType()
      case _: PSeqSize => PIntType()
      case headExp: PSeqHead => typ(headExp.seq).asInstanceOf[PCollectionType].elementType
      case tailExp: PSeqTail => typ(tailExp.seq)

      case conditional: PConditional => typ(conditional.thn)
      case unfolding: PUnfolding => typ(unfolding.body)

      case _: PPointsTo | _: PPredicateExp | _: PGuardExp | _: PTrackingResource => PBoolType()

      case tree.parent.pair(_: PIrrelevantValue, p: PExpression) => typ(p)

      case binder: PLogicalVariableBinder => typeOfLogicalVariable(binder)

      case _ => PUnknownType()
    }

  /**
    * What is the expected type of an expression?
    */
  lazy val expectedType: PExpression => PType =
    attr {
      case tree.parent(_: PIf | _: PWhile) => PBoolType()

      case tree.parent(PAssign(id, _)) =>
        entity(id) match {
          case LocalVariableEntity(decl) => decl.typ
          case _ => PUnknownType()
        }

      case tree.parent(PHeapRead(id, _)) => typeOfIdn(id)

      case tree.parent(PHeapWrite(location, _)) => typeOfLocation(location)

      case e @ tree.parent(PEquals(lhs, rhs)) =>
        /* Given an equality lhs == rhs, the expected type of the lhs is the type of the rhs
         * and vice versa.
         * This, however, can result in a cycle; in particular, if lhs and rhs denote the
         * same variable (whose type is to be determined). In this case, Kiama throws an
         * IllegalStateException and we return PUnknownType.
         */
        try {
          if (e eq lhs) typ(rhs)
          else typ(lhs)
        } catch {
          case ex: IllegalStateException if ex.getMessage.toLowerCase.startsWith("cycle detected") =>
            PUnknownType()
        }

      case tree.parent(_: PAdd | _: PSub | _: PMod | _: PDiv) => PIntType()
      case tree.parent(_: PAnd | _: POr | _: PNot) => PBoolType()
      case tree.parent(_: PLess | _: PAtMost | _: PGreater | _: PAtLeast) => PIntType()

      case cnd @ tree.parent(conditional: PConditional) if cnd eq conditional.cond => PBoolType()
      case els @ tree.parent(conditional: PConditional) if els eq conditional.els => typ(conditional.thn)

      case set @ tree.parent(contains: PSetContains) if set eq contains.set => PSetType(typ(contains.element))

      case _ =>
        /* Returning unknown expresses that no particular type is expected */
        PUnknownType()
    }

  lazy val atomicity: PStatement => AtomicityKind =
    attr {
      case _: PAssign => AtomicityKind.Nonatomic
      case _: PIf => AtomicityKind.Nonatomic
      case _: PWhile => AtomicityKind.Nonatomic

      case PSeqComp(first, second) =>
        if (isGhost(first)) atomicity(second)
        else if (isGhost(second)) atomicity(first)
        else AtomicityKind.Nonatomic

      case _: PSkip => AtomicityKind.Atomic
      case _: PHeapWrite => AtomicityKind.Atomic
      case _: PHeapRead => AtomicityKind.Atomic

      /* TODO: Not sure if it makes sense to attribute an atomicity kind to ghost operations.
       *       See also `isGhost` and its uses.
       */
      case _: PFold => AtomicityKind.Atomic
      case _: PUnfold => AtomicityKind.Atomic
      case _: PInhale => AtomicityKind.Atomic
      case _: PExhale => AtomicityKind.Atomic
      case _: PAssume => AtomicityKind.Atomic
      case _: PAssert => AtomicityKind.Atomic
      case _: PHavoc => AtomicityKind.Atomic
      case _: PUseRegionInterpretation => AtomicityKind.Atomic

      case _: PMakeAtomic => AtomicityKind.Atomic
      case _: PUpdateRegion => AtomicityKind.Atomic
      case _: PUseAtomic => AtomicityKind.Atomic
      case _: POpenRegion => AtomicityKind.Atomic

      case call: PProcedureCall =>
        val callee = entity(call.procedure).asInstanceOf[ProcedureEntity].declaration

        callee.atomicity match {
          case PNotAtomic() => AtomicityKind.Nonatomic
          case PPrimitiveAtomic() | PAbstractAtomic() => AtomicityKind.Atomic
        }
    }

  lazy val expectedAtomicity: PStatement => AtomicityKind =
    attr {
      case tree.parent(_: PIf | _: PWhile) => AtomicityKind.Nonatomic

      case tree.parent(PSeqComp(first, second)) =>
        if (isGhost(first)) atomicity(second)
        else if (isGhost(second)) atomicity(first)
        else AtomicityKind.Nonatomic

      case tree.parent(_: PMakeAtomic) => AtomicityKind.Nonatomic
      case tree.parent(_: PUpdateRegion) => AtomicityKind.Atomic
      case tree.parent(_: PUseAtomic) => AtomicityKind.Atomic
      case tree.parent(_: POpenRegion) => AtomicityKind.Atomic

      case tree.parent(procedure: PProcedure) =>
        /* TODO: Unify with code above */
        procedure.atomicity match {
          case PNotAtomic() => AtomicityKind.Nonatomic
          case PPrimitiveAtomic() | PAbstractAtomic() => AtomicityKind.Atomic
        }

      case of @ tree.parent(parent) =>
        sys.error(  s"Unsupported: determining the expected atomicity of '$of' "
                  + s"(class: ${of.getClass.getSimpleName}) in the context of '$parent' "
                  + s"(class: ${parent.getClass.getSimpleName})")
    }

  lazy val freeVariables: PExpression => ListSet[PIdnUse] =
    attr {
      case PIdnExp(id) => ListSet(id)

      case bindingContext: PExpression with PBindingContext =>
        bindingContext match {
          case PSetComprehension(qvar, filter, _) =>
            freeVariables(filter) - PIdnUse(qvar.id.name)
          case PPredicateExp(_, _) => ???
//            Set(arguments flatMap freeVariables :_*)
          case PPointsTo(location, value) =>
            ListSet(location.receiver) ++ (freeVariables(value) -- boundVariables(value))
        }

      case _: PTrueLit => ListSet.empty
      case _: PFalseLit => ListSet.empty
      case _: PIntLit => ListSet.empty
      case _: PRet => ListSet.empty
      case _: PIntSet => ListSet.empty
      case _: PNatSet => ListSet.empty

      case explicitCollection: PExplicitCollection =>
        ListSet(explicitCollection.elements flatMap freeVariables :_*)

      case PSetComprehension(qvar, filter, _) =>
        freeVariables(filter) - PIdnUse(qvar.id.name)

      case PSeqSize(seq) => freeVariables(seq)
      case PSeqHead(seq) => freeVariables(seq)
      case PSeqTail(seq) => freeVariables(seq)

      case op: PUnOp => freeVariables(op.operand)
      case op: PBinOp => freeVariables(op.left) ++ freeVariables(op.right)

      case PConditional(cond, thn, els) =>
        freeVariables(cond) ++ freeVariables(thn) ++ freeVariables(els)

      case PUnfolding(predicate, body) =>
        freeVariables(predicate) ++ freeVariables(body)

      case PGuardExp(_, regionId) => ListSet(regionId)
      case PDiamond(regionId) => ListSet(regionId)

      case PRegionUpdateWitness(regionId, from, to) =>
        ListSet(regionId) ++ freeVariables(from) ++ freeVariables(to)

      case _: PIrrelevantValue => ListSet.empty
    }

  lazy val boundVariables: PExpression => ListSet[PIdnDef] =
    attr {
      case PLogicalVariableBinder(id) => ListSet(id)
      case _ => ListSet.empty
    }

  private implicit def defs2UsesSets(xs: ListSet[PIdnDef]): ListSet[PIdnUse] =
    xs.map(idndef => PIdnUse(idndef.name))
}

sealed trait LogicalVariableContext

object LogicalVariableContext {
  case object Interference extends LogicalVariableContext
  case object Precondition extends LogicalVariableContext
  case object Postcondition extends LogicalVariableContext
  case object Invariant extends LogicalVariableContext
  case object Procedure extends LogicalVariableContext
  case object Region extends LogicalVariableContext
  case object Predicate extends LogicalVariableContext
}

sealed trait AtomicityKind

object AtomicityKind {
  case object Nonatomic extends AtomicityKind
  case object Atomic extends AtomicityKind
}
