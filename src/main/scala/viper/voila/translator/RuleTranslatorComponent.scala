/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.translator

import viper.voila.frontend._
import viper.voila.reporting._
import viper.silver.{ast => vpr}
import viper.silver.verifier.{errors => vprerr, reasons => vprrea}

trait RuleTranslatorComponent { this: PProgramToViperTranslator =>
  protected var currentlyOpenRegions: List[(PRegion, Vector[PExpression], vpr.Label)] = List.empty
    /* Important: use as a stack! */

  def translate(makeAtomic: PMakeAtomic): vpr.Stmt = {
    val regionArgs = makeAtomic.regionPredicate.arguments
    val regionId = regionArgs.head.asInstanceOf[PIdnExp].id

    val (region, regionInArgs, regionOutArgs) =
      getAndTranslateRegionPredicateDetails(makeAtomic.regionPredicate)

    assert(
      regionOutArgs.isEmpty,
       "Using-clauses expect region assertions without out-arguments, but got " +
      s"${makeAtomic.regionPredicate} at ${makeAtomic.regionPredicate.position}")

    val regionType = semanticAnalyser.typ(region.state)
    val vprRegionIdArg = regionInArgs.head

    val inhaleDiamond =
      vpr.Inhale(diamondAccess(translateUseOf(makeAtomic.guard.regionId.id)))()

    val guard = translate(makeAtomic.guard)

    val exhaleGuard =
      vpr.Exhale(guard)().withSource(makeAtomic.guard)

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.ExhaleFailed if e causedBy exhaleGuard =>
        MakeAtomicError(makeAtomic, InsufficientGuardPermissionError(makeAtomic.guard))
    }

    val havoc1 = stabilizeSingleInstances("before atomic", (region, regionInArgs))

    val havoc2 = stabilizeSingleInstances("after atomic", (region, regionInArgs))

    val ruleBody = translate(makeAtomic.body)

    val vprAtomicityContextX =
      vpr.DomainFuncApp(
        atomicityContextFunction(regionId),
        regionInArgs,
        Map.empty[vpr.TypeVar, vpr.Type]
      )()

    val vprStepFrom =
      stepFromLocation(vprRegionIdArg, regionType).withSource(regionId)

    val vprStepTo =
      stepToLocation(vprRegionIdArg, regionType).withSource(regionId)

    val checkUpdatePermitted = {
      val vprStepFromAllowed =
        vpr.AnySetContains(
          vprStepFrom,
          vprAtomicityContextX
        )().withSource(makeAtomic)

      val vprCheckFrom =
        vpr.Assert(vprStepFromAllowed)().withSource(makeAtomic)

      errorBacktranslator.addErrorTransformer {
        case e @ vprerr.AssertFailed(_, reason: vprrea.InsufficientPermission, _)
             if (e causedBy vprCheckFrom) && (reason causedBy vprStepFrom) =>

          MakeAtomicError(makeAtomic)
            .dueTo(InsufficientTrackingResourcePermissionError(makeAtomic.regionPredicate, regionId))
            .dueTo(hintAtEnclosingLoopInvariants(regionId))
            .dueTo(AdditionalErrorClarification("This could be related to issue #8", regionId))

        case e @ vprerr.AssertFailed(_, reason: vprrea.AssertionFalse, _)
             if (e causedBy vprCheckFrom) && (reason causedBy vprStepFromAllowed) =>

          MakeAtomicError(makeAtomic)
            .dueTo(IllegalRegionStateChangeError(makeAtomic.body))
            .dueTo(AdditionalErrorClarification(
                      "In particular, it cannot be shown that the region is transitioned from a " +
                      "state that is compatible with the procedure's interference specification",
                      regionId))
            .dueTo(hintAtEnclosingLoopInvariants(regionId))
      }

      val vprCheckTo =
        regionStateChangeAllowedByGuard(
          region,
          regionInArgs,
          makeAtomic.guard.guard.name,
          guard,
          vprStepFrom,
          vprStepTo
        ).withSource(makeAtomic)

      errorBacktranslator.addErrorTransformer {
        case e: vprerr.AssertFailed if e causedBy vprCheckTo =>
          MakeAtomicError(makeAtomic)
            .dueTo(IllegalRegionStateChangeError(makeAtomic.guard))
            .dueTo(hintAtEnclosingLoopInvariants(regionId))
      }

      vpr.Seqn(
        Vector(
          vprCheckFrom,
          vprCheckTo),
        Vector.empty
      )()
    }

