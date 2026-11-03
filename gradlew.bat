@echo off
setlocal
set "BASE=%~dp0"
set "JAR=%BASE%gradle\wrapper\gradle-wrapper.jar"
if not exist "%JAR%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $p=$env:JAR; $u='https://services.gradle.org/distributions/gradle-8.13-wrapper.jar'; Invoke-WebRequest -Uri $u -OutFile ($p+'.tmp'); $hash=(Get-FileHash ($p+'.tmp') -Algorithm SHA256).Hash.ToLowerInvariant(); if ($hash -ne '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f') { Remove-Item ($p+'.tmp'); throw 'Gradle wrapper checksum mismatch' }; Move-Item ($p+'.tmp') $p"
  if errorlevel 1 exit /b 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%
