// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import static com.google.devtools.build.lib.syntax.Runtime.NONE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory.EnvironmentExtension;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.BazelLibrary;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import com.google.devtools.build.lib.syntax.Environment.Frame;
import com.google.devtools.build.lib.syntax.Environment.Phase;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkSemantics;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import com.google.devtools.build.lib.vfs.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Parser for WORKSPACE files.  Fills in an ExternalPackage.Builder
 */
public class WorkspaceFactory {
  private static final Pattern LEGAL_WORKSPACE_NAME = Pattern.compile("^\\p{Alpha}\\w*$");

  // List of static function added by #addWorkspaceFunctions. Used to trim them out from the
  // serialized list of variables bindings.
  private static final ImmutableList<String> STATIC_WORKSPACE_FUNCTIONS =
      ImmutableList.of(
          "workspace",
          "__embedded_dir__", // serializable so optional
          "__workspace_dir__", // serializable so optional
          "DEFAULT_SERVER_JAVABASE", // serializable so optional
          PackageFactory.PKG_CONTEXT);

  private final Package.Builder builder;

  private final Path installDir;
  private final Path workspaceDir;
  private final Mutability mutability;

  private final boolean allowOverride;
  private final RuleFactory ruleFactory;

  private final ImmutableMap<String, BaseFunction> workspaceFunctions;
  private final ImmutableList<EnvironmentExtension> environmentExtensions;

  // Values from the previous workspace file parts.
  // List of load statements
  private ImmutableMap<String, Extension> parentImportMap = ImmutableMap.of();
  // List of top level variable bindings
  private ImmutableMap<String, Object> parentVariableBindings = ImmutableMap.of();

  // Values accumulated up to the currently parsed workspace file part.
  // List of load statements
  private ImmutableMap<String, Extension> importMap = ImmutableMap.of();
  // List of top level variable bindings
  private ImmutableMap<String, Object> variableBindings = ImmutableMap.of();

  /**
   * @param builder a builder for the Workspace
   * @param ruleClassProvider a provider for known rule classes
   * @param mutability the Mutability for the current evaluation context
   */
  public WorkspaceFactory(
      Package.Builder builder,
      RuleClassProvider ruleClassProvider,
      ImmutableList<EnvironmentExtension> environmentExtensions,
      Mutability mutability) {
    this(builder, ruleClassProvider, environmentExtensions, mutability, true, null, null);
  }

  // TODO(bazel-team): document installDir
  /**
   * @param builder a builder for the Workspace
   * @param ruleClassProvider a provider for known rule classes
   * @param environmentExtensions the Skylark environment extensions
   * @param mutability the Mutability for the current evaluation context
   * @param installDir the install directory
   * @param workspaceDir the workspace directory
   */
  public WorkspaceFactory(
      Package.Builder builder,
      RuleClassProvider ruleClassProvider,
      ImmutableList<EnvironmentExtension> environmentExtensions,
      Mutability mutability,
      boolean allowOverride,
      @Nullable Path installDir,
      @Nullable Path workspaceDir) {
    this.builder = builder;
    this.mutability = mutability;
    this.installDir = installDir;
    this.workspaceDir = workspaceDir;
    this.allowOverride = allowOverride;
    this.environmentExtensions = environmentExtensions;
    this.ruleFactory = new RuleFactory(ruleClassProvider, AttributeContainer::new);
    this.workspaceFunctions = WorkspaceFactory.createWorkspaceFunctions(
        allowOverride, ruleFactory);
  }

  /**
   * Parses the given WORKSPACE file without resolving Skylark imports, using the default Skylark
   * semantics.
   */
  public void parse(ParserInputSource source)
      throws BuildFileContainsErrorsException, InterruptedException {
    parse(source, SkylarkSemantics.DEFAULT_SEMANTICS, null);
  }

  @VisibleForTesting
  public void parse(
      ParserInputSource source,
      SkylarkSemantics skylarkSemantics,
      @Nullable StoredEventHandler localReporter)
      throws BuildFileContainsErrorsException, InterruptedException {
    // This method is split in 2 so WorkspaceFileFunction can call the two parts separately and
    // do the Skylark load imports in between. We can't load skylark imports from
    // generate_workspace at the moment because it doesn't have access to skyframe, but that's okay
    // because most people are just using it to resolve Maven dependencies.
    if (localReporter == null) {
      localReporter = new StoredEventHandler();
    }
    BuildFileAST buildFileAST = BuildFileAST.parseBuildFile(source, localReporter);
    if (buildFileAST.containsErrors()) {
      throw new BuildFileContainsErrorsException(
          Label.EXTERNAL_PACKAGE_IDENTIFIER, "Failed to parse " + source.getPath());
    }
    execute(buildFileAST, null, skylarkSemantics, localReporter);
  }