    val vprRegionState =
      vpr.FuncApp(
        regionStateFunction(region),
        regionInArgs
      )()

    val assumeCurrentStateIsStepTo =
      vpr.Inhale(
        vpr.EqCmp(
          vprRegionState,
          stepToLocation(vprRegionIdArg, regionType)
        )()
      )()

    val assumeOldStateWasStepFrom =
      vpr.Inhale(
        vpr.EqCmp(
          vpr.Old(vprRegionState)(),
          stepFromLocation(vprRegionIdArg, regionType)
        )()
      )()

    val inhaleGuard = vpr.Inhale(guard)()

    val exhaleTrackingResource = {
      val stepFrom =
        stepFromAccess(vprRegionIdArg, regionType).withSource(makeAtomic.regionPredicate)

      val stepTo =
        stepToAccess(vprRegionIdArg, regionType).withSource(makeAtomic.regionPredicate)

      val exhale =
        vpr.Exhale(
          vpr.And(
            stepFrom,
            stepTo
          )()
        )().withSource(makeAtomic.regionPredicate)

      errorBacktranslator.addErrorTransformer {
        case e: vprerr.ExhaleFailed if e causedBy exhale =>
          MakeAtomicError(makeAtomic)
            .dueTo(InsufficientTrackingResourcePermissionError(makeAtomic.regionPredicate, regionId))
            .dueTo(hintAtEnclosingLoopInvariants(regionId))
      }

      exhale
    }

    val result =
      vpr.Seqn(
        Vector(
          inhaleDiamond,
          exhaleGuard,
          havoc1,
          ruleBody,
          checkUpdatePermitted,
          havoc2,
          BLANK_LINE,
          assumeCurrentStateIsStepTo,
          assumeOldStateWasStepFrom,
          inhaleGuard,
          exhaleTrackingResource),
        Vector.empty
      )()

