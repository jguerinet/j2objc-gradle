/*
 * Copyright (c) 2015 the authors of j2objc-gradle (see AUTHORS file)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.j2objccontrib.j2objcgradle.tasks

import com.github.j2objccontrib.j2objcgradle.J2objcConfig
import com.google.common.annotations.VisibleForTesting
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.util.regex.Matcher

/**
 * Updates the Xcode project with j2objc generated files and resources.
 * <p/>
 * This uses the CocoaPods dependency system. For more details see
 * https://cocoapods.org/.
 * <p/>
 * It creates a podspec file and inserts it into your project's pod file.
 * If you haven't create a pod file yet you have to run `pod init` in your
 * project folder before you run this task.
 */
@CompileStatic
class PodspecTask extends DefaultTask {

    // Generated ObjC source files and main resources
    // Not @InputDirectory as the podspec doesn't depend on the directory contents, only the path
    @Input
    File getDestSrcMainObjDirFile() {
        return J2objcConfig.from(project).getDestSrcDirFile('main', 'objc')
    }
    // Not @InputDirectory as the podspec doesn't depend on the directory contents, only the path
    @Input
    File getDestSrcMainResourcesDirFile() {
        return J2objcConfig.from(project).getDestSrcDirFile('main', 'resources')
    }

    @Input
    String getJ2objcHome() { return Utils.j2objcHome(project) }

    @Input
    String getLibName() { return "${project.name}-j2objc" }

    // Default: build/j2objcOutputs
    // Podspec Requirements require this location:
    //     Podspecs should be located at the root of the repository, and paths to files should be specified relative
    //     to the root of the repository as well. File patterns do not support traversing the parent directory ( .. ).
    //     https://guides.cocoapods.org/syntax/podspec.html#group_file_patterns
    @Input
    File getDestPodspecDirFile() { return J2objcConfig.from(project).getDestPodspecDirFile() }

    @Input
    File getDestLibDirFile() { return J2objcConfig.from(project).getDestLibDirFile() }

    String getBasePodName() {
        return J2objcConfig.from(project).getPodName()
    }

    @Input
    String getMinVersionIos() { return J2objcConfig.from(project).getMinVersionIos() }

    @OutputFile
    File getPodspec() {
        return new File(getDestPodspecDirFile(), "${getBasePodName()}.podspec")
    }


    @TaskAction
    void podspecWrite() {
        // TODO: allow custom list of libraries
        // podspec paths must be relative to podspec file, which is in buildDir
        String resourceIncludePath = Utils.relativizeNonParent(getDestPodspecDirFile(), getDestSrcMainResourcesDirFile())
        String relativeHeaderIncludePath = Utils.relativizeNonParent(getDestPodspecDirFile(), getDestSrcMainObjDirFile())
        // iOS packed libraries are shared with watchOS
        String libDirIos = Utils.relativizeNonParent(getDestPodspecDirFile(), new File(getDestLibDirFile(), 'iosRelease'))

        validateNumericVersion(getMinVersionIos(), 'minVersionIos')

        J2objcConfig config = J2objcConfig.from(project)
        String author = config.getPodAuthor()
        String license = config.getPodLicense()
        String homepageURL = config.getPodHomepageURL()
        String sourceURL = config.getPodSourceURL()
        String version = config.getPodVersion()

        String podspecContents = genPodspec(getBasePodName(), relativeHeaderIncludePath, resourceIncludePath, libDirIos,
                getMinVersionIos(), getLibName(), getJ2objcHome(), author, license, homepageURL, sourceURL, version)

        Utils.projectMkDir(project, getDestPodspecDirFile())

        // Delete the podspec if it exists already
        if (getPodspec().exists()) {
            getPodspec().delete()
        }

        logger.debug("Writing podspec... ${getPodspec()}")
        getPodspec().write(podspecContents)
    }

