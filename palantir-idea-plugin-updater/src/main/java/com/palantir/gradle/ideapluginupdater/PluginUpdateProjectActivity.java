package com.palantir.gradle.ideapluginupdater;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.PluginNode;
import com.intellij.ide.plugins.RepositoryHelper;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.updateSettings.impl.PluginDownloader;
import com.intellij.util.Alarm;
import com.intellij.util.text.VersionComparatorUtil;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PluginUpdateProjectActivity implements ProjectActivity, Disposable {
    private static final Logger log = LoggerFactory.getLogger(PluginUpdateProjectActivity.class);
    private static final int INITIAL_DELAY_MS = 20_000;
    private static final int CHECK_INTERVAL_MS = 20_000;

    @Override
    public final @NotNull Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        scheduleUpdateCheck(project, alarm, INITIAL_DELAY_MS);
        return Unit.INSTANCE;
    }

    private void scheduleUpdateCheck(Project project, Alarm alarm, int delay) {
        alarm.addRequest(
                () -> {
                    runUpdateCheck(project);
                    if (!project.isDisposed()) {
                        scheduleUpdateCheck(project, alarm, CHECK_INTERVAL_MS);
                    }
                },
                delay);
    }

    private void runUpdateCheck(Project project) {
        Set<String> failedPlugins = Stream.of(PluginManagerCore.getPlugins())
                .filter(PluginDescriptor::isEnabled)
                .filter(plugin ->
                        plugin.getVendor() != null && plugin.getVendor().contains("Palantir"))
                .map(plugin -> updateIfNeeded(plugin, project))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (!failedPlugins.isEmpty()) {
            notifyOfFailedPlugins(project, failedPlugins);
        }
    }

    private void notifyOfFailedPlugins(Project project, Set<String> failedPlugins) {
        String message = "The following plugins failed to update:\n" + String.join("\n", failedPlugins);
        ApplicationManager.getApplication().invokeLater(() -> {
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("Update Palantir plugins")
                    .createNotification("Plugin update failures", message, NotificationType.ERROR);
            notification.addAction(new NotificationAction("Open settings to update") {
                @Override
                public void actionPerformed(AnActionEvent actionEvent, Notification notification) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(actionEvent.getProject(), "Plugins");
                    notification.expire();
                }
            });
            notification.notify(project);
        });
    }

    private String updateIfNeeded(IdeaPluginDescriptor plugin, Project project) {
        Set<PluginId> pluginIds = Set.of(plugin.getPluginId());
        PluginNode latestPlugin = RepositoryHelper.loadPlugins(pluginIds).stream()
                .max(Comparator.comparing(PluginNode::getVersion, VersionComparatorUtil::compare))
                .orElse(null);

        if (latestPlugin == null) {
            log.warn("No plugin found for pluginId: {}", plugin.getName());
            return null;
        }

        if (latestPlugin.getVersion().equals(plugin.getVersion())) {
            return null;
        }

        log.debug("Updating plugin {}", plugin.getName());
        boolean success = updatePlugin(latestPlugin, project);
        return success ? null : plugin.getName();
    }

    private boolean updatePlugin(PluginNode plugin, Project project) {
        CompletableFuture<Boolean> updateResult = new CompletableFuture<>();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Updating plugin", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    PluginDownloader downloader = PluginDownloader.createDownloader(plugin);
                    downloader.prepareToInstall(indicator);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            boolean result = downloader.installDynamically(null);
                            updateResult.complete(result);
                        } catch (IOException e) {
                            log.error("Error installing plugin dynamically: {}", plugin.getName(), e);
                            updateResult.complete(false);
                        }
                    });
                } catch (IOException e) {
                    log.error("Error updating plugin: {}", plugin.getName(), e);
                    updateResult.complete(false);
                }
            }
        });

        try {
            return updateResult.get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error waiting for plugin update result: {}", plugin.getName(), e);
            return false;
        }
    }

    @Override
    public void dispose() {}
}
