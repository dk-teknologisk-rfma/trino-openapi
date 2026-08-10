---
name: publish-release
description: Use when publishing a new release of trino-openapi on this fork (dk-teknologisk-rfma/trino-openapi), since GitHub Actions workflows do not run here and releases must be built and published manually with the GitHub CLI.
---

# Publish a trino-openapi Release

This fork does not run GitHub Actions (forks disable workflows by default and
must be manually enabled per-repo, which hasn't been done here). `.github/workflows/release.yaml`
and its `mvn release:prepare`/`release:perform` + JReleaser pipeline will NOT
run automatically on push. Releases are built and published manually instead.

## Steps

1. **Set the version in `pom.xml`** (`<version>` near the top, under
   `<artifactId>trino-openapi</artifactId>`). Follow the existing convention:
   `<major>.<minor>-<trino-spi-version>`, e.g. `1.88-483` (Trino SPI version is
   `dep.trino.version` in the same pom). Bump minor version for new features/fixes;
   only bump the Trino SPI suffix when `dep.trino.version` itself changes.

2. **Build the deployable zip:**
   ```bash
   ./mvnw -q -o clean package -DskipTests
   ```
   The `trino-plugin` packaging (via the `io.airlift:airbase` parent) produces
   `target/trino-openapi-<version>.zip`. Skip `-DskipTests` if the user wants
   the test suite run first.

3. **Commit the version bump** (if not already committed) and make sure
   `git status` is clean and `origin/main` is up to date:
   ```bash
   git add pom.xml && git commit -m "Set release version to <version>"
   git push origin main
   ```

4. **Tag the release commit**, matching the `tagNameFormat` (`v@{project.version}`)
   configured for `maven-release-plugin` in `pom.xml`:
   ```bash
   git tag v<version>
   git push origin v<version>
   ```

5. **Create the GitHub release** with the zip attached, using the same repo
   this checkout points at (check with `git remote -v`):
   ```bash
   gh release create v<version> target/trino-openapi-<version>.zip \
     --repo dk-teknologisk-rfma/trino-openapi \
     --title "v<major.minor> for Trino <trino-spi-version>" \
     --notes "<short summary of what changed>"
   ```
   Check `gh release list --repo dk-teknologisk-rfma/trino-openapi` and
   `gh release view <previous-tag> --repo dk-teknologisk-rfma/trino-openapi`
   first to match the existing title/notes style.

## Notes

- `pom.xml`'s `<version>` does not auto-bump; it must be edited manually before
  each release.
- If Actions are ever enabled on this fork, re-check whether `release.yaml`
  would now run on push and could conflict with this manual process.