  /**
   * Actually runs through the AST, calling the functions in the WORKSPACE file and adding rules
   * to the //external package.
   */
  public void execute(
      BuildFileAST ast,
      Map<String, Extension> importedExtensions,
      SkylarkSemantics skylarkSemantics)
      throws InterruptedException {
    Preconditions.checkNotNull(ast);
    Preconditions.checkNotNull(importedExtensions);
    execute(ast, importedExtensions, skylarkSemantics, new StoredEventHandler());
  }


  private void execute(
      BuildFileAST ast,
      @Nullable Map<String, Extension> importedExtensions,
      SkylarkSemantics skylarkSemantics,
      StoredEventHandler localReporter)
      throws InterruptedException {
    if (importedExtensions != null) {
      Map<String, Extension> map = new HashMap<>(parentImportMap);
      map.putAll(importedExtensions);
      importMap = ImmutableMap.copyOf(importedExtensions);
    } else {
      importMap = parentImportMap;
    }
    Environment workspaceEnv =
        Environment.builder(mutability)
            .setSemantics(skylarkSemantics)
            .setGlobals(BazelLibrary.GLOBALS)
            .setEventHandler(localReporter)
            .setImportedExtensions(importMap)
            .setPhase(Phase.WORKSPACE)
            .build();
    addWorkspaceFunctions(workspaceEnv, localReporter);
    for (Map.Entry<String, Object> binding : parentVariableBindings.entrySet()) {
      try {
        workspaceEnv.update(binding.getKey(), binding.getValue());
      } catch (EvalException e) {
        // This should never happen because everything was already evaluated.
        throw new IllegalStateException(e);
      }
    }
    if (!ast.exec(workspaceEnv, localReporter)) {
      localReporter.handle(Event.error("Error evaluating WORKSPACE file"));
    }

    // Save the list of variable bindings for the next part of the workspace file. The list of
    // variable bindings of interest are the global variable bindings that are defined by the user,
    // so not the workspace functions.
    // Workspace functions are not serializable and should not be passed over sky values. They
    // also have a package builder specific to the current part and should be reinitialized for
    // each workspace file.
    ImmutableMap.Builder<String, Object> bindingsBuilder = ImmutableMap.builder();
    Frame globals = workspaceEnv.getGlobals();
    for (String s : globals.getBindings().keySet()) {
      Object o = globals.get(s);
      if (!isAWorkspaceFunction(s, o)) {
        bindingsBuilder.put(s, o);
      }
    }
    variableBindings = bindingsBuilder.build();

    builder.addPosts(localReporter.getPosts());
    builder.addEvents(localReporter.getEvents());
    if (localReporter.hasErrors()) {
      builder.setContainsErrors();
    }
    localReporter.clear();
  }

  private boolean isAWorkspaceFunction(String name, Object o) {
    return STATIC_WORKSPACE_FUNCTIONS.contains(name) || (workspaceFunctions.get(name) == o);
  }

  private static boolean isLegalWorkspaceName(String name) {
    Matcher matcher = LEGAL_WORKSPACE_NAME.matcher(name);
    return matcher.matches();
  }

  /**
   * Adds the various values returned by the parsing of the previous workspace file parts. {@code
   * aPackage} is the package returned by the parent WorkspaceFileFunction, {@code importMap} is the
   * list of load statements imports computed by the parent WorkspaceFileFunction and {@code
   * variableBindings} the list of top level variable bindings of that same call.
   */
  public void setParent(
      Package aPackage,
      ImmutableMap<String, Extension> importMap,
      ImmutableMap<String, Object> bindings)
      throws NameConflictException, InterruptedException {
    this.parentVariableBindings = bindings;
    this.parentImportMap = importMap;
    builder.setWorkspaceName(aPackage.getWorkspaceName());
    // Transmit the content of the parent package to the new package builder.
    builder.addPosts(aPackage.getPosts());
    builder.addEvents(aPackage.getEvents());
    if (aPackage.containsErrors()) {
      builder.setContainsErrors();
    }
    builder.addRegisteredExecutionPlatformLabels(aPackage.getRegisteredExecutionPlatformLabels());
    builder.addRegisteredToolchainLabels(aPackage.getRegisteredToolchainLabels());
    for (Rule rule : aPackage.getTargets(Rule.class)) {
      try {
        // The old rule references another Package instance and we wan't to keep the invariant that
        // every Rule references the Package it is contained within
        Rule newRule = builder.createRule(
            rule.getLabel(),
            rule.getRuleClassObject(),
            rule.getLocation(),
            rule.getAttributeContainer());
        newRule.populateOutputFiles(NullEventHandler.INSTANCE, builder);
        if (rule.containsErrors()) {
          newRule.setContainsErrors();
        }
        builder.addRule(newRule);
      } catch (LabelSyntaxException e) {
        // This rule has already been created once, so it should have worked the second time, too
        throw new IllegalStateException(e);
      }
    }
  }

