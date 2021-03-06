// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.execution.test.runner;

import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.TestData;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.plugins.gradle.execution.GradleRunnerUtil;
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.TestRunner;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jetbrains.plugins.gradle.util.GradleUtil;
import org.jetbrains.plugins.gradle.util.TasksToRun;

import java.util.*;

import static org.jetbrains.plugins.gradle.execution.test.runner.TestGradleConfigurationProducerUtilKt.escapeIfNeeded;
import static org.jetbrains.plugins.gradle.settings.TestRunner.*;

/**
 * @author Vladislav.Soroka
 */
public abstract class GradleTestRunConfigurationProducer extends RunConfigurationProducer<ExternalSystemRunConfiguration> {

  protected static final Logger LOG = Logger.getInstance(GradleTestRunConfigurationProducer.class);

  private TestTasksChooser testTasksChooser = new TestTasksChooser();

  protected GradleTestRunConfigurationProducer() {
    super(true);
  }

  @Override
  public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
    TestRunner testRunner = getTestRunner(self.getSourceElement());
    return testRunner == CHOOSE_PER_TEST || testRunner == GRADLE;
  }

  @Override
  public boolean shouldReplace(@NotNull ConfigurationFromContext self, @NotNull ConfigurationFromContext other) {
    return getTestRunner(self.getSourceElement()) == GRADLE;
  }

  @Override
  protected boolean setupConfigurationFromContext(@NotNull ExternalSystemRunConfiguration configuration,
                                                  @NotNull ConfigurationContext context,
                                                  @NotNull Ref<PsiElement> sourceElement) {
    if (!GradleConstants.SYSTEM_ID.equals(configuration.getSettings().getExternalSystemId())) return false;

    if (sourceElement.isNull()) return false;
    TestRunner testRunner = getTestRunner(sourceElement.get());
    if (testRunner == PLATFORM) return false;
    if (configuration instanceof GradleRunConfiguration) {
      final GradleRunConfiguration gradleRunConfiguration = (GradleRunConfiguration)configuration;
      gradleRunConfiguration.setScriptDebugEnabled(false);
    }
    boolean result = doSetupConfigurationFromContext(configuration, context, sourceElement);
    restoreDefaultScriptParametersIfNeeded(configuration, context);
    return result;
  }

  protected Runnable addCheckForTemplateParams(@NotNull ConfigurationFromContext configuration,
                         @NotNull ConfigurationContext context,
                         @NotNull Runnable startRunnable) {
    return () -> {
      restoreDefaultScriptParametersIfNeeded(configuration.getConfiguration(), context);
      startRunnable.run();
    };
  }

  protected void restoreDefaultScriptParametersIfNeeded(@NotNull RunConfiguration configuration,
                                                        @NotNull ConfigurationContext context) {
    RunnerAndConfigurationSettings template = context.getRunManager().getConfigurationTemplate(getConfigurationFactory());
    final RunConfiguration original = template.getConfiguration();
    if (original instanceof ExternalSystemRunConfiguration
        && configuration instanceof ExternalSystemRunConfiguration) {
      ExternalSystemRunConfiguration originalRC = (ExternalSystemRunConfiguration)original;
      ExternalSystemRunConfiguration configurationRC = (ExternalSystemRunConfiguration)configuration;
      String currentParams = configurationRC.getSettings().getScriptParameters();
      String defaultParams = originalRC.getSettings().getScriptParameters();

      if (!StringUtil.isEmptyOrSpaces(defaultParams)) {
        if (!StringUtil.isEmptyOrSpaces(currentParams)) {
          configurationRC.getSettings().setScriptParameters(currentParams + " " + defaultParams);
        } else {
          configurationRC.getSettings().setScriptParameters(defaultParams);
        }
      }
    }
  }


  protected abstract boolean doSetupConfigurationFromContext(ExternalSystemRunConfiguration configuration,
                                                             ConfigurationContext context,
                                                             Ref<PsiElement> sourceElement);

  @Override
  public boolean isConfigurationFromContext(@NotNull ExternalSystemRunConfiguration configuration, @NotNull ConfigurationContext context) {
    if (!GradleConstants.SYSTEM_ID.equals(configuration.getSettings().getExternalSystemId())) return false;

    String projectPath = configuration.getSettings().getExternalProjectPath();
    TestRunner testRunner = getTestRunner(context.getProject(), projectPath);
    if (testRunner == PLATFORM) return false;
    return doIsConfigurationFromContext(configuration, context);
  }

  protected abstract boolean doIsConfigurationFromContext(ExternalSystemRunConfiguration configuration, ConfigurationContext context);

  @Nullable
  protected String resolveProjectPath(@NotNull Module module) {
    return GradleRunnerUtil.resolveProjectPath(module);
  }

  protected TestTasksChooser getTestTasksChooser() {
    return testTasksChooser;
  }

  @TestOnly
  public void setTestTasksChooser(TestTasksChooser testTasksChooser) {
    this.testTasksChooser = testTasksChooser;
  }

  public static boolean hasTasksInConfiguration(VirtualFile source, Project project, ExternalSystemTaskExecutionSettings settings) {
    List<TasksToRun> tasksToRun = findAllTestsTaskToRun(source, project);
    List<List<String>> escapedTasks = ContainerUtil.map(tasksToRun, tasks -> ContainerUtil.map(tasks, it -> escapeIfNeeded(it)));
    List<String> taskNames = settings.getTaskNames();
    if (escapedTasks.stream().anyMatch(taskNames::containsAll)) return true;
    String scriptParameters = settings.getScriptParameters();
    if (StringUtil.isEmpty(scriptParameters)) return false;
    List<String> escapedJoinedTasks = ContainerUtil.map(escapedTasks, it -> StringUtil.join(it, " "));
    return escapedJoinedTasks.stream().anyMatch(scriptParameters::contains);
  }

  /**
   * Finds any of possible tasks to run tests for specified source
   *
   * @param source  is a file or directory for find in source set
   * @param project is a project with the source
   * @return any of possible tasks to run tests for specified source
   */
  @NotNull
  public static TasksToRun findTestsTaskToRun(@NotNull VirtualFile source, @NotNull Project project) {
    List<TasksToRun> tasksToRun = findAllTestsTaskToRun(source, project);
    if (tasksToRun.isEmpty()) return TasksToRun.EMPTY;
    return tasksToRun.get(0);
  }

  /**
   * Finds all of possible tasks to run tests for specified source
   *
   * @param source  is a file or directory for find in source set
   * @param project is a project with the source
   * @return all of possible tasks to run tests for specified source
   */
  @NotNull
  public static List<TasksToRun> findAllTestsTaskToRun(@NotNull VirtualFile source, @NotNull Project project) {
    String sourcePath = source.getPath();
    ProjectFileIndex projectFileIndex = ProjectFileIndex.SERVICE.getInstance(project);
    Module module = projectFileIndex.getModuleForFile(source);
    if (module == null) return Collections.emptyList();
    List<TasksToRun> testTasks = new ArrayList<>();
    for (GradleTestTasksProvider provider : GradleTestTasksProvider.EP_NAME.getExtensions()) {
      List<String> tasks = provider.getTasks(module, source);
      if (!ContainerUtil.isEmpty(tasks)) {
        String testName = StringUtil.join(tasks, " ");
        testTasks.add(new TasksToRun.Impl(testName, tasks));
      }
    }
    DataNode<ModuleData> moduleDataNode = GradleUtil.findGradleModuleData(module);
    if (moduleDataNode == null) return testTasks;
    Collection<DataNode<TestData>> testsData = ExternalSystemApiUtil.findAll(moduleDataNode, ProjectKeys.TEST);
    for (DataNode<TestData> testDataNode : testsData) {
      TestData testData = testDataNode.getData();
      Set<String> sourceFolders = testData.getSourceFolders();
      for (String sourceFolder : sourceFolders) {
        if (FileUtil.isAncestor(sourceFolder, sourcePath, false)) {
          String testName = testData.getTestName();
          String testTaskName = testData.getTestTaskName();
          List<String> tasks = new SmartList<>(testTaskName);
          testTasks.add(new TasksToRun.Impl(testName, tasks));
        }
      }
    }
    return testTasks;
  }

  private static TestRunner getTestRunner(@NotNull Project project, @NotNull String projectPath) {
    return GradleProjectSettings.getTestRunner(project, projectPath);
  }

  private static TestRunner getTestRunner(@NotNull PsiElement sourceElement) {
    Module module = ModuleUtilCore.findModuleForPsiElement(sourceElement);
    if (module == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Cannot find module for %s", sourceElement.toString()), new Throwable());
      }
      return PLATFORM;
    }
    return GradleProjectSettings.getTestRunner(module);
  }
}
