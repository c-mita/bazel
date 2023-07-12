// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.bazel.bzlmod.modquery.ModqueryOptions.Charset.UTF8;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ExtensionArg;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ExtensionArg.ExtensionArgConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.InvalidArgumentException;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ModqueryExecutor;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ModqueryOptions;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ModqueryOptions.QueryType;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ModqueryOptions.QueryTypeConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ModuleArg;
import com.google.devtools.build.lib.bazel.bzlmod.modquery.ModuleArg.ModuleArgConverter;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.ModqueryCommand.Code;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.MaybeCompleteSet;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;

/** Queries the Bzlmod external dependency graph. */
@Command(
    name = ModqueryCommand.NAME,
    // TODO(andreisolo): figure out which extra options are really needed
    options = {
      ModqueryOptions.class,
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    help = "resource:modquery.txt",
    shortDescription = "Queries the Bzlmod external dependency graph",
    allowResidue = true)
public final class ModqueryCommand implements BlazeCommand {

  public static final String NAME = "modquery";

  @Override
  public void editOptions(OptionsParser optionsParser) {
    try {
      optionsParser.parse(
          PriorityCategory.SOFTWARE_REQUIREMENT,
          "Option required by the modquery command",
          ImmutableList.of("--enable_bzlmod"));
    } catch (OptionsParsingException e) {
      // Should never happen.
      throw new IllegalStateException("Unexpected exception", e);
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BazelDepGraphValue depGraphValue;
    BazelModuleInspectorValue moduleInspector;

    SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();

    try {
      env.syncPackageLoading(options);

      EvaluationResult<SkyValue> evaluationResult =
          skyframeExecutor.prepareAndGet(
              ImmutableSet.of(BazelDepGraphValue.KEY, BazelModuleInspectorValue.KEY),
              evaluationContext);

      if (evaluationResult.hasError()) {
        Exception e = evaluationResult.getError().getException();
        String message = "Unexpected error during repository rule evaluation.";
        if (e != null) {
          message = e.getMessage();
        }
        return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS);
      }

      depGraphValue = (BazelDepGraphValue) evaluationResult.get(BazelDepGraphValue.KEY);

      moduleInspector =
          (BazelModuleInspectorValue) evaluationResult.get(BazelModuleInspectorValue.KEY);

    } catch (InterruptedException e) {
      String errorMessage = "Modquery interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    } catch (AbruptExitException e) {
      env.getReporter().handle(Event.error(null, "Unknown error: " + e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    }

    ModqueryOptions modqueryOptions = options.getOptions(ModqueryOptions.class);
    Preconditions.checkArgument(modqueryOptions != null);

    if (options.getResidue().isEmpty()) {
      String errorMessage =
          String.format("No query type specified, choose one of : %s.", QueryType.printValues());
      return reportAndCreateFailureResult(env, errorMessage, Code.MODQUERY_COMMAND_UNKNOWN);
    }

    // The first keyword in the residue must be the QueryType, and then comes a list of "arguments".
    String queryInput = options.getResidue().get(0);
    QueryType query;
    try {
      query = new QueryTypeConverter().convert(queryInput);
    } catch (OptionsParsingException e) {
      String errorMessage =
          String.format("Invalid query type, choose one from : %s.", QueryType.printValues());
      return reportAndCreateFailureResult(env, errorMessage, Code.MODQUERY_COMMAND_UNKNOWN);
    }
    List<String> args = options.getResidue().subList(1, options.getResidue().size());

    // Extract and check the --base_module argument first to use it when parsing the other args.
    // Can only be a TargetModule or a repoName relative to the ROOT.
    ModuleKey baseModuleKey;
    AugmentedModule rootModule = moduleInspector.getDepGraph().get(ModuleKey.ROOT);
    try {
      ImmutableSet<ModuleKey> keys =
          modqueryOptions.baseModule.resolveToModuleKeys(
              moduleInspector.getModulesIndex(),
              moduleInspector.getDepGraph(),
              rootModule.getDeps(),
              rootModule.getUnusedDeps(),
              false,
              false);
      if (keys.size() > 1) {
        throw new InvalidArgumentException(
            String.format(
                "The --base_module option can only specify exactly one module version, choose one"
                    + " of: %s.",
                keys.stream().map(ModuleKey::toString).collect(joining(", "))),
            Code.INVALID_ARGUMENTS);
      }
      baseModuleKey = Iterables.getOnlyElement(keys);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "In --base_module %s option: %s (Note that unused modules cannot be used here)",
              modqueryOptions.baseModule, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    // The args can have different types depending on the query type, so create multiple
    // containers which can be filled accordingly.
    ImmutableSet<ModuleKey> argsAsModules = null;
    ImmutableSortedSet<ModuleExtensionId> argsAsExtensions = null;
    ImmutableMap<String, RepositoryName> argsAsRepos = null;

    AugmentedModule baseModule =
        Objects.requireNonNull(moduleInspector.getDepGraph().get(baseModuleKey));
    RepositoryMapping baseModuleMapping = depGraphValue.getFullRepoMapping(baseModuleKey);
    try {
      switch (query) {
        case TREE:
          // TREE doesn't take extra arguments.
          if (!args.isEmpty()) {
            throw new InvalidArgumentException(
                "the 'tree' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS);
          }
          break;
        case SHOW:
          ImmutableMap.Builder<String, RepositoryName> targetToRepoName =
              new ImmutableMap.Builder<>();
          for (String arg : args) {
            try {
              targetToRepoName.putAll(
                  ModuleArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToRepoNames(
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          baseModuleMapping));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format(
                      "In repo argument %s: %s (Note that unused modules cannot be used here)",
                      arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS,
                  e);
            }
          }
          argsAsRepos = targetToRepoName.buildKeepingLast();
          break;
        case SHOW_EXTENSION:
          ImmutableSortedSet.Builder<ModuleExtensionId> extensionsBuilder =
              new ImmutableSortedSet.Builder<>(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
          for (String arg : args) {
            try {
              extensionsBuilder.add(
                  ExtensionArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToExtensionId(
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          baseModule.getDeps(),
                          baseModule.getUnusedDeps()));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format("In extension argument: %s %s", arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS,
                  e);
            }
          }
          argsAsExtensions = extensionsBuilder.build();
          break;
        default:
          ImmutableSet.Builder<ModuleKey> keysBuilder = new ImmutableSet.Builder<>();
          for (String arg : args) {
            try {
              keysBuilder.addAll(
                  ModuleArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToModuleKeys(
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          baseModule.getDeps(),
                          baseModule.getUnusedDeps(),
                          modqueryOptions.includeUnused,
                          /* warnUnused= */ true));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format("In module argument %s: %s", arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS);
            }
          }
          argsAsModules = keysBuilder.build();
      }
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(env, e.getMessage(), e.getCode());
    }
    /* Extract and check the --from and --extension_usages argument */
    ImmutableSet<ModuleKey> fromKeys;
    ImmutableSet<ModuleKey> usageKeys;
    try {
      fromKeys =
          moduleArgListToKeys(
              modqueryOptions.modulesFrom,
              moduleInspector.getModulesIndex(),
              moduleInspector.getDepGraph(),
              baseModule.getDeps(),
              baseModule.getUnusedDeps(),
              modqueryOptions.includeUnused);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format("In --from %s option: %s", modqueryOptions.modulesFrom, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    try {
      usageKeys =
          moduleArgListToKeys(
              modqueryOptions.extensionUsages,
              moduleInspector.getModulesIndex(),
              moduleInspector.getDepGraph(),
              baseModule.getDeps(),
              baseModule.getUnusedDeps(),
              modqueryOptions.includeUnused);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "In --extension_usages %s option: %s (Note that unused modules cannot be used"
                  + " here)",
              modqueryOptions.extensionUsages, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    /* Extract and check the --extension_filter argument */
    Optional<MaybeCompleteSet<ModuleExtensionId>> filterExtensions = Optional.empty();
    if (query.isGraph() && modqueryOptions.extensionFilter != null) {
      if (modqueryOptions.extensionFilter.isEmpty()) {
        filterExtensions = Optional.of(MaybeCompleteSet.completeSet());
      } else {
        try {
          filterExtensions =
              Optional.of(
                  MaybeCompleteSet.copyOf(
                      extensionArgListToIds(
                          modqueryOptions.extensionFilter,
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          baseModule.getDeps(),
                          baseModule.getUnusedDeps())));
        } catch (InvalidArgumentException e) {
          return reportAndCreateFailureResult(
              env,
              String.format(
                  "In --extension_filter %s option: %s",
                  modqueryOptions.extensionFilter, e.getMessage()),
              Code.INVALID_ARGUMENTS);
        }
      }
    }

    ImmutableMap<String, BzlmodRepoRuleValue> targetRepoRuleValues = null;
    try {
      // If the query is a SHOW, also request the BzlmodRepoRuleValues from SkyFactory.
      if (query == QueryType.SHOW) {
        ImmutableSet<SkyKey> skyKeys =
            argsAsRepos.values().stream().map(BzlmodRepoRuleValue::key).collect(toImmutableSet());
        EvaluationResult<SkyValue> result =
            env.getSkyframeExecutor().prepareAndGet(skyKeys, evaluationContext);
        if (result.hasError()) {
          Exception e = result.getError().getException();
          String message = "Unexpected error during repository rule evaluation.";
          if (e != null) {
            message = e.getMessage();
          }
          return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS);
        }
        targetRepoRuleValues =
            argsAsRepos.entrySet().stream()
                .collect(
                    toImmutableMap(
                        Entry::getKey,
                        e ->
                            (BzlmodRepoRuleValue)
                                result.get(BzlmodRepoRuleValue.key(e.getValue()))));
      }
    } catch (InterruptedException e) {
      String errorMessage = "Modquery interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    }

    // Workaround to allow different default value for DEPS and EXPLAIN, and also use
    // Integer.MAX_VALUE instead of the exact number string.
    if (modqueryOptions.depth < 1) {
      switch (query) {
        case EXPLAIN:
          modqueryOptions.depth = 1;
          break;
        case DEPS:
          modqueryOptions.depth = 2;
          break;
        default:
          modqueryOptions.depth = Integer.MAX_VALUE;
      }
    }

    ModqueryExecutor modqueryExecutor =
        new ModqueryExecutor(
            moduleInspector.getDepGraph(),
            depGraphValue.getExtensionUsagesTable(),
            moduleInspector.getExtensionToRepoInternalNames(),
            filterExtensions,
            modqueryOptions,
            new OutputStreamWriter(
                env.getReporter().getOutErr().getOutputStream(),
                modqueryOptions.charset == UTF8 ? UTF_8 : US_ASCII));

    switch (query) {
      case TREE:
        modqueryExecutor.tree(fromKeys);
        break;
      case DEPS:
        modqueryExecutor.tree(argsAsModules);
        break;
      case PATH:
        modqueryExecutor.path(fromKeys, argsAsModules);
        break;
      case ALL_PATHS:
      case EXPLAIN:
        modqueryExecutor.allPaths(fromKeys, argsAsModules);
        break;
      case SHOW:
        modqueryExecutor.show(targetRepoRuleValues);
        break;
      case SHOW_EXTENSION:
        modqueryExecutor.showExtension(argsAsExtensions, usageKeys);
        break;
    }

    return BlazeCommandResult.success();
  }

  /** Collects a list of {@link ModuleArg} into a set of {@link ModuleKey}s. */
  private static ImmutableSet<ModuleKey> moduleArgListToKeys(
      ImmutableList<ModuleArg> argList,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
      boolean includeUnused)
      throws InvalidArgumentException {
    ImmutableSet.Builder<ModuleKey> allTargetKeys = new ImmutableSet.Builder<>();
    for (ModuleArg moduleArg : argList) {
      allTargetKeys.addAll(
          moduleArg.resolveToModuleKeys(
              modulesIndex, depGraph, baseModuleDeps, baseModuleUnusedDeps, includeUnused, true));
    }
    return allTargetKeys.build();
  }

  private static ImmutableSortedSet<ModuleExtensionId> extensionArgListToIds(
      ImmutableList<ExtensionArg> args,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps)
      throws InvalidArgumentException {
    ImmutableSortedSet.Builder<ModuleExtensionId> extensionsBuilder =
        new ImmutableSortedSet.Builder<>(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
    for (ExtensionArg arg : args) {
      extensionsBuilder.add(
          arg.resolveToExtensionId(modulesIndex, depGraph, baseModuleDeps, baseModuleUnusedDeps));
    }
    return extensionsBuilder.build();
  }

  private static BlazeCommandResult reportAndCreateFailureResult(
      CommandEnvironment env, String message, Code detailedCode) {
    if (message.charAt(message.length() - 1) != '.') {
      message = message.concat(".");
    }
    String fullMessage =
        String.format(
            message.concat(" Type '%s help modquery' for syntax and help."),
            env.getRuntime().getProductName());
    env.getReporter().handle(Event.error(fullMessage));
    return createFailureResult(fullMessage, detailedCode);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.detailedExitCode(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setModqueryCommand(
                    FailureDetails.ModqueryCommand.newBuilder().setCode(detailedCode).build())
                .setMessage(message)
                .build()));
  }
}
