package io.quarkus.gradle.tasks;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.tools.ant.types.Commandline;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.options.Option;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.AppModelResolver;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.gradle.QuarkusPlugin;
import io.quarkus.gradle.QuarkusPluginExtension;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.utilities.JavaBinFinder;

public class QuarkusDev extends QuarkusTask {

    private Set<File> filesIncludedInClasspath = new HashSet<>();

    private File buildDir;

    private String sourceDir;

    private String workingDir;

    private List<String> jvmArgs;

    private boolean preventnoverify = false;

    private List<String> args = new LinkedList<String>();

    private List<String> compilerArgs = new LinkedList<>();

    public QuarkusDev() {
        super("Development mode: enables hot deployment with background compilation");
    }

    @InputDirectory
    @Optional
    public File getBuildDir() {
        if (buildDir == null) {
            buildDir = getProject().getBuildDir();
        }
        return buildDir;
    }

    public void setBuildDir(File buildDir) {
        this.buildDir = buildDir;
    }

    @Optional
    @InputDirectory
    public File getSourceDir() {
        if (sourceDir == null) {
            return extension().sourceDir();
        } else {
            return new File(sourceDir);
        }
    }

    @Option(description = "Set source directory", option = "source-dir")
    public void setSourceDir(String sourceDir) {
        this.sourceDir = sourceDir;
    }

    @Input
    // @InputDirectory this breaks kotlin projects, the working dir at this stage will be evaluated to 'classes/java/main' instead of 'classes/kotlin/main'
    public String getWorkingDir() {
        if (workingDir == null) {
            return extension().workingDir().toString();
        } else {
            return workingDir;
        }
    }

    @Option(description = "Set working directory", option = "working-dir")
    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    @Optional
    @Input
    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    @Option(description = "Set JVM arguments", option = "jvm-args")
    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    @Optional
    @Input
    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    @Option(description = "Set application arguments", option = "quarkus-args")
    public void setArgsString(String argsString) {
        this.setArgs(Arrays.asList(Commandline.translateCommandline(argsString)));
    }

    @Input
    public boolean isPreventnoverify() {
        return preventnoverify;
    }

    @Option(description = "value is intended to be set to true when some generated bytecode is" +
            " erroneous causing the JVM to crash when the verify:none option is set " +
            "(which is on by default)", option = "prevent-noverify")
    public void setPreventnoverify(boolean preventnoverify) {
        this.preventnoverify = preventnoverify;
    }

    @Optional
    @Input
    public List<String> getCompilerArgs() {
        return compilerArgs;
    }

    @Option(description = "Additional parameters to pass to javac when recompiling changed source files", option = "compiler-args")
    public void setCompilerArgs(List<String> compilerArgs) {
        this.compilerArgs = compilerArgs;
    }

    @TaskAction
    public void startDev() {
        Project project = getProject();
        QuarkusPluginExtension extension = (QuarkusPluginExtension) project.getExtensions().findByName("quarkus");

        if (!getSourceDir().isDirectory()) {
            throw new GradleException("The `src/main/java` directory is required, please create it.");
        }

        if (!extension().outputDirectory().isDirectory()) {
            throw new GradleException("The project has no output yet, " +
                    "this should not happen as build should have been executed first. " +
                    "Does the project have any source files?");
        }

        DevModeContext context = new DevModeContext();
        context.setProjectDir(project.getProjectDir());
        for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
            context.getSystemProperties().put(e.getKey().toString(), (String) e.getValue());
        }
        for (Map.Entry<String, ?> e : project.getProperties().entrySet()) {
            if (e.getValue() instanceof String) {
                context.getBuildSystemProperties().put(e.getKey(), e.getValue().toString());
            }
        }

        //  this is a minor hack to allow ApplicationConfig to be populated with defaults
        context.getBuildSystemProperties().putIfAbsent("quarkus.application.name", project.getName());
        if (project.getVersion() != null) {
            context.getBuildSystemProperties().putIfAbsent("quarkus.application.version", project.getVersion().toString());
        }

