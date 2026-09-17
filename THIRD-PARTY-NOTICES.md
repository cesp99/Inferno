# Third-party notices

Inferno itself is licensed under the GNU General Public License v3.0 (see `LICENSE`). The app statically links
or bundles the components below. Each section names the component, its licence, and reproduces the licence text
or its SPDX identifier with a link to the canonical text. This file is also shipped inside the APK
(`assets/THIRD-PARTY-NOTICES.md`) and shown in Settings > About > Open-source licences.

Model weights are **not** part of the app; their licences are listed per model in `README.md` and on each model
card before download.

---

## 1. llama.cpp and ggml

* Project: https://github.com/ggml-org/llama.cpp (pinned submodule `third_party/llama.cpp`, tag b10991,
  commit 930e2fa5995789efbf249a8bf61325bb626e417b). Compiled into `libinferno.so` (ggml, ggml-cpu, llama, mtmd).
* Licence: MIT (`third_party/llama.cpp/LICENSE`)

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 1.1 Libraries vendored by llama.cpp and linked into `libinferno.so` via `mtmd`

| Component | Licence | Canonical text |
|---|---|---|
| stb_image (Sean Barrett) | MIT **or** Public Domain (Unlicense), at the user's choice | see section 4 |
| miniaudio (David Reid) | Public Domain (Unlicense) **or** MIT No Attribution, at the user's choice | see section 5 |
| xxHash (Yann Collet) — `vendor/hash/xxhash` | BSD-2-Clause | https://github.com/Cyan4973/xxHash/blob/dev/LICENSE |
| rotate-bits — `vendor/hash/rotate-bits` | MIT | https://github.com/ggml-org/llama.cpp/blob/master/vendor/hash/rotate-bits/LICENSE.md |
| sha1 (Steve Reid) — `vendor/hash/sha1` | Public Domain | header comment in `vendor/hash/sha1/sha1.h` |
| subprocess.h (Neil Henning) — `vendor/sheredom` | Public Domain (Unlicense) | https://github.com/sheredom/subprocess.h |
| nlohmann/json (`vendor/nlohmann`) | MIT | https://github.com/nlohmann/json/blob/develop/LICENSE.MIT |

## 2. stable-diffusion.cpp

* Project: https://github.com/leejet/stable-diffusion.cpp (pinned submodule `third_party/stable-diffusion.cpp`,
  commit 59c23bc). Compiled into `libinferno_sd.so` together with its forked copy of ggml (MIT, same licence
  as section 1).
* Licence: MIT (`third_party/stable-diffusion.cpp/LICENSE`)

