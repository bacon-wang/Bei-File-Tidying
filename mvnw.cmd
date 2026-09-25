@echo off
java -Dmaven.multiModuleProjectDirectory="%~dp0" -classpath "%~dp0\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
