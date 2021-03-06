/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.remote

import com.intellij.ide.IdeBundle

enum class AuthType {
  PASSWORD, KEY_PAIR,
  /**
   * Use the way OpenSSH `ssh` client authenticates:
   * - read OpenSSH configuration files, get `IdentityFile` declared in it;
   * - use identity files provided by authentication agent (ssh-agent or PuTTY).
   */
  OPEN_SSH;

  val displayName: String
    get() = when (this) {
      PASSWORD -> IdeBundle.message("display.name.password")
      KEY_PAIR -> IdeBundle.message("display.name.key.pair.openssh.or.putty")
      OPEN_SSH -> IdeBundle.message("display.name.openssh.config.and.authentication.agent")
    }
}