  @SkylarkSignature(
    name = "workspace",
    objectType = Object.class,
    returnType = SkylarkList.class,
    doc =
        "Sets the name for this workspace. Workspace names should be a Java-package-style "
            + "description of the project, using underscores as separators, e.g., "
            + "github.com/bazelbuild/bazel should use com_github_bazelbuild_bazel. Names must "
            + "start with a letter and can only contain letters, numbers, and underscores.",
    parameters = {@Param(name = "name", type = String.class, doc = "the name of the workspace.")},
    useAst = true,
    useEnvironment = true
  )
  private static final BuiltinFunction.Factory newWorkspaceFunction =
      new BuiltinFunction.Factory("workspace") {
        public BuiltinFunction create(boolean allowOverride, final RuleFactory ruleFactory) {
          if (allowOverride) {
            return new BuiltinFunction(
                "workspace", FunctionSignature.namedOnly("name"), BuiltinFunction.USE_AST_ENV) {
              public Object invoke(String name, FuncallExpression ast, Environment env)
                  throws EvalException, InterruptedException {
                if (!isLegalWorkspaceName(name)) {
                  throw new EvalException(
                      ast.getLocation(), name + " is not a legal workspace name");
                }
                String errorMessage = LabelValidator.validateTargetName(name);
                if (errorMessage != null) {
                  throw new EvalException(ast.getLocation(), errorMessage);
                }
                PackageFactory.getContext(env, ast).pkgBuilder.setWorkspaceName(name);
                Package.Builder builder = PackageFactory.getContext(env, ast).pkgBuilder;
                RuleClass localRepositoryRuleClass = ruleFactory.getRuleClass("local_repository");
                RuleClass bindRuleClass = ruleFactory.getRuleClass("bind");
                Map<String, Object> kwargs =
                    ImmutableMap.<String, Object>of("name", name, "path", ".");
                try {
                  // This effectively adds a "local_repository(name = "<ws>", path = ".")"
                  // definition to the WORKSPACE file.
                  WorkspaceFactoryHelper.createAndAddRepositoryRule(
                      builder, localRepositoryRuleClass, bindRuleClass, kwargs, ast);
                } catch (InvalidRuleException | NameConflictException | LabelSyntaxException e) {
                  throw new EvalException(ast.getLocation(), e.getMessage());
                }
                return NONE;
              }
            };
          } else {
            return new BuiltinFunction(
                "workspace", FunctionSignature.namedOnly("name"), BuiltinFunction.USE_AST) {
              public Object invoke(String name, FuncallExpression ast) throws EvalException {
                throw new EvalException(
                    ast.getLocation(),
                    "workspace() function should be used only at the top of the WORKSPACE file");
              }
            };
          }
        }
      };

  private static BuiltinFunction newBindFunction(final RuleFactory ruleFactory) {
    return new BuiltinFunction(
        "bind", FunctionSignature.namedOnly(1, "name", "actual"), BuiltinFunction.USE_AST_ENV) {
      public Object invoke(String name, String actual, FuncallExpression ast, Environment env)
          throws EvalException, InterruptedException {
        Label nameLabel;
        try {
          nameLabel = Label.parseAbsolute("//external:" + name);
          try {
            Package.Builder builder = PackageFactory.getContext(env, ast).pkgBuilder;
            RuleClass ruleClass = ruleFactory.getRuleClass("bind");
            WorkspaceFactoryHelper.addBindRule(
                builder,
                ruleClass,
                nameLabel,
                actual == null ? null : Label.parseAbsolute(actual),
                ast.getLocation(),
                ruleFactory.getAttributeContainer(ruleClass));
          } catch (RuleFactory.InvalidRuleException
              | Package.NameConflictException
              | LabelSyntaxException e) {
            throw new EvalException(ast.getLocation(), e.getMessage());
          }

        } catch (LabelSyntaxException e) {
          throw new EvalException(ast.getLocation(), e.getMessage());
        }
        return NONE;
      }
    };
  }

