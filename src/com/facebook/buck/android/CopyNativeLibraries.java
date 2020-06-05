/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.android.common.SdkConstants;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A {@link BuildRule} that gathers shared objects generated by {@code ndk_library} and {@code
 * prebuilt_native_library} rules into a directory. It also hashes the shared objects collected and
 * stores this metadata in a text file, to be used later by {@link ExopackageInstaller}.
 */
public class CopyNativeLibraries extends AbstractBuildRule implements SupportsInputBasedRuleKey {
  @AddToRuleKey private final ImmutableSet<TargetCpuType> cpuFilters;
  @AddToRuleKey private final ImmutableSet<SourcePath> nativeLibDirectories;
  @AddToRuleKey private final ImmutableSet<SourcePath> nativeLibAssetDirectories;
  @AddToRuleKey private final ImmutableSet<StrippedObjectDescription> stripLibRules;
  @AddToRuleKey private final ImmutableSet<StrippedObjectDescription> stripLibAssetRules;
  @AddToRuleKey private final String moduleName;

  private final Supplier<SortedSet<BuildRule>> depsSupplier;

  protected CopyNativeLibraries(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<StrippedObjectDescription> strippedLibs,
      ImmutableSet<StrippedObjectDescription> strippedLibsAssets,
      ImmutableSet<SourcePath> nativeLibDirectories,
      ImmutableSet<SourcePath> nativeLibAssetDirectories,
      ImmutableSet<TargetCpuType> cpuFilters,
      String moduleName) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkArgument(
        !nativeLibDirectories.isEmpty()
            || !nativeLibAssetDirectories.isEmpty()
            || !strippedLibs.isEmpty()
            || !strippedLibsAssets.isEmpty(),
        "There should be at least one native library to copy.");
    this.nativeLibDirectories = nativeLibDirectories;
    this.nativeLibAssetDirectories = nativeLibAssetDirectories;
    this.stripLibRules = strippedLibs;
    this.stripLibAssetRules = strippedLibsAssets;
    this.cpuFilters = cpuFilters;
    this.moduleName = moduleName;
    this.depsSupplier =
        MoreSuppliers.memoize(
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  // TODO(cjhopman): This should be private and only exposed as a SourcePath.
  Path getPathToNativeLibsDir() {
    return getBinPath().resolve("libs");
  }

  SourcePath getSourcePathToNativeLibsDir() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToNativeLibsDir());
  }

  private Path getPathToNativeLibsAssetsDir() {
    return getBinPath().resolve("assetLibs");
  }

  SourcePath getSourcePathToNativeLibsAssetsDir() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToNativeLibsAssetsDir());
  }

  /**
   * Returns the path that is the immediate parent of {@link #getPathToNativeLibsAssetsDir()} and
   * {@link #getPathToNativeLibsDir()}.
   */
  private RelPath getPathToAllLibsDir() {
    return getBinPath();
  }

  SourcePath getSourcePathToAllLibsDir() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToAllLibsDir());
  }

  private Path getPathToMetadataTxt() {
    return getBinPath().resolve("metadata.txt");
  }

  SourcePath getSourcePathToMetadataTxt() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToMetadataTxt());
  }

  private RelPath getBinPath() {
    return BuildTargetPaths.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__native_" + moduleName + "_%s__");
  }

  @VisibleForTesting
  ImmutableSet<SourcePath> getNativeLibDirectories() {
    return nativeLibDirectories;
  }

  @VisibleForTesting
  ImmutableSet<StrippedObjectDescription> getStrippedObjectDescriptions() {
    return ImmutableSet.<StrippedObjectDescription>builder()
        .addAll(stripLibRules)
        .addAll(stripLibAssetRules)
        .build();
  }

  private void addStepsForCopyingStrippedNativeLibrariesOrAssets(
      BuildContext context,
      ProjectFilesystem filesystem,
      ImmutableSet<StrippedObjectDescription> strippedNativeLibrariesOrAssets,
      Path destinationRootDir,
      ImmutableList.Builder<Step> steps) {
    for (StrippedObjectDescription strippedObject : strippedNativeLibrariesOrAssets) {
      Optional<String> abiDirectoryComponent =
          getAbiDirectoryComponent(strippedObject.getTargetCpuType());
      Preconditions.checkState(abiDirectoryComponent.isPresent());

      Path destination =
          destinationRootDir
              .resolve(abiDirectoryComponent.get())
              .resolve(strippedObject.getStrippedObjectName());

      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  destination.getParent())));
      steps.add(
          CopyStep.forFile(
              filesystem,
              context
                  .getSourcePathResolver()
                  .getAbsolutePath(strippedObject.getSourcePath())
                  .getPath(),
              destination));
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getBinPath())));

    Path pathToNativeLibs = getPathToNativeLibsDir();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToNativeLibs)));

    Path pathToNativeLibsAssets = getPathToNativeLibsAssetsDir();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToNativeLibsAssets)));

    for (SourcePath nativeLibDir : nativeLibDirectories.asList().reverse()) {
      copyNativeLibrary(
          context,
          getProjectFilesystem(),
          context.getSourcePathResolver().getAbsolutePath(nativeLibDir),
          pathToNativeLibs,
          cpuFilters,
          steps);
    }

    for (SourcePath nativeLibDir : nativeLibAssetDirectories.asList().reverse()) {
      copyNativeLibrary(
          context,
          getProjectFilesystem(),
          context.getSourcePathResolver().getAbsolutePath(nativeLibDir),
          pathToNativeLibsAssets,
          cpuFilters,
          steps);
    }

    addStepsForCopyingStrippedNativeLibrariesOrAssets(
        context, getProjectFilesystem(), stripLibRules, pathToNativeLibs, steps);

    addStepsForCopyingStrippedNativeLibrariesOrAssets(
        context, getProjectFilesystem(), stripLibAssetRules, pathToNativeLibsAssets, steps);

    Path pathToMetadataTxt = getPathToMetadataTxt();
    steps.add(
        createMetadataStep(
            getProjectFilesystem(), getPathToMetadataTxt(), getPathToAllLibsDir().getPath()));

    buildableContext.recordArtifact(pathToNativeLibs);
    buildableContext.recordArtifact(pathToNativeLibsAssets);
    buildableContext.recordArtifact(pathToMetadataTxt);

    return steps.build();
  }

  static Step createMetadataStep(
      ProjectFilesystem filesystem, Path pathToMetadataTxt, Path pathToAllLibsDir) {
    return new AbstractExecutionStep("hash_native_libs") {
      @Override
      public StepExecutionResult execute(StepExecutionContext context) throws IOException {
        ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
        for (Path nativeLib : filesystem.getFilesUnderPath(pathToAllLibsDir)) {
          Sha1HashCode filesha1 = filesystem.computeSha1(nativeLib);
          Path relativePath = pathToAllLibsDir.relativize(nativeLib);
          metadataLines.add(String.format("%s %s", relativePath, filesha1));
        }
        filesystem.writeLinesToPath(metadataLines.build(), pathToMetadataTxt);
        return StepExecutionResults.SUCCESS;
      }
    };
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  static void copyNativeLibrary(
      BuildContext context,
      ProjectFilesystem filesystem,
      AbsPath sourceDir,
      Path destinationDir,
      ImmutableSet<TargetCpuType> cpuFilters,
      ImmutableList.Builder<Step> steps) {

    if (cpuFilters.isEmpty()) {
      steps.add(
          CopyStep.forDirectory(
              filesystem,
              sourceDir.getPath(),
              destinationDir,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    } else {
      for (TargetCpuType cpuType : cpuFilters) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());

        AbsPath libSourceDir = sourceDir.resolve(abiDirectoryComponent.get());
        Path libDestinationDir = destinationDir.resolve(abiDirectoryComponent.get());

        MkdirStep mkDirStep =
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), filesystem, libDestinationDir));
        CopyStep copyStep =
            CopyStep.forDirectory(
                filesystem,
                libSourceDir.getPath(),
                libDestinationDir,
                CopyStep.DirectoryMode.CONTENTS_ONLY);
        steps.add(
            new Step() {
              @Override
              public StepExecutionResult execute(StepExecutionContext context) throws IOException {
                // TODO(simons): Using a projectfilesystem here is almost definitely wrong.
                // This is because each library may come from different build rules, which may be in
                // different cells --- this check works by coincidence.
                if (!filesystem.exists(libSourceDir.getPath())) {
                  return StepExecutionResults.SUCCESS;
                }
                if (mkDirStep.execute(context).isSuccess()
                    && copyStep.execute(context).isSuccess()) {
                  return StepExecutionResults.SUCCESS;
                }
                return StepExecutionResults.ERROR;
              }

              @Override
              public String getShortName() {
                return "copy_native_libraries";
              }

              @Override
              public String getDescription(StepExecutionContext context) {
                ImmutableList.Builder<String> stringBuilder = ImmutableList.builder();
                stringBuilder.add(String.format("[ -d %s ]", libSourceDir.toString()));
                stringBuilder.add(mkDirStep.getDescription(context));
                stringBuilder.add(copyStep.getDescription(context));
                return Joiner.on(" && ").join(stringBuilder.build());
              }
            });
      }
    }

    // Rename native files named like "*-disguised-exe" to "lib*.so" so they will be unpacked
    // by the Android package installer.  Then they can be executed like normal binaries
    // on the device.
    steps.add(
        new AbstractExecutionStep("rename_native_executables") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            ImmutableSet.Builder<Path> executablesBuilder = ImmutableSet.builder();
            filesystem.walkRelativeFileTree(
                destinationDir,
                new SimpleFileVisitor<Path>() {
                  @Override
                  public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith("-disguised-exe")) {
                      executablesBuilder.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                  }
                });
            for (Path exePath : executablesBuilder.build()) {
              Path fakeSoPath =
                  Paths.get(
                      PathFormatter.pathWithUnixSeparators(exePath)
                          .replaceAll("/([^/]+)-disguised-exe$", "/lib$1.so"));
              filesystem.move(exePath, fakeSoPath);
            }
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  /**
   * Native libraries compiled for different CPU architectures are placed in the respective ABI
   * subdirectories, such as 'armeabi', 'armeabi-v7a', 'x86' and 'mips'. This looks at the cpu
   * filter and returns the correct subdirectory. If cpu filter is not present or not supported,
   * returns Optional.empty();
   */
  private static Optional<String> getAbiDirectoryComponent(TargetCpuType cpuType) {
    switch (cpuType) {
      case ARM:
        return Optional.of(SdkConstants.ABI_ARMEABI);
      case ARMV7:
        return Optional.of(SdkConstants.ABI_ARMEABI_V7A);
      case ARM64:
        return Optional.of(SdkConstants.ABI_ARM64_V8A);
      case X86:
        return Optional.of(SdkConstants.ABI_INTEL_ATOM);
      case X86_64:
        return Optional.of(SdkConstants.ABI_INTEL_ATOM64);
      case MIPS:
        return Optional.of(SdkConstants.ABI_MIPS);
      default:
        return Optional.empty();
    }
  }

  @BuckStyleValue
  public abstract static class StrippedObjectDescription implements AddsToRuleKey {
    @AddToRuleKey
    public abstract SourcePath getSourcePath();

    @AddToRuleKey
    public abstract String getStrippedObjectName();

    @AddToRuleKey
    public abstract TargetCpuType getTargetCpuType();

    public abstract APKModule getApkModule();
  }
}