    // Podspec references are relative to project.buildDir
    @VisibleForTesting
    static String genPodspec(String podname, String publicHeadersDir, String resourceDir,
                             String libDirIos, String minVersionIos, String libName, String j2objcHome, String author,
                             String license, String homepageURL, String sourceURL, String version) {

        // Relative paths for content referenced by CocoaPods
        validatePodspecPath(libDirIos, true)
        validatePodspecPath(resourceDir, true)

        // Absolute paths for Xcode command line
        validatePodspecPath(j2objcHome, false)
        validatePodspecPath(publicHeadersDir, true)

        // TODO: CocoaPods strongly recommends switching from 'resources' to 'resource_bundles'
        // http://guides.cocoapods.org/syntax/podspec.html#resource_bundles

        // TODO: replace xcconfig with {pod|user}_target_xcconfig
        // See 'Split of xcconfig' from: http://blog.cocoapods.org/CocoaPods-0.38/

        String podsDirectory = "\$(PODS_ROOT)/$podname"

        // File and line separators assumed to be '/' and '\n' as podspec can only be used on OS X
        return "Pod::Spec.new do |s|\n" +
               "    s.name = '$podname'\n" +
               "    s.version = '$version'\n" +
               "    s.summary = 'Generated by the J2ObjC Gradle Plugin.'\n" +
               "    s.homepage = '$homepageURL'\n" +
               "    s.license = '$license'\n" +
               "    s.author = '$author'\n" +
               "    s.source = { :git => '$sourceURL', :tag => s.version.to_s }\n" +
               "    s.resources = '$resourceDir/**/*'\n" +
               "    s.requires_arc = true\n" +
               "    s.libraries = 'ObjC', 'guava', 'javax_inject', 'jre_emul', 'jsr305', 'z', 'icucore'\n" +
               "    s.xcconfig = {\n" +
               "        'HEADER_SEARCH_PATHS' => '$podsDirectory/j2objc/include $podsDirectory/$publicHeadersDir'\n" +
               "    }\n" +
               // http://guides.cocoapods.org/syntax/podspec.html#deployment_target
               "    s.ios.xcconfig = {\n" +
               "        'LIBRARY_SEARCH_PATHS' => '$podsDirectory/j2objc/lib'\n" +
               "    }\n" +
               "    s.ios.deployment_target = '$minVersionIos'\n" +
               "    s.ios.vendored_libraries = '$libDirIos/lib${libName}.a'\n" +
               "    s.prepare_command = <<-CMD\n" +
               "        ./download_distribution.sh\n" +
               "    CMD\n" +
                // Path to the headers for our library and J2ObjC
               "    s.preserve_paths = 'j2objc', 'src'\n" +
                // Headers for J2ObjC
               "    s.header_mappings_dir = 'j2objc/include'\n" +
               "end\n"
    }

    @VisibleForTesting
    void validateNumericVersion(String version, String type) {
        if (version.equals("")) {
            return
        }
        // Requires at least a major and minor version number
        Matcher versionMatcher = (version =~ /^[0-9]*(\.[0-9]+)+$/)
        if (!versionMatcher.find()) {
            logger.warn("Non-numeric version for $type: $version")
        }
    }

    @VisibleForTesting
    static void validatePodspecPath(String path, boolean relativeRequired) {
        if (path.contains('//')) {
            throw new InvalidUserDataException("Path shouldn't have '//': $path")
        }
        if (path.endsWith('/')) {
            throw new InvalidUserDataException("Path shouldn't end with '/': $path")
        }
        if (path.endsWith('*')) {
            throw new InvalidUserDataException("Only genPodspec(...) should add '*': $path")
        }
        // Hack to recognize absolute path on Windows, only relevant in unit tests run on Windows
        boolean absolutePath = path.startsWith('/') ||
                               (path.startsWith('C:\\') && Utils.isWindowsNoFake())
        if (relativeRequired && absolutePath) {
            throw new InvalidUserDataException("Path shouldn't be absolute: $path")
        }
        if (!relativeRequired && !absolutePath) {
            throw new InvalidUserDataException("Path shouldn't be relative: $path")
        }
        if (relativeRequired && path.startsWith('../')) {
            // Pod references must be relative to podspec and not traverse parent, i.e. '../'
            // https://guides.cocoapods.org/syntax/podspec.html#group_file_patterns
            throw new InvalidUserDataException("Path can't traverse parent: $path")
        }
    }
}