  @SkylarkSignature(
    name = "register_execution_platforms",
    objectType = Object.class,
    returnType = NoneType.class,
    doc = "Registers a platform so that it is available to execute actions.",
    extraPositionals =
        @Param(
          name = "platform_labels",
          type = SkylarkList.class,
          generic1 = String.class,
          doc = "The labels of the platforms to register."
        ),
    useAst = true,
    useEnvironment = true
  )
  private static final BuiltinFunction.Factory newRegisterExecutionPlatformsFunction =
      new BuiltinFunction.Factory("register_execution_platforms") {
        public BuiltinFunction create(final RuleFactory ruleFactory) {
          return new BuiltinFunction(
              "register_execution_platforms",
              FunctionSignature.POSITIONALS,
              BuiltinFunction.USE_AST_ENV) {
            public Object invoke(
                SkylarkList<String> platformLabels, FuncallExpression ast, Environment env)
                throws EvalException, InterruptedException {

              // Collect the platform labels.
              List<Label> platforms = new ArrayList<>();
              for (String rawLabel : platformLabels.getContents(String.class, "platform_labels")) {
                try {
                  platforms.add(Label.parseAbsolute(rawLabel));
                } catch (LabelSyntaxException e) {
                  throw new EvalException(
                      ast.getLocation(),
                      String.format(
                          "In register_execution_platforms: unable to parse platform label %s: %s",
                          rawLabel, e.getMessage()),
                      e);
                }
              }

              // Add to the package definition for later.
              Package.Builder builder = PackageFactory.getContext(env, ast).pkgBuilder;
              builder.addRegisteredExecutionPlatformLabels(platforms);

              return NONE;
            }
          };
        }
      };

  @SkylarkSignature(
    name = "register_toolchains",
    objectType = Object.class,
    returnType = NoneType.class,
    doc =
        "Registers a toolchain created with the toolchain() rule so that it is available for "
            + "toolchain resolution.",
    extraPositionals =
        @Param(
          name = "toolchain_labels",
          type = SkylarkList.class,
          generic1 = String.class,
          doc = "The labels of the toolchains to register."
        ),
    useAst = true,
    useEnvironment = true
  )
  private static final BuiltinFunction.Factory newRegisterToolchainsFunction =
      new BuiltinFunction.Factory("register_toolchains") {
        public BuiltinFunction create(final RuleFactory ruleFactory) {
          return new BuiltinFunction(
              "register_toolchains", FunctionSignature.POSITIONALS, BuiltinFunction.USE_AST_ENV) {
            public Object invoke(
                SkylarkList<String> toolchainLabels, FuncallExpression ast, Environment env)
                throws EvalException, InterruptedException {

              // Collect the toolchain labels.
              List<Label> toolchains = new ArrayList<>();
              for (String rawLabel :
                  toolchainLabels.getContents(String.class, "toolchain_labels")) {
                try {
                  toolchains.add(Label.parseAbsolute(rawLabel));
                } catch (LabelSyntaxException e) {
                  throw new EvalException(
                      ast.getLocation(),
                      String.format(
                          "In register_toolchains: unable to parse toolchain label %s: %s",
                          rawLabel, e.getMessage()),
                      e);
                }
              }

              // Add to the package definition for later.
              Package.Builder builder = PackageFactory.getContext(env, ast).pkgBuilder;
              builder.addRegisteredToolchainLabels(toolchains);

              return NONE;
            }
          };
        }
      };

