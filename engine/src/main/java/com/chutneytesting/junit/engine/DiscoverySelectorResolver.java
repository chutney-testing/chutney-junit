package com.chutneytesting.junit.engine;

import com.chutneytesting.engine.api.execution.StepDefinitionDto;
import com.chutneytesting.glacio.api.GlacioAdapter;
import com.chutneytesting.junit.api.Chutney;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.*;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.support.descriptor.UriSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toList;

public class DiscoverySelectorResolver {

    public static final String FEATURE_SEGMENT_TYPE = "feature";
    public static final String SCENARIO_SEGMENT_TYPE = "scenario";

    private static final String FEATURE_EXTENSION = ".feature";

    private final PathMatchingResourcePatternResolver pathResolver = new PathMatchingResourcePatternResolver();
    private final GlacioAdapter glacioAdapter;
    private boolean classMode;

    public DiscoverySelectorResolver(GlacioAdapter glacioAdapter) {
        this.glacioAdapter = glacioAdapter;
    }

    public void resolveSelectors(EngineDiscoveryRequest engineDiscoveryRequest, ChutneyEngineDescriptor engineDescriptor) {
        Predicate<String> packageNameFilter = Filter.composeFilters(engineDiscoveryRequest.getFiltersByType(PackageNameFilter.class)).toPredicate();

        // Keep class selector first in line in order to position classMode property
        List<ClassSelector> classSelectors = engineDiscoveryRequest.getSelectorsByType(ClassSelector.class);
        classSelectors.forEach(cs -> resolveClass(engineDescriptor, cs.getJavaClass()));

        List<FileSelector> fileSelectors = engineDiscoveryRequest.getSelectorsByType(FileSelector.class);
        fileSelectors.forEach(fs -> resolveFile(engineDescriptor, fs.getFile()));

        List<DirectorySelector> directorySelectors = engineDiscoveryRequest.getSelectorsByType(DirectorySelector.class);
        directorySelectors.forEach(ds -> resolveDirectory(engineDescriptor, ds.getDirectory()));

        List<ClasspathRootSelector> classpathRootSelectors = engineDiscoveryRequest.getSelectorsByType(ClasspathRootSelector.class);
        classpathRootSelectors.forEach(crs -> resolveClassPathRoot(engineDescriptor, crs.getClasspathRoot(), packageNameFilter));

        List<PackageSelector> packageSelectors = engineDiscoveryRequest.getSelectorsByType(PackageSelector.class);
        packageSelectors.stream()
            .filter(ps -> packageNameFilter.test(ps.getPackageName()))
            .forEach(ps -> resolvePackage(engineDescriptor, ps.getPackageName()));

        List<ClasspathResourceSelector> classpathResourceSelectors = engineDiscoveryRequest.getSelectorsByType(ClasspathResourceSelector.class);
        classpathResourceSelectors.stream()
            .filter(crs -> packageNameFilter.test(crs.getClasspathResourceName().replace("/", ".")))
            .forEach(crs -> resolveClassPathResource(engineDescriptor, crs.getClasspathResourceName()));

        List<UriSelector> uriSelectors = engineDiscoveryRequest.getSelectorsByType(UriSelector.class);
        uriSelectors.forEach(us -> resolveURI(engineDescriptor, us.getUri()));

        // Use UniqueId selectors as filter over current engine descriptor. As such, keep this last in line.
        List<UniqueIdSelector> uniqueIdSelectors = engineDiscoveryRequest.getSelectorsByType(UniqueIdSelector.class);
        resolveUniqueIds(engineDescriptor, uniqueIdSelectors.stream().map(UniqueIdSelector::getUniqueId).collect(toList()));
    }

    private void resolveUniqueIds(ChutneyEngineDescriptor engineDescriptor, List<UniqueId> uniqueIds) {
        if (uniqueIds.isEmpty()) {
            return;
        }

        if (engineDescriptor.getChildren().isEmpty()) {
            resolvePackage(engineDescriptor, "");
        }

        List<String> uniqueIdsStrings = uniqueIds.stream().map(UniqueId::toString).collect(toList());
        List<? extends TestDescriptor> testDescriptors = engineDescriptor.getChildren().stream()
            .flatMap(td -> td.getChildren().stream())
            .filter(ts -> uniqueIdsStrings.stream().noneMatch(uis -> ts.getUniqueId().toString().contains(uis)))
            .collect(toList());

        testDescriptors.forEach(TestDescriptor::removeFromHierarchy);

        List<? extends TestDescriptor> emptyFeatures = engineDescriptor.getChildren().stream().filter(ts -> ts.getChildren().isEmpty()).collect(toList());
        emptyFeatures.forEach(TestDescriptor::removeFromHierarchy);
    }

    private void resolveURI(TestDescriptor parent, URI uri) {
        resolveResource(parent, pathResolver.getResource(uri.toString()));
    }

    private void resolveClassPathResource(TestDescriptor parent, String classPathResourceName) {
        if (hasFeatureExtension(classPathResourceName)) {
            Resource resource = pathResolver.getResource("classpath:" + classPathResourceName);
            resolveResource(parent, resource);
        }
    }