```
MIT License

Copyright (c) 2023 leejet

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 2.1 Libraries vendored by stable-diffusion.cpp (`thirdparty/`) and linked into `libinferno_sd.so`

| Component | Licence | Canonical text |
|---|---|---|
| stb_image / stb_image_resize / stb_image_write | MIT or Public Domain (dual) | see section 4 |
| nlohmann/json (`json.hpp`) | MIT | https://github.com/nlohmann/json/blob/develop/LICENSE.MIT |
| kuba--/zip (`zip.c`, `zip.h`) | Public Domain (Unlicense) | https://github.com/kuba--/zip/blob/master/UNLICENSE |
| miniz (`miniz.h`) | MIT | https://github.com/richgel999/miniz/blob/master/LICENSE |
| darts-clone (`darts.h`, Susumu Yata) | BSD-3-Clause | `third_party/stable-diffusion.cpp/thirdparty/LICENSE.darts_clone.txt` |
| Oniguruma (K. Kosako) | BSD-2-Clause | `third_party/stable-diffusion.cpp/thirdparty/oniguruma/COPYING` |
| utf8proc (Julia developers, Public Software Group) | MIT + Unicode data licence | `third_party/stable-diffusion.cpp/thirdparty/utf8proc/LICENSE.md` |

WebP/WebM output (`libwebp`, `libwebm`) and `cpp-httplib` are **not** compiled into Inferno (`SD_WEBP=OFF`,
`SD_WEBM=OFF`, no server example).

## 3. KleidiAI

* Project: https://github.com/ARM-software/kleidiai — release v1.24.0, fetched by ggml at configure time
  (`GGML_CPU_KLEIDIAI=ON`) and statically linked into both `libinferno.so` and `libinferno_sd.so`.
* Licence: Apache License 2.0 (`LICENSES/Apache-2.0.txt` in the KleidiAI source tree; full text in section 8).
  The KleidiAI source tree also carries `LICENSES/BSD-3-Clause.txt`, which applies only to its bundled test
  dependencies (googletest, benchmark) — none of which are built or shipped here.
* NOTICE (Apache-2.0 §4(d)): KleidiAI v1.24.0 ships no separate `NOTICE` file; the attribution carried by every
  source file is reproduced here verbatim:

```
SPDX-FileCopyrightText: Copyright 2024-2026 Arm Limited and/or its affiliates <open-source-office@arm.com>
SPDX-License-Identifier: Apache-2.0
```

Arm and Kleidi are trademarks of Arm Limited (or its subsidiaries or affiliates).

### 3.1 OpenCL headers (Khronos Group)

* Project: https://github.com/KhronosGroup/OpenCL-Headers (pinned submodule `third_party/OpenCL-Headers`).
  Header-only: used to compile ggml's OpenCL backend and `app/src/main/cpp/inferno_opencl.cpp`; nothing from the
  package is shipped in the APK beyond the constants and prototypes compiled into `libinferno.so`. No OpenCL
  runtime is bundled: the phone's own `libOpenCL.so` (a public vendor library) is loaded at run time.
* Licence: Apache License 2.0 (`third_party/OpenCL-Headers/LICENSE`; full text in section 8).

## 4. stb_image (Sean Barrett)

Single-header image decoder used by both engines. Dual-licensed; Inferno uses it under the MIT alternative.

```
This software is available under 2 licenses -- choose whichever you prefer.
------------------------------------------------------------------------------
ALTERNATIVE A - MIT License
Copyright (c) 2017 Sean Barrett
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
------------------------------------------------------------------------------
ALTERNATIVE B - Public Domain (www.unlicense.org)
This is free and unencumbered software released into the public domain.
Anyone is free to copy, modify, publish, use, compile, sell, or distribute this
software, either in source code form or as a compiled binary, for any purpose,
commercial or non-commercial, and by any means.
In jurisdictions that recognize copyright laws, the author or authors of this
software dedicate any and all copyright interest in the software to the public
domain. We make this dedication for the benefit of the public at large and to
the detriment of our heirs and successors. We intend this dedication to be an
overt act of relinquishment in perpetuity of all present and future rights to
this software under copyright law.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## 5. miniaudio (David Reid)

Pulled in by llama.cpp's `mtmd-helper` (audio decoding; unused at runtime by Inferno but linked). Dual-licensed;
Inferno uses it under the MIT No Attribution alternative.

```
Copyright 2026 David Reid

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

(Alternative 1, the Unlicense, is reproduced in section 4, "ALTERNATIVE B".)

## 6. Lucide icons

* Project: https://lucide.dev — bundled through the Compose port `com.composables:icons-lucide:1.1.0`
  (https://composeicons.com, MIT, https://github.com/composablehorizons/composeicons/blob/main/LICENSE).
* Licence of the icon set: ISC

```
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other
copyright (c) for Lucide are held by Lucide Contributors 2022.

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING
ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL,
DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE
USE OR PERFORMANCE OF THIS SOFTWARE.
```

## 7. Apache-2.0 licensed Kotlin/Android libraries

All of the following are used under the Apache License, Version 2.0 (full text in section 8):

| Component | Version | Source |
|---|---|---|
| OkHttp (Square) | 5.5.0 | https://github.com/square/okhttp |
| Coil 3 (`coil-compose`) | 3.6.2 | https://github.com/coil-kt/coil |
| AndroidX: core-ktx, core-splashscreen, activity-compose, lifecycle (runtime-compose, viewmodel-compose, process), Room (runtime, ktx, compiler), DataStore preferences, graphics-shapes, material3-adaptive | see `gradle/libs.versions.toml` | https://github.com/androidx/androidx |
| Jetpack Compose (ui, ui-graphics, foundation, animation, material3) via `compose-bom-alpha 2026.09.00` | material3 1.5.0-alpha28, foundation/ui/animation 1.13.0-alpha03 | https://github.com/androidx/androidx |
| kotlinx-coroutines-android | 1.11.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx-serialization-json | 1.11.0 | https://github.com/Kotlin/kotlinx.serialization |
| Kotlin standard library (JetBrains) | 2.4.20 | https://github.com/JetBrains/kotlin |
| multiplatform-markdown-renderer (Mike Penz) | 0.45.0 | https://github.com/mikepenz/multiplatform-markdown-renderer |
| KleidiAI (Arm) | v1.24.0 | see section 3 |
| OpenCL-Headers (Khronos Group) | pinned submodule `third_party/OpenCL-Headers` | https://github.com/KhronosGroup/OpenCL-Headers |

## 8. Apache License, Version 2.0

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```