    surroundWithSectionComments(makeAtomic.statementName, result)
  }

  def translate(updateRegion: PUpdateRegion): vpr.Stmt = {
    val regionArgs = updateRegion.regionPredicate.arguments
    val regionId = regionArgs.head.asInstanceOf[PIdnExp].id

    val (region, vprInArgs, vprOutArgs) =
      getAndTranslateRegionPredicateDetails(updateRegion.regionPredicate)

      assert(
        vprOutArgs.isEmpty,
         "Using-clauses expect region assertions without out-arguments, but got " +
        s"${updateRegion.regionPredicate} at ${updateRegion.regionPredicate.position}")

    val regionType = semanticAnalyser.typ(region.state)
    val vprRegionId = vprInArgs.head

    val exhaleDiamond =
      vpr.Exhale(diamondAccess(vprRegionId))().withSource(updateRegion.regionPredicate)

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.ExhaleFailed if e causedBy exhaleDiamond =>
        UpdateRegionError(updateRegion)
          .dueTo(InsufficientDiamondResourcePermissionError(updateRegion.regionPredicate, regionId))
          .dueTo(hintAtEnclosingLoopInvariants(regionId))
    }

    val label = freshLabel("pre_region_update")

    val unfoldRegionPredicate =
      vpr.Unfold(regionPredicateAccess(region, vprInArgs))().withSource(updateRegion.regionPredicate)

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.UnfoldFailed if e causedBy unfoldRegionPredicate =>
        UpdateRegionError(updateRegion, InsufficientRegionPermissionError(updateRegion.regionPredicate))
    }

    val stabilizeFrameRegions =
      stabilizeAllInstances(s"before ${updateRegion.statementName}@${updateRegion.lineColumnPosition}")

    val ruleBody = translate(updateRegion.body)

    val foldRegionPredicate =
      vpr.Fold(regionPredicateAccess(region, vprInArgs))().withSource(updateRegion.regionPredicate)

    val ebt = this.errorBacktranslator // TODO: Should not be necessary!!!!!
    errorBacktranslator.addErrorTransformer {
      case e: vprerr.FoldFailed if e causedBy foldRegionPredicate =>
        UpdateRegionError(updateRegion, ebt.translate(e.reason))
    }

    val currentState =
      vpr.FuncApp(
        regionStateFunction(region),
        vprInArgs
      )()

    val oldState =
      vpr.LabelledOld(
        currentState,
        label.name
      )()

    val stateChanged = vpr.NeCmp(currentState, oldState)()

    val obtainTrackingResource = {
      val stepFrom = stepFromAccess(vprRegionId, regionType)
      val stepTo = stepToAccess(vprRegionId, regionType)

      val inhaleFromTo =
        vpr.Inhale(
          vpr.And(
            stepFrom,
            stepTo
          )()
        )()

      val initFrom = vpr.FieldAssign(stepFrom.loc, oldState)()
      val initTo = vpr.FieldAssign(stepTo.loc, currentState)()

      vpr.Seqn(
        Vector(
          inhaleFromTo,
          initFrom,
          initTo),
        Vector.empty
      )()
    }

    val inhaleDiamond = vpr.Inhale(diamondAccess(vprRegionId))()

    val postRegionUpdate =
      vpr.If(
        stateChanged,
        obtainTrackingResource,
        vpr.Seqn(Vector(inhaleDiamond), Vector.empty)()
      )()

    val result =
      vpr.Seqn(
        Vector(
          exhaleDiamond,
          label,
          unfoldRegionPredicate,
          stabilizeFrameRegions,
          ruleBody,
          foldRegionPredicate,
          postRegionUpdate),
        Vector.empty
      )()

    surroundWithSectionComments(updateRegion.statementName, result)
  }

  def translate(useAtomic: PUseAtomic): vpr.Stmt = {
    val (region, inArgs, outArgs) = getRegionPredicateDetails(useAtomic.regionPredicate)
    val vprInArgs = inArgs map translate

    assert(
      outArgs.isEmpty,
       "Using-clauses expect region assertions without out-arguments, but got " +
      s"${useAtomic.regionPredicate} at ${useAtomic.regionPredicate.position}")

    val preUseAtomicLabel = freshLabel("pre_use_atomic")

    val unfoldRegionPredicate =
      vpr.Unfold(regionPredicateAccess(region, vprInArgs))()

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.UnfoldFailed if e causedBy unfoldRegionPredicate =>
        UseAtomicError(useAtomic, InsufficientRegionPermissionError(useAtomic.regionPredicate))
    }

    currentlyOpenRegions = (region, inArgs, preUseAtomicLabel) :: currentlyOpenRegions
    val ruleBody = translate(useAtomic.body)
    assert(currentlyOpenRegions.head == (region, inArgs, preUseAtomicLabel))
    currentlyOpenRegions = currentlyOpenRegions.tail

    val foldRegionPredicate =
      vpr.Fold(
        regionPredicateAccess(region, vprInArgs).withSource(useAtomic.regionPredicate)
      )().withSource(useAtomic.regionPredicate)

    /* TODO: Reconsider error messages - introduce dedicated "region closing failed" error? */

    val ebt = this.errorBacktranslator // TODO: Should not be necessary!!!!!
    errorBacktranslator.addErrorTransformer {
      case e: vprerr.FoldFailed if e causedBy foldRegionPredicate =>
        UseAtomicError(useAtomic)
          .dueTo(IllegalRegionStateChangeError(useAtomic.regionPredicate))
          .dueTo(AdditionalErrorClarification("In particular, closing the region at the end of the use-atomic block might fail", useAtomic.regionPredicate))
          .dueTo(ebt.translate(e.reason))
    }

    val guard = translate(useAtomic.guard)

    /* Temporarily exhale the guard used in the use-atomic rule so that it is no longer held
     * when stabilising the frame.
     *
     * Note: The guard must be checked in any case! I.e. the check is independent of the
     * technical treatment of frame stabilisation.
     */
    val exhaleGuard = vpr.Exhale(guard)().withSource(useAtomic.guard)

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.ExhaleFailed if e causedBy exhaleGuard =>
        UseAtomicError(useAtomic, InsufficientGuardPermissionError(useAtomic.guard))
    }

    val inhaleGuard = vpr.Inhale(guard)()

    val stabilizationReason = s"before ${useAtomic.statementName}@${useAtomic.lineColumnPosition}"

    val stabilizeOtherRegionTypes =
      stabilizeAllInstances(stabilizationReason, tree.root.regions.filterNot(_ == region): _*)

    val stabilizeCurrentRegionTypes =
      stabilizeAllInstances(stabilizationReason, region)

    val currentState =
      vpr.FuncApp(
        regionStateFunction(region),
        vprInArgs
      )()

    val oldState =
      vpr.LabelledOld(
        currentState,
        preUseAtomicLabel.name
      )()

    val stateChangeAllowed =
      regionStateChangeAllowedByGuard(
        region,
        vprInArgs,
        useAtomic.guard.guard.name,
        guard,
        oldState,
        currentState
      ).withSource(useAtomic)

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.AssertFailed if e causedBy stateChangeAllowed =>
        UseAtomicError(useAtomic, IllegalRegionStateChangeError(useAtomic.body))
    }

    val result =
      vpr.Seqn(
        Vector(
          preUseAtomicLabel,
          /* Note: Guard must be checked!
           * I.e. the check is independent of the technical treatment of frame stabilisation.
           */
          exhaleGuard,
          stabilizeOtherRegionTypes,
          unfoldRegionPredicate,
          stabilizeCurrentRegionTypes,
          inhaleGuard,
          ruleBody,
          foldRegionPredicate,
          stateChangeAllowed),
        Vector.empty
      )()

    surroundWithSectionComments(useAtomic.statementName, result)
  }

  def translate(openRegion: POpenRegion): vpr.Stmt = {
    val (region, inArgs, outArgs) = getRegionPredicateDetails(openRegion.regionPredicate)
    val vprInArgs = inArgs map translate
    val preOpenLabel = freshLabel("pre_open_region")

    assert(
      outArgs.isEmpty,
       "Using-clauses expect region assertions without out-arguments, but got " +
      s"${openRegion.regionPredicate} at ${openRegion.regionPredicate.position}")

    val unfoldRegionPredicate =
      vpr.Unfold(regionPredicateAccess(region, vprInArgs))()

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.UnfoldFailed if e causedBy unfoldRegionPredicate =>
        OpenRegionError(openRegion, InsufficientRegionPermissionError(openRegion.regionPredicate))
    }

    currentlyOpenRegions = (region, inArgs, preOpenLabel) :: currentlyOpenRegions
    val ruleBody = translate(openRegion.body)
    assert(currentlyOpenRegions.head == (region, inArgs, preOpenLabel))
    currentlyOpenRegions = currentlyOpenRegions.tail

    val foldRegionPredicate =
      vpr.Fold(regionPredicateAccess(region, vprInArgs))()

    val ebt = this.errorBacktranslator // TODO: Should not be necessary!!!!!
    errorBacktranslator.addErrorTransformer {
      case e: vprerr.FoldFailed if e causedBy foldRegionPredicate =>
        OpenRegionError(openRegion, ebt.translate(e.reason))
    }

    val currentState =
      vpr.FuncApp(
        regionStateFunction(region),
        vprInArgs
      )()

    val oldState =
      vpr.LabelledOld(
        currentState,
        preOpenLabel.name
      )()

    val stateUnchanged =
      vpr.Assert(
        vpr.EqCmp(
          currentState,
          oldState
        )()
      )()

    errorBacktranslator.addErrorTransformer {
      case e: vprerr.AssertFailed if e causedBy stateUnchanged =>
        OpenRegionError(openRegion, IllegalRegionStateChangeError(openRegion.body))
    }

    val result =
      vpr.Seqn(
        Vector(
          preOpenLabel,
          unfoldRegionPredicate,
          ruleBody,
          foldRegionPredicate,
          stateUnchanged),
        Vector.empty
      )()

    surroundWithSectionComments(openRegion.statementName, result)
  }
}