  /**
   * Returns a function-value implementing the build rule "ruleClass" (e.g. cc_library) in the
   * specified package context.
   */
  private static BuiltinFunction newRuleFunction(
      final RuleFactory ruleFactory, final String ruleClassName, final boolean allowOverride) {
    return new BuiltinFunction(
        ruleClassName, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV) {
      public Object invoke(Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException, InterruptedException {
        try {
          Package.Builder builder = PackageFactory.getContext(env, ast).pkgBuilder;
          if (!allowOverride
              && kwargs.containsKey("name")
              && builder.targets.containsKey(kwargs.get("name"))) {
            throw new EvalException(
                ast.getLocation(),
                "Cannot redefine repository after any load statement in the WORKSPACE file"
                    + " (for repository '"
                    + kwargs.get("name")
                    + "')");
          }
          RuleClass ruleClass = ruleFactory.getRuleClass(ruleClassName);
          RuleClass bindRuleClass = ruleFactory.getRuleClass("bind");
          Rule rule =
              WorkspaceFactoryHelper.createAndAddRepositoryRule(
                  builder, ruleClass, bindRuleClass, kwargs, ast);
          if (!isLegalWorkspaceName(rule.getName())) {
            throw new EvalException(
                ast.getLocation(), rule + "'s name field must be a legal workspace name");
          }
        } catch (RuleFactory.InvalidRuleException
            | Package.NameConflictException
            | LabelSyntaxException e) {
          throw new EvalException(ast.getLocation(), e.getMessage());
        }
        return NONE;
      }
    };
  }

  private static ImmutableMap<String, BaseFunction> createWorkspaceFunctions(
      boolean allowOverride, RuleFactory ruleFactory) {
    Map<String, BaseFunction> map = new HashMap<>();
    map.put("bind", newBindFunction(ruleFactory));
    map.put(
        "register_execution_platforms", newRegisterExecutionPlatformsFunction.apply(ruleFactory));
    map.put("register_toolchains", newRegisterToolchainsFunction.apply(ruleFactory));
    for (String ruleClass : ruleFactory.getRuleClassNames()) {
      if (!map.containsKey(ruleClass)) {
        BaseFunction ruleFunction = newRuleFunction(ruleFactory, ruleClass, allowOverride);
        map.put(ruleClass, ruleFunction);
      }
    }
    return ImmutableMap.copyOf(map);
  }

  private void addWorkspaceFunctions(Environment workspaceEnv, StoredEventHandler localReporter) {
    try {
      workspaceEnv.setup("workspace", newWorkspaceFunction.apply(allowOverride, ruleFactory));
      for (Map.Entry<String, BaseFunction> function : workspaceFunctions.entrySet()) {
        workspaceEnv.update(function.getKey(), function.getValue());
      }
      if (installDir != null) {
        workspaceEnv.update("__embedded_dir__", installDir.getPathString());
      }
      if (workspaceDir != null) {
        workspaceEnv.update("__workspace_dir__", workspaceDir.getPathString());
      }
      File javaHome = new File(System.getProperty("java.home"));
      if (javaHome.getName().equalsIgnoreCase("jre")) {
        javaHome = javaHome.getParentFile();
      }
      workspaceEnv.update("DEFAULT_SERVER_JAVABASE", javaHome.toString());

      for (EnvironmentExtension extension : environmentExtensions) {
        extension.updateWorkspace(workspaceEnv);
      }
      workspaceEnv.setupDynamic(
          PackageFactory.PKG_CONTEXT,
          new PackageFactory.PackageContext(builder, null, localReporter, AttributeContainer::new));
    } catch (EvalException e) {
      throw new AssertionError(e);
    }
  }

  private static ClassObject newNativeModule(
      ImmutableMap<String, BaseFunction> workspaceFunctions, String version) {
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    Runtime.BuiltinRegistry builtins = Runtime.getBuiltinRegistry();
    for (String nativeFunction : builtins.getFunctionNames(SkylarkNativeModule.class)) {
      builder.put(nativeFunction, builtins.getFunction(SkylarkNativeModule.class, nativeFunction));
    }
    for (Map.Entry<String, BaseFunction> function : workspaceFunctions.entrySet()) {
      builder.put(function.getKey(), function.getValue());
    }

    builder.put("bazel_version", version);
    return NativeProvider.STRUCT.create(builder.build(), "no native function or rule '%s'");
  }

  static ClassObject newNativeModule(RuleClassProvider ruleClassProvider, String version) {
    RuleFactory ruleFactory = new RuleFactory(ruleClassProvider, AttributeContainer::new);
    return WorkspaceFactory.newNativeModule(
        WorkspaceFactory.createWorkspaceFunctions(false, ruleFactory), version);
  }

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(WorkspaceFactory.class);
  }

  public Map<String, Extension> getImportMap() {
    return importMap;
  }

  public Map<String, Object> getVariableBindings() {
    return variableBindings;
  }
}
