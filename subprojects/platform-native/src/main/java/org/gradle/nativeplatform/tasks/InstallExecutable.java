/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.nativeplatform.tasks;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Incubating;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryVar;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileVar;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.platform.base.ToolChain;
import org.gradle.util.GFileUtils;

import javax.inject.Inject;
import java.io.File;

/**
 * Installs an executable with it's dependent libraries so it can be easily executed.
 */
@Incubating
public class InstallExecutable extends DefaultTask {
    private ToolChain toolChain;
    private NativePlatform platform;
    private final DirectoryVar destinationDir;
    private final RegularFileVar executable;
    private final ConfigurableFileCollection libs;
    private final WorkerLeaseService workerLeaseService;

    /**
     * Injects a {@link WorkerLeaseService} instance.
     *
     * @since 4.2
     */
    @Inject
    public InstallExecutable(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
        this.libs = getProject().files();
        destinationDir = newOutputDirectory();
        executable = newInputFile();
    }

    /**
     * The tool chain used for linking.
     */
    @Internal
    public ToolChain getToolChain() {
        return toolChain;
    }

    public void setToolChain(ToolChain toolChain) {
        this.toolChain = toolChain;
    }

    /**
     * The platform describing the install target.
     */
    @Nested
    public NativePlatform getPlatform() {
        return platform;
    }

    public void setPlatform(NativePlatform platform) {
        this.platform = platform;
    }

    /**
     * The directory to install files into.
     *
     * @since 4.1
     */
    @OutputDirectory
    public DirectoryVar getInstallDirectory() {
        return destinationDir;
    }

    @Internal
    public File getDestinationDir() {
        return destinationDir.getAsFile().getOrNull();
    }

    public void setDestinationDir(File destinationDir) {
        this.destinationDir.set(destinationDir);
    }

    /**
     * Sets the destination directory to install the executable via a {@link Provider}
     *
     * @param destinationDir the destination directory provider to use
     * @see #setDestinationDir(File)
     * @since 4.1
     */
    public void setDestinationDir(Provider<? extends Directory> destinationDir) {
        this.destinationDir.set(destinationDir);
    }

    /**
     * The executable file to install.
     *
     * @since 4.1
     */
    @Internal("Covered by inputFileIfExists")
    public RegularFileVar getSourceFile() {
        return executable;
    }

    @Internal("Covered by inputFileIfExists")
    public File getExecutable() {
        return executable.getAsFile().getOrNull();
    }

    public void setExecutable(File executable) {
        this.executable.set(executable);
    }

    /**
     * Sets the executable to install via a {@link Provider}
     *
     * @param executable the executable provider to use
     * @see #setExecutable(File)
     * @since 4.1
     */
    public void setExecutable(Provider<? extends RegularFile> executable) {
        this.executable.set(executable);
    }

    /**
     * Workaround for when the task is given an input file that doesn't exist
     *
     * @since 4.3
     */
    @SkipWhenEmpty
    @Optional
    @InputFile
    protected File getInputFileIfExists() {
        File inputFile = getExecutable();
        if (inputFile != null && inputFile.exists()) {
            return inputFile;
        } else {
            return null;
        }
    }

    /**
     * The library files that should be installed.
     */
    @InputFiles
    public FileCollection getLibs() {
        return libs;
    }

    public void setLibs(FileCollection libs) {
        this.libs.setFrom(libs);
    }

    /**
     * Adds a set of library files to be installed. The provided libs object is evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     */
    public void lib(Object libs) {
        this.libs.from(libs);
    }

    /**
     * Returns the script file that can be used to run the install image.
     */
    @Internal
    public File getRunScript() {
        OperatingSystem operatingSystem = OperatingSystem.forName(platform.getOperatingSystem().getName());
        return new File(getDestinationDir(), operatingSystem.getScriptName(getExecutable().getName()));
    }

    @Inject
    protected FileSystem getFileSystem() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected FileOperations getFileOperations() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    public void install() {
        // TODO: Migrate this to the worker API once the FileSystem and FileOperations services can be injected
        workerLeaseService.withoutProjectLock(new Runnable() {
            @Override
            public void run() {
                if (platform.getOperatingSystem().isWindows()) {
                    installWindows();
                } else {
                    installUnix();
                }
            }
        });
    }

    private void installWindows() {
        final File destination = getDestinationDir();
        final File executable = getExecutable();

        installToDir(new File(destination, "lib"));

        StringBuilder toolChainPath = new StringBuilder();
        if (toolChain instanceof Gcc) {
            // Gcc on windows requires the path to be set
            toolChainPath.append("SET PATH=");
            for (File pathEntry : ((Gcc) toolChain).getPath()) {
                toolChainPath.append(pathEntry.getAbsolutePath()).append(";");
            }

            toolChainPath.append("%PATH%");
        }

        String runScriptText =
              "\n@echo off"
            + "\nSETLOCAL"
            + "\n" + toolChainPath
            + "\nCALL \"%~dp0lib\\" + executable.getName() + "\" %*"
            + "\nEXIT /B %ERRORLEVEL%"
            + "\nENDLOCAL"
            + "\n";
        GFileUtils.writeFile(runScriptText, getRunScript());
    }

    private void installUnix() {
        final File destination = getDestinationDir();
        final File executable = getExecutable();

        installToDir(new File(destination, "lib"));

        String runScriptText =
              "#!/bin/sh"
            + "\nAPP_BASE_NAME=`dirname \"$0\"`"
            + "\nDYLD_LIBRARY_PATH=\"$APP_BASE_NAME/lib\""
            + "\nexport DYLD_LIBRARY_PATH"
            + "\nLD_LIBRARY_PATH=\"$APP_BASE_NAME/lib\""
            + "\nexport LD_LIBRARY_PATH"
            + "\nexec \"$APP_BASE_NAME/lib/" + executable.getName() + "\" \"$@\""
            + "\n";
        GFileUtils.writeFile(runScriptText, getRunScript());

        getFileSystem().chmod(getRunScript(), 0755);
    }

    private void installToDir(final File binaryDir) {
        getFileOperations().sync(new Action<CopySpec>() {
            public void execute(CopySpec copySpec) {
                copySpec.into(binaryDir);
                copySpec.from(getExecutable());
                copySpec.from(getLibs());
            }

        });
    }
}