    private void resolveClass(TestDescriptor parent, Class<?> aClass) {
        if (aClass.isAnnotationPresent(Chutney.class)) {
            classMode = true;
            String classPackageName = aClass.getPackage().getName();
            resolvePackage(parent, classPackageName.replace(".", "/"));
        }
    }

    private void resolvePackage(TestDescriptor parent, String packageName) {
        try {
            Resource[] resources = pathResolver.getResources("classpath*:" + packageName.replace(".", "/") + "/**/*" + FEATURE_EXTENSION);
            for (Resource resource : resources) {
                resolveResource(parent, resource);
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException("Cannot get resources from " + packageName, ioe);
        }
    }

    private void resolveClassPathRoot(TestDescriptor parent, URI classpathRoot, Predicate<String> packageNameFilter) {
        try {
            Resource[] resources = pathResolver.getResources(classpathRoot.toString() + "/**/*" + FEATURE_EXTENSION);
            for (Resource resource : resources) {
                if (packageNameFilter.test(resource.getURI().getPath().replace(classpathRoot.getPath(), "").replace("/", "."))) {
                    resolveResource(parent, resource);
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException("Cannot get resources from " + classpathRoot, ioe);
        }
    }

    private void resolveDirectory(TestDescriptor parent, File dir) {
        if (dir.exists() && dir.isDirectory()) {
            try {
                List<Path> features = Files.walk(dir.toPath())
                    .filter(path -> path.toFile().isFile())
                    .filter(path -> hasFeatureExtension(path.toFile().getName()))
                    .collect(toList());

                features.forEach(path -> resolveFile(parent, path.toFile()));
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }
    }

    private void resolveFile(TestDescriptor parent, File file) {
        if (file.exists() && file.isFile()) {
            resolveFeature(parent, file.getName(), content(file), UriSource.from(file.toURI()));
        }
    }

    private void resolveResource(TestDescriptor parent, Resource resource) {
        if (resource.exists()) {
            try {
                resolveFeature(parent, resourceName(resource), content(resource.getInputStream()), UriSource.from(resource.getURI()));
            } catch (IOException ioe) {
                throw new UncheckedIOException("Cannot get inputstream from " + resource.getDescription(), ioe);
            }
        }
    }

    private void resolveFeature(TestDescriptor parent, String name, String featureContent, TestSource testSource) {
        UniqueId uniqueId = parent.getUniqueId().append(FEATURE_SEGMENT_TYPE, name);
        if (!parent.findByUniqueId(uniqueId).isPresent()) {

            FeatureDescriptor featureDescriptor = new FeatureDescriptor(uniqueId, name, featureSource(testSource));

            List<StepDefinitionDto> stepDefinitions = parseFeature(featureContent);
            stepDefinitions.forEach(stepDefinition -> resolveScenario(featureDescriptor, stepDefinition));

            parent.addChild(featureDescriptor);
        }
    }

    private void resolveScenario(FeatureDescriptor parentFeature, StepDefinitionDto stepDefinition) {
        ScenarioDescriptor scenarioDescriptor =
            new ScenarioDescriptor(
                parentFeature.getUniqueId().append(SCENARIO_SEGMENT_TYPE, stepDefinition.name),
                stepDefinition.name,
                scenarioSource(stepDefinition.name, parentFeature.getSource().get()), stepDefinition);

        parentFeature.addChild(scenarioDescriptor);
    }

    private List<StepDefinitionDto> parseFeature(String featureContent) {
        return glacioAdapter.toChutneyStepDefinition(featureContent);
    }

    private String content(File file) {
        try {
            return content(new FileInputStream(file));
        } catch (FileNotFoundException fnfe) {
            throw new UncheckedIOException("Unable to read " + file.getAbsolutePath(), fnfe);
        }
    }

    private String content(InputStream in) {
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ioe) {
            throw new UncheckedIOException("Unable to read inputstream", ioe);
        }
    }

    private String resourceName(Resource resource) {
        try {
            String[] split = resource.getURI().getSchemeSpecificPart().split("/");
            if (split.length > 0) {
                return split[split.length - 1];
            }
            return resource.toString();
        } catch (IOException ioe) {
            throw new UncheckedIOException("Cannot get URI from " + resource.getDescription(), ioe);
        }
    }

    private boolean hasFeatureExtension(String name) {
        return name.endsWith(FEATURE_EXTENSION);
    }

    private TestSource featureSource(TestSource testSource) {
        if (classMode) {
            String name = testSource.toString();
            if (testSource instanceof UriSource) {
                name = ((UriSource) testSource).getUri().getSchemeSpecificPart();
            }
            return ClassSource.from(name);
        }
        return testSource;
    }

    private TestSource scenarioSource(String scenarioName, TestSource testSource) {
        if (testSource instanceof ClassSource) {
            return MethodSource.from(((ClassSource) testSource).getClassName(), scenarioName, "");
        }
        return testSource;
    }
}
