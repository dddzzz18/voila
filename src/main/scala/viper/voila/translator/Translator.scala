/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.translator

import viper.voila.frontend.{SemanticAnalyser, VoilaTree}
import viper.silver.{ast => vpr}

trait Translator[F, T] {
  def translate(source: F): T
}

class PProgramToViperTranslator(val semanticAnalyser: SemanticAnalyser)
    extends Translator[VoilaTree, vpr.Program]
       with MainTranslatorComponent
       with HeapAccessTranslatorComponent
       with RegionTranslatorComponent
       with RuleTranslatorComponent