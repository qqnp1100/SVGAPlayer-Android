# JitPack JDK 17 Configuration Design

## Goal

Make JitPack run this project with JDK 17, matching Android Gradle Plugin 8.12.0 and the library module's Java/Kotlin target.

## Design

Add a root-level `jitpack.yml` containing only JitPack's JDK selection:

```yaml
jdk:
  - openjdk17
```

Do not override JitPack's default install command or change existing Gradle files. This keeps publication behavior unchanged while removing JDK auto-detection from the build path.

## Verification

- Confirm `jitpack.yml` is valid YAML and selects `openjdk17`.
- Confirm the Git diff contains only the intended JitPack configuration for the implementation step.
- Leave unrelated working-tree files untouched.
