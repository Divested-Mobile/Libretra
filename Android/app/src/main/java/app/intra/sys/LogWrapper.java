/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.sys;

import android.util.Log;
import app.intra.BuildConfig;

public class LogWrapper {
  public static void logException(Throwable t) {
    Log.e("LogWrapper", "Error", t);
  }

  public static void log(int i, String s, String s1) {
    Log.println(i, s, s1);
  }
}
