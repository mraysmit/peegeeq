# Maven Toolchains - How It Works

## Overview

Maven Toolchains allows you to **run Maven with one JDK** (e.g., Java 24) while **compiling and testing with a different JDK** (e.g., Java 21).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Maven JVM (Java 24)                                         │
│ ├── Reads pom.xml                                           │
│ ├── Resolves dependencies                                   │
│ ├── Orchestrates build lifecycle                            │
│ └── Spawns forked processes ──────────────────┐             │
└────────────────────────────────────────────────│─────────────┘
                                                 │
                                                 ▼
┌─────────────────────────────────────────────────────────────┐
│ Forked JVM (Java 21) - Compiler                             │
│ ├── Executable: C:\Users\markr\.jdks\openjdk-21\bin\javac  │
│ ├── Compiles source code                                    │
│ └── Produces Java 21 bytecode (class version 65)            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Forked JVM (Java 21) - Test Runner                          │
│ ├── Executable: C:\Users\markr\.jdks\openjdk-21\bin\java   │
│ ├── Runs JUnit tests                                        │
│ ├── JaCoCo agent instruments bytecode                       │
│ └── No Java 24 compatibility issues!                        │
└─────────────────────────────────────────────────────────────┘
```

## Configuration Files

### 1. `~/.m2/toolchains.xml` - JDK Registry

```xml
<toolchains>
    <toolchain>
        <type>jdk</type>
        <provides>
            <version>21</version>
        </provides>
        <configuration>
            <jdkHome>C:\Users\markr\.jdks\openjdk-21</jdkHome>
        </configuration>
    </toolchain>
</toolchains>
```

**Purpose**: Tells Maven where to find different JDK versions on your system.

### 2. `pom.xml` - Toolchain Request

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-toolchains-plugin</artifactId>
    <configuration>
        <toolchains>
            <jdk>
                <version>21</version>  <!-- Request Java 21 -->
            </jdk>
        </toolchains>
    </configuration>
</plugin>
```

**Purpose**: Declares which JDK version the project requires.

## How Forking Works

### Compiler Plugin (maven-compiler-plugin)

When Maven compiles code:

1. **Reads toolchain**: Gets Java 21 path from build context
2. **Forks process**: Launches `C:\Users\markr\.jdks\openjdk-21\bin\javac`
3. **Passes arguments**: Source files, classpath, `--release 21`
4. **Waits for completion**: Collects compilation results
5. **Returns to Maven**: Maven continues with next phase

**Evidence from logs**:
```
[INFO] Toolchain in maven-compiler-plugin: JDK[C:\Users\markr\.jdks\openjdk-21]
[INFO] Compiling 45 source files with javac [forked debug release 21]
```

### Surefire Plugin (maven-surefire-plugin)

When Maven runs tests:

1. **Creates temporary JAR**: `surefirebooter-*.jar` with test classpath
2. **Builds command line**: 
   ```
   C:\Users\markr\.jdks\openjdk-21\bin\java.exe
   -javaagent:jacoco-agent.jar
   -XX:+EnableDynamicAgentLoading
   -jar surefirebooter-*.jar
   ```
3. **Forks process**: Launches new JVM with Java 21
4. **Runs tests**: JUnit executes in forked JVM
5. **Collects results**: Maven receives test results via IPC

**Evidence from logs**:
```
[INFO] Toolchain in maven-surefire-plugin: JDK[C:\Users\markr\.jdks\openjdk-21]
[DEBUG] Forking command line: ... -jar surefirebooter-*.jar
```

## Why This Solves the JaCoCo Problem

### Before (Java 24):
```
Maven (Java 24) → Tests run in Java 24 → JaCoCo tries to instrument Java 24 bytecode
                                       → IllegalClassFormatException (unsupported version 68)
```

### After (Java 21 via Toolchains):
```
Maven (Java 24) → Forks Java 21 → Tests run in Java 21 → JaCoCo instruments Java 21 bytecode
                                                        → ✅ Success! (version 65 supported)
```

## Key Benefits

1. **Flexibility**: Use latest Maven features (requires newer Java) while targeting older Java versions
2. **Consistency**: Ensure all developers use the same JDK version for builds
3. **CI/CD**: Build server can have multiple JDKs, Maven picks the right one
4. **Tool Compatibility**: Avoid issues like JaCoCo not supporting bleeding-edge Java versions

## Verification

Check which JDK is being used:

```bash
# Maven's JDK
mvn -version
# Output: Java version: 24

# Compilation JDK
mvn compile -X | grep "Toolchain in maven-compiler-plugin"
# Output: Toolchain in maven-compiler-plugin: JDK[C:\Users\markr\.jdks\openjdk-21]

# Test JDK
mvn test -X | grep "Toolchain in maven-surefire-plugin"
# Output: Toolchain in maven-surefire-plugin: JDK[C:\Users\markr\.jdks\openjdk-21]
```

## References

- [Maven Toolchains Documentation](https://maven.apache.org/guides/mini/guide-using-toolchains.html)
- [Maven Compiler Plugin - Using Different JDK](https://maven.apache.org/plugins/maven-compiler-plugin/examples/compile-using-different-jdk.html)
- [Maven Surefire Plugin - Forking](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html)