        context.setSourceEncoding(getSourceEncoding());
        try {
            List<String> args = new ArrayList<>();
            args.add(JavaBinFinder.findBin());
            final String debug = System.getProperty("debug");
            final String suspend = System.getProperty("suspend");
            String debugSuspend = "n";
            if (suspend != null) {
                switch (suspend.toLowerCase(Locale.ENGLISH)) {
                    case "n":
                    case "false": {
                        debugSuspend = "n";
                        break;
                    }
                    case "":
                    case "y":
                    case "true": {
                        debugSuspend = "y";
                        break;
                    }
                    default: {
                        System.err.println(
                                "Ignoring invalid value \"" + suspend + "\" for \"suspend\" param and defaulting to \"n\"");
                        break;
                    }
                }
            }
            if (debug == null) {
                // debug mode not specified
                // make sure 5005 is not used, we don't want to just fail if something else is using it
                try (Socket socket = new Socket(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 5005)) {
                    System.err.println("Port 5005 in use, not starting in debug mode");
                } catch (IOException e) {
                    args.add("-Xdebug");
                    args.add("-Xrunjdwp:transport=dt_socket,address=0.0.0.0:5005,server=y,suspend=" + debugSuspend);
                }
            } else if (debug.toLowerCase().equals("client")) {
                args.add("-Xdebug");
                args.add("-Xrunjdwp:transport=dt_socket,address=localhost:5005,server=n,suspend=" + debugSuspend);
            } else if (debug.toLowerCase().equals("true") || debug.isEmpty()) {
                args.add("-Xdebug");
                args.add("-Xrunjdwp:transport=dt_socket,address=0.0.0.0:5005,server=y,suspend=" + debugSuspend);
            } else if (!debug.toLowerCase().equals("false")) {
                try {
                    int port = Integer.parseInt(debug);
                    if (port <= 0) {
                        throw new GradleException("The specified debug port must be greater than 0");
                    }
                    args.add("-Xdebug");
                    args.add("-Xrunjdwp:transport=dt_socket,address=0.0.0.0:" + port + ",server=y,suspend=" + debugSuspend);
                } catch (NumberFormatException e) {
                    throw new GradleException(
                            "Invalid value for debug parameter: " + debug + " must be true|false|client|{port}");
                }
            }
            if (getJvmArgs() != null) {
                args.addAll(getJvmArgs());
            }

            // the following flags reduce startup time and are acceptable only for dev purposes
            args.add("-XX:TieredStopAtLevel=1");
            if (!isPreventnoverify()) {
                args.add("-Xverify:none");
            }

            //build a class-path string for the base platform
            //this stuff does not change
            // Do not include URIs in the manifest, because some JVMs do not like that
            StringBuilder classPathManifest = new StringBuilder();

            final AppModel appModel;
            final AppModelResolver modelResolver = extension().getAppModelResolver(LaunchMode.DEVELOPMENT);
            try {
                final AppArtifact appArtifact = extension.getAppArtifact();
                appArtifact.setPaths(QuarkusGradleUtils.getOutputPaths(project));
                appModel = modelResolver.resolveModel(appArtifact);
            } catch (AppModelResolverException e) {
                throw new GradleException("Failed to resolve application model " + extension.getAppArtifact() + " dependencies",
                        e);
            }

            args.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");

            final Set<AppArtifactKey> projectDependencies = new HashSet<>();
            addSelfWithLocalDeps(project, context, new HashSet<>(), projectDependencies, true);

            for (AppDependency appDependency : appModel.getFullDeploymentDeps()) {
                final AppArtifact appArtifact = appDependency.getArtifact();
                if (!projectDependencies.contains(new AppArtifactKey(appArtifact.getGroupId(), appArtifact.getArtifactId()))) {
                    appArtifact.getPaths().forEach(p -> {
                        if (Files.exists(p)) {
                            addToClassPaths(classPathManifest, p.toFile());
                        }
                    });
                }
            }

            //we also want to add the maven plugin jar to the class path
            //this allows us to just directly use classes, without messing around copying them
            //to the runner jar
            addGradlePluginDeps(classPathManifest, context);

            context.setCacheDir(new File(getBuildDir(), "transformer-cache").getAbsoluteFile());

            JavaPluginConvention javaPluginConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
            if (javaPluginConvention != null) {
                context.setSourceJavaVersion(javaPluginConvention.getSourceCompatibility().toString());
                context.setTargetJvmVersion(javaPluginConvention.getTargetCompatibility().toString());
            }

            if (getCompilerArgs().isEmpty()) {
                getJavaCompileTask()
                        .map(compileTask -> compileTask.getOptions().getCompilerArgs())
                        .ifPresent(context::setCompilerOptions);
            } else {
                context.setCompilerOptions(getCompilerArgs());
            }

            // this is the jar file we will use to launch the dev mode main class
            final File tempFile = new File(getBuildDir(), extension.finalName() + "-dev.jar");
            tempFile.delete();
            tempFile.deleteOnExit();

            context.setDevModeRunnerJarFile(tempFile);
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tempFile))) {
                out.putNextEntry(new ZipEntry("META-INF/"));
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPathManifest.toString());
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(out);

                out.putNextEntry(new ZipEntry(DevModeMain.DEV_MODE_CONTEXT));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes))) {
                    obj.writeObject(context);
                }
                out.write(bytes.toByteArray());

                out.putNextEntry(new ZipEntry(BootstrapConstants.SERIALIZED_APP_MODEL));
                bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes))) {
                    obj.writeObject(appModel);
                }
                out.write(bytes.toByteArray());
            }

            final Path serializedModel = QuarkusGradleUtils.serializeAppModel(appModel, this);
            serializedModel.toFile().deleteOnExit();
            args.add("-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());

            extension.outputDirectory().mkdirs();

            if (context.isEnablePreview()) {
                args.add(DevModeContext.ENABLE_PREVIEW_FLAG);
            }

            args.add("-jar");
            args.add(tempFile.getAbsolutePath());
            args.addAll(this.getArgs());
            if (getLogger().isDebugEnabled()) {
                getLogger().debug("Launching JVM with command line: {}", String.join(" ", args));
            }

            project.exec(action -> {
                action.commandLine(args).workingDir(getWorkingDir());
                action.setStandardInput(System.in)
                        .setErrorOutput(System.out)
                        .setStandardOutput(System.out);
            });
        } catch (Exception e) {
            throw new GradleException("Failed to run", e);
        }
    }

    private void addSelfWithLocalDeps(Project project, DevModeContext context, Set<String> visited,
            Set<AppArtifactKey> addedDeps, boolean root) {
        if (!visited.add(project.getPath())) {
            return;
        }
        final Configuration compileCp = project.getConfigurations().findByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
        if (compileCp != null) {
            compileCp.getIncoming().getDependencies().forEach(d -> {
                if (d instanceof ProjectDependency) {
                    addSelfWithLocalDeps(((ProjectDependency) d).getDependencyProject(), context, visited, addedDeps, false);
                }
            });
        }

        addLocalProject(project, context, addedDeps, root);
    }

    private void addLocalProject(Project project, DevModeContext context, Set<AppArtifactKey> addeDeps, boolean root) {
        final AppArtifactKey key = new AppArtifactKey(project.getGroup().toString(), project.getName());
        if (addeDeps.contains(key)) {
            return;
        }
        final JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaConvention == null) {
            return;
        }

        SourceSetContainer sourceSets = javaConvention.getSourceSets();
        SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Set<String> sourcePaths = new HashSet<>();

        for (File sourceDir : mainSourceSet.getAllJava().getSrcDirs()) {
            if (sourceDir.exists()) {
                sourcePaths.add(sourceDir.getAbsolutePath());
            }
        }
        //TODO: multiple resource directories
        final File resourcesSrcDir = mainSourceSet.getResources().getSourceDirectories().getSingleFile();

        if (sourcePaths.isEmpty() && !resourcesSrcDir.exists()) {
            return;
        }

        String classesDir = QuarkusGradleUtils.getClassesDir(mainSourceSet, project.getBuildDir());

        final String resourcesOutputPath;
        if (resourcesSrcDir.exists()) {
            resourcesOutputPath = mainSourceSet.getOutput().getResourcesDir().getAbsolutePath();
            if (!Files.exists(Paths.get(classesDir))) {
                // currently classesDir can't be null and is expected to exist
                classesDir = resourcesOutputPath;
            }
        } else {
            // currently resources dir should exist
            resourcesOutputPath = classesDir;
        }

        DevModeContext.ModuleInfo wsModuleInfo = new DevModeContext.ModuleInfo(
                project.getName(),
                project.getProjectDir().getAbsolutePath(),
                sourcePaths,
                classesDir,
                resourcesSrcDir.getAbsolutePath(),
                resourcesOutputPath);
        if (root) {
            context.setApplicationRoot(wsModuleInfo);
        } else {
            context.getAdditionalModules().add(wsModuleInfo);
        }
        addeDeps.add(key);
    }

    private String getSourceEncoding() {
        return getJavaCompileTask()
                .map(javaCompile -> javaCompile.getOptions().getEncoding())
                .orElse(null);
    }

    private java.util.Optional<JavaCompile> getJavaCompileTask() {
        return java.util.Optional
                .ofNullable((JavaCompile) getProject().getTasks().getByName(JavaPlugin.COMPILE_JAVA_TASK_NAME));
    }

    private void addGradlePluginDeps(StringBuilder classPathManifest, DevModeContext context) {
        boolean foundQuarkusPlugin = false;
        Project prj = getProject();
        while (prj != null && !foundQuarkusPlugin) {
            if (prj.getPlugins().hasPlugin(QuarkusPlugin.ID)) {
                final Set<ResolvedDependency> firstLevelDeps = prj.getBuildscript().getConfigurations().getByName("classpath")
                        .getResolvedConfiguration().getFirstLevelModuleDependencies();
                if (firstLevelDeps.isEmpty()) {
                    // TODO this looks weird
                } else {
                    for (ResolvedDependency rd : firstLevelDeps) {
                        if ("io.quarkus.gradle.plugin".equals(rd.getModuleName())) {
                            rd.getAllModuleArtifacts().stream()
                                    .map(ResolvedArtifact::getFile)
                                    .forEach(f -> addToClassPaths(classPathManifest, f));
                            foundQuarkusPlugin = true;
                            break;
                        }
                    }
                }
            }
            prj = prj.getParent();
        }
        if (!foundQuarkusPlugin) {
            // that's weird, the project may include the plugin but not have it on its classpath
            // this may happen when running the plugin's tests
            // so here we check the property set in the Quarkus functional tests
            final String pluginUnderTestMetaData = System.getProperty("plugin-under-test-metadata.properties");
            if (pluginUnderTestMetaData != null) {
                final Path p = Paths.get(pluginUnderTestMetaData);
                if (Files.exists(p)) {
                    final Properties props = new Properties();
                    try (InputStream is = Files.newInputStream(p)) {
                        props.load(is);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to read " + p, e);
                    }
                    final String classpath = props.getProperty("implementation-classpath");
                    for (String cpElement : classpath.split(File.pathSeparator)) {
                        final File f = new File(cpElement);
                        if (f.exists()) {
                            addToClassPaths(classPathManifest, f);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Unable to find quarkus-gradle-plugin dependency in " + getProject());
            }
        }
    }

    private void addToClassPaths(StringBuilder classPathManifest, File file) {
        if (filesIncludedInClasspath.add(file)) {
            getProject().getLogger().info("Adding dependency {}", file);

            final URI uri = file.toPath().toAbsolutePath().toUri();
            classPathManifest.append(uri).append(" ");
        }
    }

    private URL toUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Failed to convert URI to URL: " + uri, e);
        }
    }

}
