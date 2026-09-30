# -*- coding: utf-8 -*-
"""构建平板客户端 debug APK。直接用 GradleWrapperMain 调起，避开 .bat / MSYS 路径问题。"""
import os, subprocess, sys

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_HOME = os.environ.get("JAVA_HOME",
                           r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot")
ANDROID_HOME = os.environ.get("ANDROID_HOME", r"C:\Android\Sdk")
LOG = os.path.join(APP, "build.log")

env = dict(os.environ)
env["JAVA_HOME"] = JAVA_HOME
env["ANDROID_HOME"] = ANDROID_HOME
env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")

java = os.path.join(JAVA_HOME, "bin", "java.exe")
cmd = [java, "-Xmx1536m", "-Dorg.gradle.appname=gradlew",
       "-classpath", os.path.join("gradle", "wrapper", "gradle-wrapper.jar"),
       "org.gradle.wrapper.GradleWrapperMain",
       "--no-daemon", "--stacktrace", ":app:assembleDebug"]

with open(LOG, "w", encoding="utf-8", errors="replace") as fh:
    fh.write("CMD " + " ".join(cmd) + "\n")
    fh.flush()
    p = subprocess.run(cmd, cwd=APP, env=env, stdout=fh, stderr=subprocess.STDOUT)
    fh.write("\nEXIT %d\n" % p.returncode)
print("EXIT", p.returncode, "log:", LOG)
