/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.backends

import viper.carbon
import viper.silver

class Carbon(commandLineArguments: Seq[String]) extends ViperVerifier {
  var backend: carbon.CarbonVerifier = _

  def start(): Unit = {
    require(backend == null)

    backend = carbon.CarbonVerifier(List("startedBy" -> s"Unit test ${this.getClass.getSimpleName}"))

    backend.parseCommandLine(commandLineArguments ++ Seq("--ignoreFile", "dummy.sil"))
    backend.start()
  }

  def handle(program: silver.ast.Program): silver.verifier.VerificationResult = {
    require(backend != null)

    backend.verify(program)
  }

  def stop(): Unit = {
    require(backend != null)

    backend.stop()
    backend = null
  }
}

