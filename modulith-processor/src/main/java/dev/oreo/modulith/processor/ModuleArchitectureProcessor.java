package dev.oreo.modulith.processor;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.oreo.modulith.core.ModuleApi;
import dev.oreo.modulith.core.ApplicationModule;
import dev.oreo.modulith.core.NamedInterface;
import dev.oreo.modulith.core.PluginModule;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time validator for module boundaries.
 *
 * <p>It rejects cross-module access to non-{@link ModuleApi} types, direct access to another
 * module's {@code internal} packages, undeclared module/API dependencies, duplicate module IDs,
 * and invalid public API declarations.</p>
 */
@SupportedAnnotationTypes({
        "dev.oreo.modulith.core.PluginModule",
        "dev.oreo.modulith.core.ModuleApi",
        "dev.oreo.modulith.core.ApplicationModule",
        "dev.oreo.modulith.core.NamedInterface"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class ModuleArchitectureProcessor extends AbstractProcessor {
    private Trees trees;
    private final Map<String, ModuleInfo> modulesById = new LinkedHashMap<>();
    private final Map<String, ModuleInfo> modulesByPackage = new LinkedHashMap<>();
    private final Set<String> scannedUnits = new HashSet<>();
    private final Set<String> reported = new HashSet<>();
    private final Map<String, Set<String>> exportedApis = new HashMap<>();
    private final Set<String> generatedAnchors = new HashSet<>();
    /** Previous index allows incremental compilation when a dependency's package-info is unchanged. */
    private final Map<String, String> previousMetadata = new LinkedHashMap<>();
    private boolean metadataWritten;
    /** Top-level types still to check once javac has analysed them. */
    private final Set<String> pendingTypes = new HashSet<>();
    private boolean checkAfterAnalysis;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        trees = Trees.instance(processingEnv);
        readPreviousMetadata(StandardLocation.CLASS_OUTPUT);
        readPreviousMetadata(StandardLocation.CLASS_PATH);
        // Resolving references in method bodies during annotation processing forces javac to
        // attribute them early, which reports spurious errors (e.g. lambdas whose parameter type
        // is a captured wildcard). With javac, check each class once it has been analysed instead.
        try {
            JavacTask.instance(processingEnv).addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent event) {
                    if (event.getKind() == TaskEvent.Kind.ANALYZE && event.getTypeElement() != null) {
                        checkAnalyzed(event.getTypeElement(), event.getCompilationUnit());
                    }
                }
            });
            checkAfterAnalysis = true;
        } catch (IllegalArgumentException | LinkageError notJavac) {
            checkAfterAnalysis = false;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        collectModules(roundEnv);
        collectPackageModules(roundEnv);
        validateApiDeclarations(roundEnv);

        if (!roundEnv.processingOver()) {
            if (checkAfterAnalysis) {
                for (Element root : roundEnv.getRootElements()) {
                    if (root instanceof TypeElement type) pendingTypes.add(type.getQualifiedName().toString());
                }
            } else {
                scanRootElements(roundEnv);
            }
        } else if (!metadataWritten) {
            validateDeclaredDependencies();
            writeMetadata();
            metadataWritten = true;
        }
        return false;
    }

    private void collectModules(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(PluginModule.class)) {
            if (!(element instanceof TypeElement type) ||
                    type.getSimpleName().contentEquals("__MinecraftModulithModule")) {
                continue;
            }

            PluginModule annotation = type.getAnnotation(PluginModule.class);
            String id = annotation.value().trim();
            String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();

            if (id.isEmpty()) {
                error(type, "MinecraftModulith module id cannot be blank");
                continue;
            }

            ModuleInfo info = new ModuleInfo(
                    id,
                    packageName,
                    type.getQualifiedName().toString(),
                    List.of(annotation.dependencies()),
                    annotation.configuration(),
                    type,
                    List.of(annotation.uses())
            );

            ModuleInfo previous = modulesById.putIfAbsent(id, info);
            if (previous != null && !previous.className().equals(info.className())) {
                error(type, "Duplicate MinecraftModulith module id '" + id + "' also used by "
                        + previous.className());
            } else {
                modulesByPackage.put(packageName, info);
            }
        }
    }


    private void collectPackageModules(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(ApplicationModule.class)) {
            if (!(element instanceof PackageElement pkg)) continue;
            ApplicationModule annotation = pkg.getAnnotation(ApplicationModule.class);
            String id = annotation.id().trim();
            String packageName = pkg.getQualifiedName().toString();
            if (!id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                error(pkg, "@ApplicationModule id must be a non-blank alphanumeric identifier");
                continue;
            }
            String generated = packageName + ".__MinecraftModulithModule";
            if (attachLifecycleClass(pkg, annotation, id, packageName, generated)) {
                continue;
            }
            ModuleInfo existing = modulesByPackage.get(packageName);
            if (existing != null) {
                // The generated anchor causes a second annotation-processing round.
                if (existing.className().equals(generated)) continue;
                error(pkg, "Do not declare both @PluginModule and @ApplicationModule in " + packageName);
                continue;
            }
            ModuleInfo info = new ModuleInfo(id, packageName, generated,
                    List.of(annotation.allowedDependencies()), annotation.configuration(), pkg, List.of());
            ModuleInfo previous = modulesById.putIfAbsent(id, info);
            if (previous != null && !previous.className().equals(generated)) {
                error(pkg, "Duplicate MinecraftModulith module id '" + id + "'");
                continue;
            }
            modulesByPackage.put(packageName, info);
            if (!generatedAnchors.add(generated)) continue;
            try {
                var file = processingEnv.getFiler().createSourceFile(generated, pkg);
                try (Writer writer = file.openWriter()) {
                    String quoted = quote(annotation.allowedDependencies());
                    String quotedAfter = quote(annotation.after());
                    writer.write("package " + packageName + ";\n");
                    writer.write("@dev.oreo.modulith.core.PluginModule(value=\"" + id +
                            "\", dependencies={" + quoted + "}, after={" + quotedAfter + "}, configuration=" +
                            annotation.configuration() + ", optional=" + annotation.optional() + ")\n");
                    writer.write("public final class __MinecraftModulithModule implements " +
                            "dev.oreo.modulith.core.MinecraftModule {}\n");
                }
            } catch (FilerException alreadyGenerated) {
                // An incremental compilation may retain the prior generated anchor.
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Module anchor already generated: " + generated);
            } catch (IOException ex) {
                error(pkg, "Could not generate module anchor: " + ex.getMessage());
            }
        }
    }

    /**
     * Joins a package-level boundary with a {@link PluginModule} lifecycle class of the same id
     * declared in that package or one of its sub-packages (for example {@code economy.lifecycle}).
     * The module then owns the whole package hierarchy and no anchor class is generated.
     *
     * @return {@code true} when the declaration was handled here
     */
    private boolean attachLifecycleClass(
            PackageElement pkg,
            ApplicationModule annotation,
            String id,
            String packageName,
            String generated
    ) {
        ModuleInfo lifecycle = modulesById.get(id);
        if (lifecycle != null && lifecycle.element() instanceof TypeElement type) {
            if (!isWithin(lifecycle.packageName(), packageName)) {
                return false; // reported as a duplicate id by the caller
            }
            PluginModule declared = type.getAnnotation(PluginModule.class);
            List<String> dependencies = mergeDeclarations(pkg, id, "dependencies",
                    declared.dependencies(), annotation.allowedDependencies());
            mergeDeclarations(pkg, id, "after", declared.after(), annotation.after());
            ModuleInfo merged = new ModuleInfo(id, packageName, lifecycle.className(), dependencies,
                    declared.configuration() || annotation.configuration(), type, List.of(declared.uses()));
            modulesByPackage.remove(lifecycle.packageName());
            modulesByPackage.put(packageName, merged);
            modulesById.put(id, merged);
            return true;
        }

        // Incremental build: the unchanged lifecycle class is not part of this compilation.
        String previous = previousMetadata.get(id);
        if (lifecycle == null && previous != null) {
            String[] columns = previous.split("\\|", -1);
            String className = columns[1];
            int dot = className.lastIndexOf('.');
            String classPackage = dot < 0 ? "" : className.substring(0, dot);
            if (!className.equals(generated) && isWithin(classPackage, packageName)) {
                List<String> dependencies = new ArrayList<>(List.of(annotation.allowedDependencies()));
                if (!columns[2].isBlank()) {
                    for (String dependency : columns[2].split(",")) {
                        if (!dependencies.contains(dependency)) dependencies.add(dependency);
                    }
                }
                List<String> uses = columns.length > 4 && !columns[4].isBlank() ? List.of(columns[4].split(",")) : List.of();
                ModuleInfo info = new ModuleInfo(id, packageName, className, dependencies,
                        annotation.configuration() || Boolean.parseBoolean(columns[3]), pkg, uses);
                modulesById.put(id, info);
                modulesByPackage.put(packageName, info);
                return true;
            }
        }
        return false;
    }

    private List<String> mergeDeclarations(
            PackageElement pkg,
            String id,
            String attribute,
            String[] fromClass,
            String[] fromPackage
    ) {
        Set<String> classValues = new java.util.LinkedHashSet<>(List.of(fromClass));
        Set<String> packageValues = new java.util.LinkedHashSet<>(List.of(fromPackage));
        if (!classValues.isEmpty() && !packageValues.isEmpty() && !classValues.equals(packageValues)) {
            error(pkg, "Module '" + id + "' declares different " + attribute + " on @ApplicationModule "
                    + packageValues + " and @PluginModule " + classValues + "; declare them in one place");
        }
        Set<String> merged = new java.util.LinkedHashSet<>(classValues);
        merged.addAll(packageValues);
        return List.copyOf(merged);
    }

    private static boolean isWithin(String packageName, String root) {
        return packageName.equals(root) || packageName.startsWith(root + ".");
    }

    private void validateApiDeclarations(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(ModuleApi.class)) {
            if (!(element instanceof TypeElement type)) {
                continue;
            }

            ModuleApi api = type.getAnnotation(ModuleApi.class);
            String apiName = api.value().trim();
            if (apiName.isEmpty() || apiName.contains("::")) {
                error(type, "@ModuleApi name must be non-blank and cannot contain '::'");
            }
            if (!type.getModifiers().contains(Modifier.PUBLIC)) {
                error(type, "@ModuleApi type must be public: " + type.getQualifiedName());
            }

            String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
            if (packageName.contains(".internal") || packageName.endsWith(".internal")) {
                error(type, "@ModuleApi cannot be declared from an internal package: " + packageName);
            }
            ModuleInfo owner = ownerOf(packageName, modulesByPackage.values().stream()
                    .sorted(Comparator.comparingInt((ModuleInfo info) -> info.packageName().length()).reversed())
                    .toList());
            if (owner != null) exportedApis.computeIfAbsent(owner.id(), ignored -> new HashSet<>()).add(apiName);
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(NamedInterface.class)) {
            if (!(element instanceof PackageElement pkg)) continue;
            NamedInterface annotation = pkg.getAnnotation(NamedInterface.class);
            String value = annotation.value().trim();
            String packageName = pkg.getQualifiedName().toString();
            if (value.isBlank() || value.contains("::")) {
                error(pkg, "@NamedInterface must have a non-blank name without '::'");
            }
            if (packageName.contains(".internal") || packageName.endsWith(".internal")) {
                error(pkg, "@NamedInterface cannot expose internal packages: " + packageName);
            }
            ModuleInfo owner = ownerOf(packageName, modulesByPackage.values().stream()
                    .sorted(Comparator.comparingInt((ModuleInfo info) -> info.packageName().length()).reversed())
                    .toList());
            if (owner == null) error(pkg, "Named API package is not owned by a module: " + packageName);
            else exportedApis.computeIfAbsent(owner.id(), ignored -> new HashSet<>()).add(value);
        }
    }

    private void validateDeclaredDependencies() {
        for (ModuleInfo module : modulesById.values()) {
            for (String raw : module.accessible()) {
                String targetId = raw;
                int delimiter = raw.indexOf("::");
                if (delimiter >= 0) {
                    targetId = raw.substring(0, delimiter);
                    String api = raw.substring(delimiter + 2);
                    if (api.isBlank()) {
                        error(module.element(), "Invalid dependency selector '" + raw + "'");
                    }
                }
                if (targetId.isBlank()) {
                    error(module.element(), "Invalid dependency selector '" + raw + "'");
                } else if (!modulesById.containsKey(targetId) &&
                        !previousMetadata.containsKey(targetId)) {
                    error(module.element(), "Module '" + module.id() + "' depends on missing module '" + targetId + "'");
                } else if (delimiter >= 0) {
                    String selectedApi = raw.substring(delimiter + 2).trim();
                    Set<String> discovered = exportedApis.get(targetId);
                    // Incremental javac may process only a subset of package-info files
                    // for a previously compiled module. Even a non-empty discovered set
                    // can be incomplete, so check unknown names only on a clean build.
                    if (!previousMetadata.containsKey(targetId) &&
                            discovered != null && !discovered.isEmpty() &&
                            !discovered.contains(selectedApi)) {
                        error(module.element(), "Module '" + module.id() + "' depends on unknown named API '" +
                                raw + "'. Available: " + discovered);
                    }
                }
            }
        }
    }

    private void scanRootElements(RoundEnvironment roundEnv) {
        List<ModuleInfo> owners = modulesByPackage.values().stream()
                .sorted(Comparator.comparingInt((ModuleInfo info) -> info.packageName().length()).reversed())
                .toList();

        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement sourceType)) {
                continue;
            }

            PackageElement sourcePackageElement = processingEnv.getElementUtils().getPackageOf(sourceType);
            String sourcePackage = sourcePackageElement.getQualifiedName().toString();
            ModuleInfo sourceModule = ownerOf(sourcePackage, owners);
            if (sourceModule == null) {
                continue;
            }

            TreePath path = trees.getPath(sourceType);
            if (path == null) {
                continue;
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            String unitKey = unit.getSourceFile() == null
                    ? sourceType.getQualifiedName().toString()
                    : unit.getSourceFile().toUri().toString();
            if (!scannedUnits.add(unitKey)) {
                continue;
            }

            new BoundaryScanner(sourceModule, owners).scan(unit, null);
        }
    }

    private List<ModuleInfo> owners() {
        return modulesByPackage.values().stream()
                .sorted(Comparator.comparingInt((ModuleInfo info) -> info.packageName().length()).reversed())
                .toList();
    }

    /** Checks one analysed top-level type, plus its file's imports the first time the file is seen. */
    private void checkAnalyzed(TypeElement type, CompilationUnitTree unit) {
        if (!pendingTypes.remove(type.getQualifiedName().toString())) {
            return;
        }
        List<ModuleInfo> owners = owners();
        String sourcePackage = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        ModuleInfo sourceModule = ownerOf(sourcePackage, owners);
        if (sourceModule == null) {
            return;
        }
        BoundaryScanner scanner = new BoundaryScanner(sourceModule, owners);
        if (unit != null) {
            String unitKey = unit.getSourceFile() == null ? type.getQualifiedName().toString() : unit.getSourceFile().toUri().toString();
            if (scannedUnits.add(unitKey)) {
                TreePath unitPath = new TreePath(unit);
                for (ImportTree importTree : unit.getImports()) {
                    scanner.scan(new TreePath(unitPath, importTree), null);
                }
            }
        }
        TreePath path = trees.getPath(type);
        if (path != null) {
            scanner.scan(path, null);
        }
    }

    private ModuleInfo ownerOf(String packageName, List<ModuleInfo> modules) {
        for (ModuleInfo module : modules) {
            if (packageName.equals(module.packageName())
                    || packageName.startsWith(module.packageName() + ".")) {
                return module;
            }
        }
        return null;
    }

    private TypeElement enclosingType(Element element) {
        Element current = element;
        while (current != null && !(current instanceof TypeElement)) {
            current = current.getEnclosingElement();
        }
        return (TypeElement) current;
    }

    private void validateReference(
            ModuleInfo sourceModule,
            List<ModuleInfo> owners,
            TreePath path
    ) {
        Element referenced = trees.getElement(path);
        if (referenced == null) {
            return;
        }

        TypeElement targetType = enclosingType(referenced);
        if (targetType == null) {
            return;
        }

        String targetPackage = processingEnv.getElementUtils()
                .getPackageOf(targetType)
                .getQualifiedName()
                .toString();
        ModuleInfo targetModule = ownerOf(targetPackage, owners);
        if (targetModule == null || targetModule.id().equals(sourceModule.id())) {
            return;
        }

        String key = sourceModule.id() + "|" + targetType.getQualifiedName() + "|" + path.getLeaf();
        if (!reported.add(key)) {
            return;
        }

        String relative = targetPackage.substring(targetModule.packageName().length());
        if ((relative + ".").contains(".internal.")) {
            errorAt(path, "Module '" + sourceModule.id() + "' cannot access internal type "
                    + targetType.getQualifiedName() + " from module '" + targetModule.id() + "'");
            return;
        }

        ModuleApi api = targetType.getAnnotation(ModuleApi.class);
        PackageElement targetPackageElement = processingEnv.getElementUtils().getPackageElement(targetPackage);
        NamedInterface named = targetPackageElement == null ? null :
                targetPackageElement.getAnnotation(NamedInterface.class);
        if (api == null && named == null) {
            errorAt(path, "Cross-module reference to " + targetType.getQualifiedName()
                    + " is not allowed because it is not annotated with @ModuleApi");
            return;
        }

        String apiName = api != null ? api.value().trim() : named.value().trim();
        boolean allowed = sourceModule.accessible().stream().anyMatch(raw -> {
            if (raw.equals(targetModule.id())) {
                return true;
            }
            return raw.equals(targetModule.id() + "::" + apiName);
        });
        if (!allowed) {
            errorAt(path, "Module '" + sourceModule.id() + "' must declare dependency '"
                    + targetModule.id() + "' or '" + targetModule.id() + "::" + apiName
                    + "' to access " + targetType.getQualifiedName());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void errorAt(TreePath path, String message) {
        trees.printMessage(Diagnostic.Kind.ERROR, message, path.getLeaf(), path.getCompilationUnit());
    }

    private void readPreviousMetadata(StandardLocation location) {
        try {
            FileObject file = processingEnv.getFiler().getResource(
                    location, "", "META-INF/minecraft-modulith/modules.idx");
            try (BufferedReader reader = new BufferedReader(file.openReader(true))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split("\\|", -1);
                    if (columns.length >= 4 && !columns[0].isBlank()) {
                        previousMetadata.putIfAbsent(columns[0], line);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Clean build or no previous module index on the classpath.
        }
    }

    private void writeMetadata() {
        if (modulesById.isEmpty() && previousMetadata.isEmpty()) {
            return;
        }
        try {
            Filer filer = processingEnv.getFiler();
            var resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/minecraft-modulith/modules.idx"
            );
            try (Writer writer = resource.openWriter()) {
                for (ModuleInfo info : modulesById.values()) {
                    writer.write(info.id());
                    writer.write("|");
                    writer.write(info.className());
                    writer.write("|");
                    writer.write(String.join(",", info.dependencies()));
                    writer.write("|");
                    writer.write(Boolean.toString(info.configuration()));
                    writer.write("|");
                    writer.write(String.join(",", info.uses()));
                    writer.write(System.lineSeparator());
                }
                for (var entry : previousMetadata.entrySet()) {
                    if (!modulesById.containsKey(entry.getKey())) {
                        writer.write(entry.getValue());
                        writer.write(System.lineSeparator());
                    }
                }
            }
        } catch (IOException exception) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "Could not write MinecraftModulith module metadata: " + exception.getMessage()
            );
        }
    }

    private final class BoundaryScanner extends TreePathScanner<Void, Void> {
        private final ModuleInfo sourceModule;
        private final List<ModuleInfo> owners;

        private BoundaryScanner(ModuleInfo sourceModule, List<ModuleInfo> owners) {
            this.sourceModule = sourceModule;
            this.owners = owners;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            validateReference(sourceModule, owners, getCurrentPath());
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            validateReference(sourceModule, owners, getCurrentPath());
            return super.visitMemberSelect(node, unused);
        }
    }

    private static String quote(String[] values) {
        return java.util.Arrays.stream(values)
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private record ModuleInfo(
            String id,
            String packageName,
            String className,
            List<String> dependencies,
            boolean configuration,
            Element element,
            List<String> uses
    ) {
        /** Selectors that grant API access: lifecycle dependencies plus runtime-only uses. */
        List<String> accessible() {
            List<String> all = new ArrayList<>(dependencies);
            all.addAll(uses);
            return all;
        }
    }
}
